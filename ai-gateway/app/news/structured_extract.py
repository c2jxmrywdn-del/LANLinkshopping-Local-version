# -*- coding: utf-8 -*-
"""资讯结构化抓取管线：robots 门禁 → 守卫抓取（逐跳留痕）→ 正文提取 → 指纹/判重。

不走 fetch_page 的 summarize 压缩（Java 侧需要原文与元数据做入库映射）；
提取失败降级 _SimpleTextExtractor → raw 原文保留，绝不静默丢弃。
"""
import json
import logging
import os
import time
from datetime import datetime, timezone, timedelta
from urllib.parse import urlparse

from .. import tools as _tools
from ..compliance.robots_gate import GATE as ROBOTS_GATE, NEWS_UA
from .dedup import Deduplicator, stored_for
from .fingerprint import body_fingerprint, simhash_hex, title_fingerprint

logger = logging.getLogger("lanlink.ai-gateway")

MAX_BYTES = int(os.environ.get("LANLINK_NEWSPIPELINE_MAX_BYTES", str(2 * 1024 * 1024)))
MAX_REDIRECTS = int(os.environ.get("LANLINK_NEWSPIPELINE_MAX_REDIRECTS", "5"))
TIMEOUT_MS = int(os.environ.get("LANLINK_NEWSPIPELINE_TIMEOUT_MS", "8000"))
PIPELINE_ENABLED = (os.environ.get("LANLINK_NEWSPIPELINE_ENABLED", "0").strip().lower()
                    in ("1", "true", "yes", "on"))
#: 正文低于该长度视为提取失败，走降级链
MIN_BODY_CHARS = 200
#: needs_review 审计列表 TTL = 窗长（秒）
REVIEW_TTL_SECONDS = int(os.environ.get("LANLINK_NEWSPIPELINE_WINDOW_HOURS", "72")) * 3600

DEDUPLICATOR = Deduplicator()


def _iso_cn(dt_raw: str | None) -> str | None:
    """trafilatura date → ISO8601；无时区按东八区补 +08:00。"""
    if not dt_raw:
        return None
    s = str(dt_raw).strip()
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        try:
            dt = datetime.strptime(s[:19], "%Y-%m-%dT%H:%M:%S")
        except ValueError:
            return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone(timedelta(hours=8)))
    return dt.isoformat(timespec="seconds")


def _origin_of(url: str) -> str:
    u = urlparse(url)
    return f"{u.scheme.lower()}://{u.netloc.lower()}"


def extract(url: str, *, source_hint: str = "", max_chars: int = 20000) -> dict:
    """单条结构化抓取。返回 outputSchema 的 data 段；网关内部异常时返回 None（由路由折成 ok=false）。

    语义：robots 拒/SSRF/抓取失败都返回 ok=true 的留痕结构（flags.stored=false），
    只有管线自身崩溃才由路由层折成 ok=false。
    """
    t0 = time.time()
    errors: list[str] = []
    robots_checked: list[dict] = []
    redirect_chain: list[dict] = []
    raw_text = ""
    extractor_chain = ""

    # ---- 0. 前置 SSRF 守卫（复用 tools._ssrf_guard 单一实现）----
    guard = _tools._ssrf_guard(url)
    if guard:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             f"SSRF 守卫拦截: {guard}", "ssrf_blocked", t0)

    # ---- 1. robots 门禁（逐 origin：入口 origin + 重定向进入的每个新 origin）----
    origin = _origin_of(url)
    allowed, delay, checked_at, r_source = ROBOTS_GATE.can_fetch(url)
    robots_checked.append({"origin": origin, "allowed": allowed,
                           "crawlDelaySec": delay, "checkedAt": checked_at,
                           "source": r_source})
    if not allowed:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             "robots.txt 禁止抓取", "robots_denied", t0)

    # ---- 2. 守卫抓取 + 逐跳留痕（on_hop 内做跨 origin robots 复查）----
    current_url = url
    final_status = 0
    final_ctype = ""

    def _on_hop(hop: int, hop_url: str, status: int, location: str | None,
                next_url: str | None) -> None:
        nonlocal current_url, final_status, final_ctype
        redirect_chain.append({"hop": hop, "url": hop_url, "status": status,
                               "location": location})
        if next_url is None:
            current_url = hop_url
            final_status = status
            return
        # 跨 origin 才复查 robots（on_hop 抛异常会中止 _guarded_get 整条重定向链）
        next_origin = _origin_of(next_url)
        if next_origin != _origin_of(hop_url):
            n_allowed, n_delay, n_at, n_source = ROBOTS_GATE.can_fetch(next_url)
            robots_checked.append({"origin": next_origin, "allowed": n_allowed,
                                   "crawlDelaySec": n_delay, "checkedAt": n_at,
                                   "source": n_source})
            if not n_allowed:
                raise _RobotsHopDenied(f"robots.txt 禁止跳转目标 {next_origin}")
        current_url = next_url

    headers = {"User-Agent": NEWS_UA, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"}
    try:
        status, ctype, body = _tools._guarded_get(
            url, timeout=min(TIMEOUT_MS / 1000.0, 30.0), max_bytes=MAX_BYTES,
            headers=headers, on_hop=_on_hop, max_redirects=MAX_REDIRECTS)
        final_status, final_ctype = status, ctype
        # 链尾终点 URL 以 on_hop 落定的 current_url 为准（验收：article.url=链尾终点）；
        # 仅当 _guarded_get 未触任何 on_hop（防御：异常实现）才退回入口 URL。
        if not redirect_chain:
            current_url = url
    except _RobotsHopDenied as exc:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             str(exc), "robots_denied", t0)
    except _tools.SSRFGuardError as exc:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             f"SSRF 守卫拦截: {exc}", "ssrf_blocked", t0)
    except Exception as exc:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             f"抓取失败: {exc}", "fetch_error", t0)

    if final_status < 200 or final_status >= 300:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             f"抓取失败: HTTP {final_status}", "fetch_error", t0)

    raw_text = _tools._decode_body(final_ctype, body)
    raw_chars = len(raw_text)

    # ---- 3. 正文提取：trafilatura → _SimpleTextExtractor → raw 兜底 ----
    article_meta: dict = {}
    text = ""
    extractor = "raw"
    if _tools.trafilatura is not None:
        try:
            j = _tools.trafilatura.extract(raw_text, url=current_url,
                                           output_format="json", with_metadata=True,
                                           include_comments=False, favor_recall=True)
            if j:
                d = json.loads(j)
                text = d.get("text") or ""
                article_meta = d
                extractor = "trafilatura"
        except Exception as exc:
            logger.debug("[news] trafilatura 失败: %s", exc)
    if not text or len(text) < MIN_BODY_CHARS:
        # 提取失败/过短：降级链。trafilatura 结果过短时也换 _SimpleTextExtractor 再比一次，
        # 取两者较长者；两者皆失败保留 raw 原文（needsReview=true，绝不静默丢弃）。
        if _tools.trafilatura is None:
            extractor = "raw"
        fallback = _tools._SimpleTextExtractor.extract(raw_text)
        if fallback and len(fallback) > len(text):
            text = fallback
            extractor = "fallback" if extractor == "raw" else extractor
        if not text:
            text = raw_text
            extractor = "raw"
    if not text:
        return _deny_payload(url, source_hint, robots_checked, redirect_chain,
                             "未能提取到正文（可能为纯脚本页）", "extract_error", t0)
    noise_stripped = extractor in ("trafilatura", "fallback")
    if len(text) > max_chars:
        text = text[:max_chars]

    title = (article_meta.get("title") or "").strip()
    if not title:
        tm = _tools.re.search(r"<title[^>]*>(.*?)</title>", raw_text, _tools.re.S | _tools.re.I)
        title = _tools._strip_tags(tm.group(1))[:120] if tm else current_url

    # ---- 4. 指纹与判重 ----
    body_sha = body_fingerprint(text)
    title_sha = title_fingerprint(title)
    published = _iso_cn(article_meta.get("date"))
    dedup = DEDUPLICATOR.check(url=current_url, title=title, body=text,
                               published_at=published or "")
    verdict = dedup["verdict"]
    stored = stored_for(verdict)
    # 降级链（fallback/raw）与判重灰区都置 needsReview，供 Java 侧参考
    needs_review = (bool(dedup.pop("_needs_review", False))
                    or extractor in ("fallback", "raw"))
    hamming_hits = dedup.pop("_hamming_hits", None)
    dedup.pop("_body_sha", None)
    dedup.pop("_title_sha", None)
    dedup.pop("_simhash", None)
    if verdict == "needs_review":
        DEDUPLICATOR.enqueue_review(current_url, title, verdict,
                                    {"ratio": dedup.get("relatedHint")})
    degraded = extractor == "raw"

    site = urlparse(current_url).netloc or source_hint or "unknown"
    authors = article_meta.get("author") or []
    if isinstance(authors, str):
        authors = [{"name": authors}] if authors else []
    elif isinstance(authors, list):
        authors = [{"name": a} for a in authors if a]
    else:
        authors = []
    images = article_meta.get("image") or []
    if isinstance(images, str):
        images = [images] if images else []

    return {
        "article": {
            "@type": "NewsArticle",
            "headline": title,
            "url": current_url,
            "originUrl": url,
            "publishedAt": published,
            "modifiedAt": _iso_cn(article_meta.get("filedate")) if article_meta.get("filedate") else None,
            "author": authors,
            "publisher": article_meta.get("source-hostname") or site,
            "lang": article_meta.get("language") or "zh-CN",
            "image": images,
            "license": article_meta.get("license"),
            "body": text,
            "excerpt": article_meta.get("excerpt"),
            "tags": [t for t in (article_meta.get("tags") or "").split(",") if t] if isinstance(article_meta.get("tags"), str) else (article_meta.get("tags") or []),
        },
        "source": {
            "originSite": site,
            "redirectChain": redirect_chain,
        },
        "robots": {
            "checked": robots_checked,
            "allowed": all(c["allowed"] for c in robots_checked) if robots_checked else True,
        },
        "fingerprint": {
            "bodySha256": body_sha,
            "titleSha256": title_sha,
            "simhash": simhash_hex(text),
            "hammingHits": hamming_hits,
        },
        "noise": {
            "extractor": extractor,
            "noiseStripped": noise_stripped,
            "rawChars": raw_chars,
            "bodyChars": len(text),
        },
        "dedup": dedup,
        "flags": {
            "needsReview": needs_review,
            "degraded": degraded,
            "stored": stored,
        },
        "aiGateway": {
            "elapsedMs": int((time.time() - t0) * 1000),
            "degraded": degraded,
            "errors": errors,
        },
    }


class _RobotsHopDenied(Exception):
    """重定向跳进入 robots 禁止的 origin：中止抓取链（由 _guarded_get 的 on_hop 传播）。"""


def _deny_payload(url: str, source_hint: str, robots_checked: list, redirect_chain: list,
                  error: str, result: str, t0: float) -> dict:
    """robots 拒抓 / SSRF / 抓取失败的统一留痕结构：ok=true + stored=false（错误在 data 内）。

    指标计数统一在路由层按 aiGateway.errors 折算（避免双重计数），本函数不 inc。
    """
    site = urlparse(url).netloc or source_hint or "unknown"
    return {
        "article": None,
        "source": {"originSite": site, "redirectChain": redirect_chain},
        "robots": {"checked": robots_checked,
                   "allowed": bool(robots_checked and all(c["allowed"] for c in robots_checked))},
        "fingerprint": {"bodySha256": "", "titleSha256": "", "simhash": "", "hammingHits": None},
        "noise": {"extractor": "raw", "noiseStripped": False, "rawChars": 0, "bodyChars": 0},
        "dedup": {"verdict": "unique", "matchedId": None, "relatedHint": None,
                  "windowHours": int(os.environ.get("LANLINK_NEWSPIPELINE_WINDOW_HOURS", "72"))},
        "flags": {"needsReview": False, "degraded": False, "stored": False},
        "aiGateway": {"elapsedMs": int((time.time() - t0) * 1000), "degraded": True,
                      "errors": [error]},
    }


# 延迟绑定指标注册表已收敛为顶部直接导入 REGISTRY（metrics.py 无反向依赖）。
