# -*- coding: utf-8 -*-
"""robots.txt 门禁（RFC 9309 子集）：资讯管线抓取前的站点级许可检查。

设计要点：
- robots.txt 抓取**复用 tools._guarded_get**：与其自身的重定向（含跨 origin，
  RFC 9309 §2.2.2 要求至少跟 5 跳）都过同一套 _ssrf_guard + 对端 IP 复核守卫链，
  不新增任何 SSRF 面；302 跳 redis:6379 / 169.254.169.254 在对应跳被拦后按
  fail-closed 全站禁止处理（source="ssrf_blocked" 留痕）。
- protego 解析：Allow 长匹配覆盖 Disallow、通配符、Crawl-delay 均按其实现语义。
- 缓存 TTL 默认 24h（RFC 9309 上限）；robots.txt 4xx → 允许；5xx/网络错 →
  全站禁止（短 TTL 后可重试，避免对端偶发 5xx 把整站停抓一整天）。
"""
import logging
import os
import time
from urllib.parse import urljoin, urlparse

from .. import tools as _tools  # robots 抓取复用 _guarded_get（同一 SSRF 守卫链）

logger = logging.getLogger("lanlink.ai-gateway")

try:
    import protego  # robots.txt 解析（requirements 已装；缺失时 fail-closed）
except Exception:  # pragma: no cover
    protego = None

#: 出站 UA（RFC 9309：product token SHOULD 是 HTTP UA 的子串，两者同源）
NEWS_UA = os.environ.get("LANLINK_NEWSPIPELINE_UA",
                         "LanlinkNewsPipeline/1.0 (+https://www.lanlink.shopping/contact)").strip()
#: robots.txt 缓存 TTL 秒（RFC 9309 上限 24h）
ROBOTS_TTL_SECONDS = int(os.environ.get("LANLINK_NEWSPIPELINE_ROBOTS_TTL_SECONDS", "86400"))
#: robots.txt 抓取超时（传入 _guarded_get 的 timeout 参数）
ROBOTS_TIMEOUT_SECONDS = float(os.environ.get("LANLINK_NEWSPIPELINE_ROBOTS_TIMEOUT_MS", "5000")) / 1000.0
#: robots.txt 响应字节上限（RFC 9309 §2.4 解析上限 500KiB，超出部分截断解析）
ROBOTS_MAX_BYTES = 500 * 1024
#: 5xx/网络错误的全站禁止缓存 TTL（RFC 9309 §2.3.1.4 允许 unreachable 短缓存后重试）
BLOCKED_TTL_SECONDS = 600


class RobotsGate:
    """按 origin 缓存的 robots.txt 检查器（进程内单例，线程安全靠 GIL 字典操作）。"""

    def __init__(self, ttl_seconds: int | None = None, timeout_seconds: float | None = None):
        self.ttl = ttl_seconds if ttl_seconds is not None else ROBOTS_TTL_SECONDS
        self.timeout = timeout_seconds if timeout_seconds is not None else ROBOTS_TIMEOUT_SECONDS
        # origin -> (expiry_epoch, parser|None, source)；parser=None 表示全站禁止
        self._cache: dict[str, tuple[float, object | None, str]] = {}

    # ---- 缓存键与 robots.txt 地址 ----

    @staticmethod
    def origin_of(url: str) -> str:
        u = urlparse(url)
        return f"{u.scheme.lower()}://{u.netloc.lower()}"

    @staticmethod
    def robots_url_for(url: str) -> str:
        return urljoin(RobotsGate.origin_of(url) + "/", "/robots.txt")

    # ---- 抓取与解析 ----

    def _fetch_parser(self, robots_url: str) -> tuple[object | None, str]:
        """抓取并解析 robots.txt。

        返回 (parser|None, source)：
          parser=None + source="5xx-blocked"  → 网络错/5xx，fail-closed（短 TTL）；
          parser=None + source="ssrf_blocked" → robots 抓取链命中 SSRF 守卫，fail-closed；
          parser=None + source="parse-error"  → 未装 protego 等解析失败，fail-closed；
          parser 对象 + source="fresh"        → 正常解析（含 4xx 视为允许的空规则）。
        抛 SSRFGuardError 前，由调用方捕获并转成 fail-closed 结果——本函数内部捕获，
        保证「robots 检查本身」绝不向管线抛异常。
        """
        try:
            status, _ctype, body = _tools._guarded_get(
                robots_url, timeout=self.timeout, max_bytes=ROBOTS_MAX_BYTES,
                headers={"User-Agent": NEWS_UA})
        except _tools.SSRFGuardError as exc:
            logger.warning("[robots] %s 抓取命中 SSRF 守卫（fail-closed）: %s", robots_url, exc)
            return None, "ssrf_blocked"
        except Exception as exc:
            logger.warning("[robots] %s 抓取失败（fail-closed）: %s", robots_url, exc)
            return None, "5xx-blocked"
        if status < 200 or status >= 300:
            # RFC 9309 §2.3.1.2：4xx（含 404）视为无限制；5xx 视为全站禁止
            if 400 <= status < 500:
                return None, "4xx-allow"
            return None, "5xx-blocked"
        if protego is None:
            logger.warning("[robots] protego 未安装，fail-closed: %s", robots_url)
            return None, "parse-error"
        try:
            parser = protego.Protego.parse(_tools._decode_body("text/plain; charset=utf-8", body))
            return parser, "fresh"
        except Exception as exc:
            logger.warning("[robots] %s 解析失败（fail-closed）: %s", robots_url, exc)
            return None, "parse-error"

    def _lookup(self, url: str) -> tuple[bool, float | None, str, str]:
        """返回 (allowed, crawl_delay, checked_at_iso, source)；cache hit 时 source="cache"。"""
        origin = self.origin_of(url)
        now = time.time()
        hit = self._cache.get(origin)
        if hit and hit[0] > now:
            expiry, parser, source = hit
            delay = None
            if parser is not None:
                try:
                    delay = parser.crawl_delay(NEWS_UA)
                except Exception:
                    delay = None
            return self._can_fetch(parser, url, source), delay, _iso(now), "cache"
        parser, source = self._fetch_parser(self.robots_url_for(url))
        ttl = self.ttl if parser is not None or source == "4xx-allow" else BLOCKED_TTL_SECONDS
        # 4xx-allow 也缓存（parser=None 但语义是允许）：用 source 区分语义，_can_fetch 放行
        self._cache[origin] = (now + ttl, parser, source)
        delay = None
        if parser is not None:
            try:
                delay = parser.crawl_delay(NEWS_UA)
            except Exception:
                delay = None
        return self._can_fetch(parser, url, source), delay, _iso(now), source

    @staticmethod
    def _can_fetch(parser: object | None, url: str, source: str = "fresh") -> bool:
        if parser is not None:
            try:
                return bool(parser.can_fetch(url, NEWS_UA))
            except Exception:
                return False
        # robots.txt 4xx（含 404）视为无限制（RFC 9309 §2.3.1.2）；其余 None 语义均为禁止
        return source == "4xx-allow"

    def can_fetch(self, url: str) -> tuple[bool, float | None, str, str]:
        """查 (allowed, crawlDelaySec, checkedAt, source)。

        source: "cache" | "fresh" | "4xx-allow" | "5xx-blocked" | "ssrf_blocked" | "parse-error"。
        crawl_delay > 0 时调用方应跳过该源当轮其余页（本模块只负责上报）。
        """
        try:
            return self._lookup(url)
        except Exception as exc:  # 兜底：检查器自身异常不得炸抓取链
            logger.warning("[robots] can_fetch 异常（fail-closed）: %s", exc)
            return False, None, _iso(time.time()), "parse-error"


def _iso(epoch: float) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat(timespec="seconds")


#: 进程级单例（TTL 缓存只在本进程内有效；多 worker 部署各自缓存，行为一致）
GATE = RobotsGate()


def robots_check(url: str) -> tuple[bool, float | None, str, str]:
    """模块级便捷入口：GATE.can_fetch(url)。"""
    return GATE.can_fetch(url)
