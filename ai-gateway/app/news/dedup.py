# -*- coding: utf-8 -*-
"""资讯三层判重（Redis 单一存储；网关不连 MySQL，落库放行权在 Java 编排层）。

层与结论（verdict → flags.stored 映射**钉死**）：
- L1 精确：newsp:fp:{bodySha256} SETNX EX TTL → 失败 = exact_duplicate（stored=false）
- L3 标题：同源+同窗（ZSET idx + HASH meta）逐对 title_ratio：
    ≥ TITLE_RATIO → near_duplicate（stored=false）
    0.75 ≤ ratio < TITLE_RATIO → needs_review（stored=false，审计进 newsp:review）
- L2 simhash：同源窗内候选全对参 hamming < SIMHASH_BITS → 仅产 needsReview 提示
    与 fingerprint.hammingHits，**不改 verdict**（提示层，恒可入库）。
- 跨源（不同 originSite）不互判；相似条目放 relatedHint。
- Redis 不可用 → verdict=unavailable + stored=true + needsReview=true
    （属 Java 放行集；兜底移交 Java existsByTitle + 唯一键，变体标题拦不住为已知缺口）。
"""
import json
import logging
import os
import time

from .fingerprint import (body_fingerprint, hamming64, simhash64, title_fingerprint,
                          title_ratio)

logger = logging.getLogger("lanlink.ai-gateway")

DEDUP_TTL_SECONDS = int(os.environ.get("LANLINK_NEWSPIPELINE_DEDUP_TTL_SECONDS", "2592000"))
WINDOW_HOURS = int(os.environ.get("LANLINK_NEWSPIPELINE_WINDOW_HOURS", "72"))
SIMHASH_BITS = int(os.environ.get("LANLINK_NEWSPIPELINE_SIMHASH_BITS", "20"))
TITLE_RATIO = float(os.environ.get("LANLINK_NEWSPIPELINE_TITLE_RATIO", "0.9"))
#: 0.75 ≤ ratio < TITLE_RATIO 的灰区下限（needs_review）
NEEDS_REVIEW_RATIO = 0.75
#: 单源 simhash 候选上限（全对参，不做桶检索）
MAX_CANDIDATES_PER_SITE = 15

KEY_FP = "newsp:fp:{sha}"
KEY_IDX = "newsp:fp:idx:{site}"
KEY_META = "newsp:fp:meta:{sha}"
KEY_REVIEW = "newsp:review"

#: verdict → stored（单一真值；unique/unavailable 属 Java 放行集）
VERDICT_STORED = {
    "unique": True,
    "unavailable": True,
    "exact_duplicate": False,
    "near_duplicate": False,
    "needs_review": False,
}


def stored_for(verdict: str) -> bool:
    return VERDICT_STORED.get(verdict, False)


class Deduplicator:
    """Redis 判重器；redis 客户端不可达时全部降级为 unavailable（放行集）。"""

    def __init__(self, redis_client=None):
        self._redis = redis_client

    def _client(self):
        if self._redis is None:
            # 复用进程级单例的延迟探测客户端。单例 CACHE 在 main.py:488 实例化
            # （semantic_cache 模块里没有该名字，直接 import 会 ImportError →
            # 恒降级 unavailable，判重从未写入——历史缺陷），故延迟经 app.main 取。
            # main.py 顶部 import 了本包路由，反向延迟导入避开循环依赖。
            from .. import main as _main
            self._redis = _main.CACHE._get_redis()
        return self._redis

    def _unavailable(self) -> dict:
        return {"verdict": "unavailable", "matchedId": None, "relatedHint": None,
                "windowHours": WINDOW_HOURS}

    def check(self, *, url: str, title: str, body: str, published_at: str) -> dict:
        """对单条资讯做三层判重，返回 dedup 段结构（verdict/matchedId/relatedHint）。

        verdict 落定后按 verdict+source 计 news_dedup_total（unavailable 降级同样计数）。
        """
        body_sha = body_fingerprint(body)
        title_sha = title_fingerprint(title)
        sh = simhash64(title + "\n" + body[:4000])
        site = _site_of(url)
        try:
            client = self._client()
            if client is None:
                out = self._unavailable()
            else:
                out = self._check_with(client, url=url, site=site, title=title,
                                       body_sha=body_sha, title_sha=title_sha, sh=sh,
                                       published_at=published_at)
        except Exception as exc:
            logger.warning("[news-dedup] redis 不可用，降级 unavailable: %s", exc)
            self._redis = None  # 下次重新探测
            out = self._unavailable()
        self._metric_inc(out.get("verdict", "unavailable"), site)
        return out

    @staticmethod
    def _metric_inc(verdict: str, source: str) -> None:
        try:
            from ..metrics import REGISTRY
            REGISTRY.news_dedup_total.inc({"verdict": verdict, "source": source or "unknown"})
        except Exception:  # noqa: BLE001 - 指标失败不影响判重主链路
            pass

    def _check_with(self, client, *, url, site, title, body_sha, title_sha, sh,
                    published_at) -> dict:
        # ---- L1 精确指纹 ----
        fp_key = KEY_FP.format(sha=body_sha)
        first = client.set(fp_key, "1", ex=DEDUP_TTL_SECONDS, nx=True)
        if not first:
            # matchedId = 首次入库记录的 bodySha256（键本身即指纹，验收 #4 口径）
            return {"verdict": "exact_duplicate", "matchedId": body_sha,
                    "relatedHint": None, "windowHours": WINDOW_HOURS}
        client.expire(fp_key, DEDUP_TTL_SECONDS)

        # ---- 同源候选集（L3 标题 / L2 simhash 共用）----
        now = time.time()
        epoch = _parse_epoch(published_at, now)
        idx_key = KEY_IDX.format(site=site)
        try:
            client.zremrangebyscore(idx_key, "-inf", epoch - WINDOW_HOURS * 3600)
        except Exception:
            pass
        members = client.zrange(idx_key, 0, -1)
        candidates = []
        for m in members[:MAX_CANDIDATES_PER_SITE]:
            m_str = m.decode() if isinstance(m, bytes) else str(m)
            meta_raw = client.hgetall(KEY_META.format(sha=m_str))
            if not meta_raw:
                continue
            meta = {k.decode() if isinstance(k, bytes) else k:
                    v.decode() if isinstance(v, bytes) else v
                    for k, v in meta_raw.items()}
            candidates.append({"sha": m_str, **meta})
        if len(members) > MAX_CANDIDATES_PER_SITE:
            candidates = candidates[:MAX_CANDIDATES_PER_SITE]

        # ---- L3 标题近重（硬拒层）----
        related: list[str] = []
        hamming_hits: list[dict] = []
        near_ratio = 0.0
        for cand in candidates:
            cand_title = cand.get("title") or ""
            if cand_title:
                r = title_ratio(title, cand_title)
                if r >= TITLE_RATIO:
                    return {"verdict": "near_duplicate", "matchedId": cand.get("sha"),
                            "relatedHint": [cand.get("origin_url") or ""],
                            "windowHours": WINDOW_HOURS}
                if NEEDS_REVIEW_RATIO <= r < TITLE_RATIO:
                    near_ratio = max(near_ratio, r)
                    related.append(cand.get("origin_url") or "")
            # ---- L2 simhash 提示层 ----
            try:
                cand_sh = int(cand.get("simhash") or "0", 16)
            except ValueError:
                cand_sh = 0
            bits = hamming64(sh, cand_sh)
            if cand_sh and bits < SIMHASH_BITS:
                hamming_hits.append({"matchedOriginUrl": cand.get("origin_url") or "",
                                     "bits": bits})

        verdict = "unique"
        needs_review = bool(hamming_hits) or near_ratio > 0
        if near_ratio > 0:
            verdict = "needs_review"
        elif hamming_hits:
            verdict = "unique"  # simhash 仅 needsReview 提示，不改 verdict

        # ---- 登记本条（供后续条目比对）----
        try:
            client.hset(KEY_META.format(sha=body_sha), mapping={
                "title": title, "title_norm": title_fingerprint(title),
                "simhash": f"{sh:016x}", "origin_url": url,
                "published_at": published_at or "",
            })
            client.expire(KEY_META.format(sha=body_sha), DEDUP_TTL_SECONDS)
            client.zadd(idx_key, {body_sha: epoch})
            client.expire(idx_key, DEDUP_TTL_SECONDS)
        except Exception as exc:
            logger.warning("[news-dedup] 候选集登记失败: %s", exc)

        related_hint = related or (hamming_hits and [h["matchedOriginUrl"] for h in hamming_hits]) or None
        return {"verdict": verdict, "matchedId": None,
                "relatedHint": related_hint or None, "windowHours": WINDOW_HOURS,
                "_needs_review": needs_review, "_hamming_hits": hamming_hits,
                "_body_sha": body_sha, "_title_sha": title_sha,
                "_simhash": f"{sh:016x}"}

    def enqueue_review(self, item_url: str, title: str, verdict: str, evidence: dict) -> bool:
        """needs_review 审计：LPUSH newsp:review {json} + EXPIRE=窗长。失败不影响主链路。"""
        try:
            client = self._client()
            if client is None:
                return False
            payload = json.dumps({"url": item_url, "title": title, "verdict": verdict,
                                  "evidence": evidence, "at": time.time()},
                                 ensure_ascii=False)
            client.lpush(KEY_REVIEW, payload)
            client.expire(KEY_REVIEW, WINDOW_HOURS * 3600)
            return True
        except Exception as exc:
            logger.warning("[news-dedup] newsp:review 写入失败: %s", exc)
            return False


def _site_of(url: str) -> str:
    from urllib.parse import urlparse
    try:
        return (urlparse(url).netloc or "unknown").lower()
    except Exception:
        return "unknown"


def _parse_epoch(published_at: str, fallback: float) -> float:
    if published_at:
        try:
            from datetime import datetime
            s = published_at.replace("Z", "+00:00")
            dt = datetime.fromisoformat(s)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=__import__("datetime").timezone.utc)
            return dt.timestamp()
        except Exception:
            pass
    return fallback
