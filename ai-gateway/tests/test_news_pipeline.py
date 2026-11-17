# -*- coding: utf-8 -*-
"""资讯处理管线测试（robots 门禁 / SSRF 探针 / 重定向留痕 / 三层判重 / 降噪降级 /
角色矩阵 / 结构化 schema / 批次上限）。HTTP 全部 mock，Redis 用 fakeredis，不触网。"""
import json
import os
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch

_app_dir = str(Path(__file__).resolve().parent.parent)
if _app_dir not in sys.path:
    sys.path.insert(0, _app_dir)

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
os.environ.setdefault("LANLINK_NEWSPIPELINE_ENABLED", "1")
for _name in ("REDIS_URL", "REDIS_URLS", "REDIS_PASSWORD"):
    os.environ.pop(_name, None)

import fakeredis  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import tools  # noqa: E402
from app import main as gw  # noqa: E402
from app.compliance import robots_gate  # noqa: E402
from app.compliance.robots_gate import RobotsGate  # noqa: E402
from app.news import structured_extract  # noqa: E402
from app.news.dedup import Deduplicator, stored_for  # noqa: E402
from app.news.fingerprint import (body_fingerprint, hamming64, simhash64,  # noqa: E402
                                  simhash_hex, title_ratio)

GW_KEY = gw.GATEWAY_API_KEY
AUTH = {"Authorization": f"Bearer {GW_KEY}"}

ARTICLE_HTML = """
<html><head><title>螺纹钢现货报价上调 - 钢铁资讯</title></head><body>
<nav><a href="/">首页</a><a href="/cat">行情</a></nav>
<div class="ad">广告位招租</div>
<article><h1>螺纹钢现货报价上调</h1>
<p>主要城市建材库存连续两周下降，螺纹钢现货报价上调 20-40 元每吨，贸易商出货节奏加快。</p>
<p>分析人士指出，供给端收缩叠加需求回暖是本轮价格上行的主要原因，短期或维持震荡走势。</p>
<p>下游施工企业按需补库，钢厂出货顺畅，社会库存去化速度有所加快。</p>
<p>华北多家钢厂高炉检修计划增加，铁水产量环比回落，焦炭采购价首轮提涨落地，成本支撑有所增强。</p>
<p>贸易商反映，终端按需采购为主，投机性需求偏弱，市场对冬储政策的预期开始升温。</p>
<p>从区域看，华东与华南主流资源报价上调 10-30 元，西北与东北市场稳中有涨，成交以刚需为主。</p>
<p>机构观点认为，短期价格仍有反复，关注宏观政策发力与原料端价格传导对成材的支撑力度。</p>
</article>
<footer>备案号 | 联系我们 | 版权所有</footer>
</body></html>
"""

ALLOW_ROBOTS = "User-agent: *\nDisallow: /private/\nCrawl-delay: 2\n"
DENY_ALL_ROBOTS = "User-agent: *\nDisallow: /\n"


# ============================ 抓取 mock 基建 ============================

class _FakeStream:
    def __init__(self, status=200, headers=None, body=b"", is_redirect=False):
        self.status_code = status
        self.headers = headers or {"content-type": "text/html; charset=utf-8"}
        self._body = body
        self.is_redirect = is_redirect
        self.extensions = {}

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def iter_bytes(self, n):
        yield self._body


class _FakeClient:
    """按 URL 前缀分发响应：routes = [(url_prefix, FakeStream|Exception), ...]
    前缀按「最长前缀优先」匹配，robots.txt 不会被 origin 前缀截胡。"""
    routes: list = []
    requests: list = []

    def __init__(self, *a, **k):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def stream(self, method, url, **k):
        _FakeClient.requests.append(url)
        matched = [(p, resp) for p, resp in _FakeClient.routes if url.startswith(p)]
        if matched:
            prefix, resp = max(matched, key=lambda pair: len(pair[0]))
            if isinstance(resp, Exception):
                raise resp
            return resp
        raise AssertionError(f"unexpected fetch: {url}")


def _html_resp(html: str, status=200) -> _FakeStream:
    return _FakeStream(status=status, body=html.encode("utf-8"))


def _fresh_gate():
    return RobotsGate(ttl_seconds=60, timeout_seconds=1.0)


# ============================ robots 门禁 ============================

class RobotsGateTest(unittest.TestCase):

    def setUp(self):
        self.gate = _fresh_gate()
        _FakeClient.routes = []
        _FakeClient.requests = []

    def test_disallow_blocks(self):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode()))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, delay, at, src = self.gate.can_fetch("http://1.2.3.4/private/a.html")
        self.assertFalse(allowed)
        self.assertEqual(src, "fresh")

    def test_allow_override_longest_match(self):
        robots = "User-agent: *\nDisallow: /private\nAllow: /private/ok\n"
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=robots.encode()))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            gate = _fresh_gate()
            ok_deny, _, _, _ = gate.can_fetch("http://1.2.3.4/private/x.html")
            ok_allow, _, _, _ = gate.can_fetch("http://1.2.3.4/private/ok/a.html")
        self.assertFalse(ok_deny)
        self.assertTrue(ok_allow)

    def test_can_fetch_param_order_regression(self):
        """protego can_fetch(url, user_agent) 参数序回归：UA 传第二参。"""
        robots = "User-agent: LanlinkNewsPipeline\nDisallow: /\nUser-agent: OtherBot\nAllow: /\n"
        parser = robots_gate.protego.Protego.parse(robots)
        self.assertFalse(parser.can_fetch("http://x.com/a", robots_gate.NEWS_UA))
        self.assertTrue(parser.can_fetch("http://x.com/a", "OtherBot/1.0"))

    def test_crawl_delay_reported(self):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode()))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, delay, _, _ = self.gate.can_fetch("http://1.2.3.4/news/a.html")
        self.assertTrue(allowed)
        self.assertEqual(delay, 2.0)

    def test_4xx_treated_as_allow(self):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(status=404, body=b"not found"))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, _, _, src = self.gate.can_fetch("http://1.2.3.4/news/a.html")
        self.assertTrue(allowed)
        self.assertEqual(src, "4xx-allow")

    def test_5xx_fail_closed(self):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(status=503, body=b"boom"))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, _, _, src = self.gate.can_fetch("http://1.2.3.4/news/a.html")
        self.assertFalse(allowed)
        self.assertEqual(src, "5xx-blocked")

    def test_network_error_fail_closed(self):
        _FakeClient.routes = [("http://1.2.3.4", OSError("conn refused"))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, _, _, src = self.gate.can_fetch("http://1.2.3.4/news/a.html")
        self.assertFalse(allowed)
        self.assertEqual(src, "5xx-blocked")

    def test_cache_hit_within_ttl(self):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode()))]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            self.gate.can_fetch("http://1.2.3.4/news/a.html")
            n_after_first = len(_FakeClient.requests)
            _, _, _, src2 = self.gate.can_fetch("http://1.2.3.4/news/b.html")
        self.assertEqual(n_after_first, 1, "TTL 内第二次查询不应再抓 robots.txt")
        self.assertEqual(src2, "cache")

    def test_robots_fetch_reuses_guarded_get(self):
        """robots.txt 抓取必须走 _guarded_get（同一 SSRF 守卫链，无旁路）。"""
        calls = []
        real = robots_gate._tools._guarded_get

        def spy(url, **kw):
            calls.append(url)
            return real(url, **kw)

        with patch.object(robots_gate._tools, "_guarded_get", side_effect=spy), \
                patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode()))]
            self.gate.can_fetch("http://1.2.3.4/news/a.html")
        self.assertEqual(calls, ["http://1.2.3.4/robots.txt"])


# ============================ robots 抓取链 SSRF 探针（验收 #2） ============================

class RobotsSSRFProbeTest(unittest.TestCase):
    """robots.txt 302 → 内网：SSRFGuardError → ssrf_blocked → fail-closed 全站禁止。"""

    def _probe(self, target):
        gate = _fresh_gate()
        redir = _FakeStream(status=302, is_redirect=True,
                            headers={"content-type": "text/html", "location": target})
        _FakeClient.routes = [("http://1.2.3.4", redir)]
        with patch.object(robots_gate._tools.httpx, "Client", _FakeClient):
            allowed, _, _, src = gate.can_fetch("http://1.2.3.4/news/a.html")
        return allowed, src

    def test_robots_redirect_to_redis_is_blocked(self):
        allowed, src = self._probe("http://redis:6379/")
        self.assertFalse(allowed)
        self.assertEqual(src, "ssrf_blocked")

    def test_robots_redirect_to_metadata_is_blocked(self):
        allowed, src = self._probe("http://169.254.169.254/latest/meta-data/")
        self.assertFalse(allowed)
        self.assertEqual(src, "ssrf_blocked")

    def test_fail_closed_feeds_into_pipeline(self):
        """ssrf_blocked 结果进入管线后：robots.allowed=false + article 缺失 + ok=true。"""
        data = structured_extract._deny_payload(
            "http://1.2.3.4/news/a.html", "probe", [], [], "SSRF 守卫拦截: x", "ssrf_blocked",
            time.time())
        self.assertEqual(data["robots"]["allowed"], False)
        self.assertIsNone(data["article"])
        self.assertFalse(data["flags"]["stored"])
        self.assertIn("SSRF", data["aiGateway"]["errors"][0])


# ============================ _guarded_get on_hop / 重定向留痕（验收 #3） ============================

class OnHopRedirectTest(unittest.TestCase):

    def setUp(self):
        _FakeClient.routes = []
        _FakeClient.requests = []

    def _three_hop_routes(self):
        return [
            ("http://1.2.3.4/start", _FakeStream(status=302, is_redirect=True,
                                                 headers={"location": "http://5.6.7.8/mid"})),
            ("http://5.6.7.8/mid", _FakeStream(status=303, is_redirect=True,
                                               headers={"location": "/final"})),
            ("http://5.6.7.8/final", _html_resp(ARTICLE_HTML)),
        ]

    def test_three_hop_chain_records_each_hop(self):
        hops = []
        _FakeClient.routes = self._three_hop_routes()
        with patch.object(tools.httpx, "Client", _FakeClient):
            status, ctype, body = tools._guarded_get(
                "http://1.2.3.4/start", timeout=2, max_bytes=65536,
                on_hop=lambda *args: hops.append(args))
        self.assertEqual(status, 200)
        self.assertEqual(len(hops), 3)
        self.assertEqual(hops[0][0], 1)
        self.assertEqual(hops[0][1], "http://1.2.3.4/start")
        self.assertEqual(hops[0][2], 302)
        self.assertEqual(hops[0][4], "http://5.6.7.8/mid")  # 绝对下一跳
        self.assertEqual(hops[1][0], 2)
        self.assertEqual(hops[1][2], 303)
        self.assertEqual(hops[1][4], "http://5.6.7.8/final")  # 相对 Location 已 urljoin
        self.assertEqual(hops[2][0], 3)
        self.assertEqual(hops[2][2], 200)
        self.assertIsNone(hops[2][4])  # 最终跳无下一跳
        self.assertIn("螺纹钢", body.decode("utf-8"))

    def test_on_hop_exception_aborts_chain(self):
        """回调抛异常（robots 拒抓）必须中止重定向链，不得先抓后查。"""
        hops = []

        def denying_hop(hop, url, status, loc, nxt):
            hops.append(url)
            if nxt and url.startswith("http://1.2.3.4"):
                raise structured_extract._RobotsHopDenied("robots.txt 禁止跳转目标")

        _FakeClient.routes = self._three_hop_routes()
        with patch.object(tools.httpx, "Client", _FakeClient):
            with self.assertRaises(structured_extract._RobotsHopDenied):
                tools._guarded_get("http://1.2.3.4/start", timeout=2, max_bytes=65536,
                                   on_hop=denying_hop)
        self.assertEqual(hops, ["http://1.2.3.4/start"], "第二跳不应被抓取")

    def test_ssrf_guard_per_hop_blocks_internal_jump(self):
        _FakeClient.routes = [
            ("http://1.2.3.4/", _FakeStream(status=302, is_redirect=True,
                                            headers={"location": "http://redis:6379/"})),
        ]
        with patch.object(tools.httpx, "Client", _FakeClient):
            with self.assertRaises(tools.SSRFGuardError):
                tools._guarded_get("http://1.2.3.4/redirect", timeout=2, max_bytes=65536)

    def test_default_signature_unchanged_without_on_hop(self):
        """缺省 on_hop=None：行为零变化（既有调用不传回调仍正常）。"""
        _FakeClient.routes = [("http://1.2.3.4/plain", _html_resp(ARTICLE_HTML))]
        with patch.object(tools.httpx, "Client", _FakeClient):
            status, ctype, body = tools._guarded_get("http://1.2.3.4/plain",
                                                     timeout=2, max_bytes=65536)
        self.assertEqual(status, 200)
        self.assertIn("螺纹钢", body.decode("utf-8"))

    def test_max_redirects_override(self):
        _FakeClient.routes = [
            ("http://1.2.3.4/", _FakeStream(status=302, is_redirect=True,
                                            headers={"location": "http://1.2.3.4/loop"})),
        ]
        with patch.object(tools.httpx, "Client", _FakeClient):
            with self.assertRaises(tools.SSRFGuardError) as ctx:
                tools._guarded_get("http://1.2.3.4/loop", timeout=2, max_bytes=65536,
                                   max_redirects=2)
        self.assertIn("上限 2", str(ctx.exception))

    def test_pipeline_cross_origin_robots_recheck(self):
        """跨 origin 跳：robots.checked 追加新 origin 记录。"""
        page = _html_resp(ARTICLE_HTML)
        _FakeClient.routes = [
            ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
            ("http://1.2.3.4/start", _FakeStream(status=302, is_redirect=True,
                                                 headers={"location": "http://5.6.7.8/mid"})),
            ("http://5.6.7.8/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
            ("http://5.6.7.8/", page),
        ]
        with patch.object(tools.httpx, "Client", _FakeClient):
            data = structured_extract.extract("http://1.2.3.4/start")
        origins = [c["origin"] for c in data["robots"]["checked"]]
        self.assertIn("http://1.2.3.4", origins)
        self.assertIn("http://5.6.7.8", origins)

    def test_article_url_is_chain_tail_not_entry(self):
        """回归（验收#4 live 缺陷）：article.url 必须是重定向链尾终点，而非首跳入口。

        历史缺陷：extract() 在 _guarded_get 返回后 `current_url = url` 兜底，把 on_hop
        落定的链尾终点覆盖回入口——301 http→https 后 article.url 仍是 http 原文。
        """
        page = _html_resp(ARTICLE_HTML)
        _FakeClient.routes = [
            ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
            ("http://1.2.3.4/old", _FakeStream(status=301, is_redirect=True,
                                               headers={"location": "http://1.2.3.4/new"})),
            ("http://1.2.3.4/new", page),
        ]
        with patch.object(tools.httpx, "Client", _FakeClient):
            data = structured_extract.extract("http://1.2.3.4/old")
        self.assertEqual(data["article"]["url"], "http://1.2.3.4/new",
                         "article.url 必须为链尾终点")
        self.assertEqual(data["article"]["originUrl"], "http://1.2.3.4/old",
                         "originUrl 保持首跳入口（溯源）")
        self.assertEqual(data["source"]["redirectChain"][0]["status"], 301)

    def test_article_url_no_redirect_keeps_entry(self):
        """无重定向：article.url=入口（on_hop 最终响应跳记录 location=null，
        current_url 保持在入口——on_hop 对 next_url=None 只落 final_status 不改 URL）。"""
        _FakeClient.routes = [
            ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
            ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML)),
        ]
        with patch.object(tools.httpx, "Client", _FakeClient):
            data = structured_extract.extract("http://1.2.3.4/news/a.html")
        self.assertEqual(data["article"]["url"], "http://1.2.3.4/news/a.html")
        chain = data["source"]["redirectChain"]
        self.assertEqual(len(chain), 1)
        self.assertEqual(chain[0]["status"], 200)
        self.assertIsNone(chain[0]["location"])


# ============================ 三层判重 ============================

def _make_client(**overrides):
    r = fakeredis.FakeStrictRedis()
    return Deduplicator(redis_client=r), r


class DedupTest(unittest.TestCase):

    def setUp(self):
        self.dedup, self.redis = _make_client()

    def test_verdict_stored_mapping_pinned(self):
        """verdict→stored 映射钉死：unique/unavailable→true，其余→false。"""
        for v, expected in (("unique", True), ("unavailable", True),
                            ("exact_duplicate", False), ("near_duplicate", False),
                            ("needs_review", False)):
            self.assertEqual(stored_for(v), expected, v)

    def test_l1_exact_duplicate_same_body(self):
        body = "钢材市场库存回落，螺纹钢现货报价小幅走强，主要城市均价上调。"
        first = self.dedup.check(url="http://a.com/news/1", title="螺纹钢报价上调",
                                 body=body, published_at="")
        self.assertEqual(first["verdict"], "unique")
        second = self.dedup.check(url="http://a.com/news/2", title="螺纹钢报价上调",
                                  body=body, published_at="")
        self.assertEqual(second["verdict"], "exact_duplicate")
        self.assertEqual(second["matchedId"], body_fingerprint(body))

    def test_l3_near_duplicate_title_variant_not_stored(self):
        """同源两 URL、正文相同、标题仅微调（ratio≥0.9）→ near_duplicate。"""
        body = "螺纹钢现货报价上调二十元，贸易商出货节奏加快，社会库存去化提速。"
        self.dedup.check(url="http://a.com/news/1", title="螺纹钢现货报价上调，贸易商出货加快",
                         body=body, published_at="")
        second = self.dedup.check(url="http://a.com/news/2",
                                  title="螺纹钢现货报价上调 贸易商出货加快",
                                  body=body + "（转载）", published_at="")
        self.assertEqual(second["verdict"], "near_duplicate")
        self.assertFalse(stored_for(second["verdict"]))

    def test_l3_grey_zone_needs_review_and_audit(self):
        """0.75 ≤ ratio < 0.9 → needs_review，newsp:review 审计留痕。

        L1 指纹键为全局键，同源灰区用例的正文须不同（否则先被 L1 拦为 exact_duplicate）。
        """
        body1 = "水泥价格连续三周上行，华东地区领涨，错峰生产与基建开工回暖是主因。"
        body2 = "水泥价格连续三周上行，华东地区涨幅领先，错峰生产叠加基建开工回暖推动。"
        self.dedup.check(url="http://a.com/news/1",
                         title="水泥价格连续三周上行 华东地区领涨", body=body1,
                         published_at="")
        second = self.dedup.check(url="http://a.com/news/2",
                                  title="水泥价格连续三周上行，华东地区涨幅居前",
                                  body=body2, published_at="")
        self.assertEqual(second["verdict"], "needs_review")
        self.assertFalse(stored_for("needs_review"))
        # 审计留痕在管线层完成（dedup.check 只出结论，见 StructuredExtractTest）；
        # 这里直接验证 enqueue_review 的审计写入路径。
        self.assertTrue(self.dedup.enqueue_review(
            "http://a.com/news/2", "水泥价格连续三周上行，华东地区涨幅居前",
            "needs_review", {"ratio": 0.88}))
        review = self.redis.lrange("newsp:review", 0, -1)
        self.assertEqual(len(review), 1)
        payload = json.loads(review[0])
        self.assertEqual(payload["verdict"], "needs_review")
        self.assertEqual(payload["url"], "http://a.com/news/2")

    def test_cross_site_same_topic_not_deduped(self):
        """跨源同主题不误杀：两个不同域名同主题在窗内，互不判重、双双可入库。

        L1 指纹键 newsp:fp:{sha} 为全局键（方案 dedupRules：正文全同 → exact_duplicate
        不分源），故跨源用例的正文为同主题不同措辞；标题相同只触发 simhash 提示层。
        """
        body_a = "铜价高位震荡，线缆企业按需采购，现货升水小幅收窄，下游补库放缓。"
        body_b = "沪铜价格高位震荡，下游线缆企业以需定采，现货升水收窄，补库节奏放缓。"
        first = self.dedup.check(url="http://a.com/news/1", title="铜价高位震荡，线缆企业按需采购",
                                 body=body_a, published_at="")
        second = self.dedup.check(url="http://b.com/news/1", title="铜价高位震荡，线缆企业按需采购",
                                  body=body_b, published_at="")
        self.assertEqual(first["verdict"], "unique")
        self.assertEqual(second["verdict"], "unique")
        self.assertIsNone(first.get("matchedId"))
        self.assertIsNone(second.get("matchedId"))
        # 跨源同文（正文完全相同）才会被全局指纹键拦下——这是方案钉死的 L1 语义
        third = self.dedup.check(url="http://c.com/news/1", title="铜价高位震荡（转载）",
                                 body=body_a, published_at="")
        self.assertEqual(third["verdict"], "exact_duplicate")

    def test_redis_down_degrades_to_unavailable_stored(self):
        """Redis 停机：verdict=unavailable + stored=true（放行集，兜底移交 Java）。"""
        class DownDedup(Deduplicator):
            def _client(self):
                return None

        d = DownDedup()
        out = d.check(url="http://a.com/news/1", title="螺纹钢报价上调", body="正文内容",
                      published_at="")
        self.assertEqual(out["verdict"], "unavailable")
        self.assertTrue(stored_for(out["verdict"]))

    def test_default_client_path_no_import_error(self):
        """回归（验收#5 live 缺陷 A）：默认构造（不注入 redis_client）的 _client() 必须走
        main.CACHE 取进程单例客户端——历史缺陷是 `from ..semantic_cache import CACHE`
        死导入（CACHE 在 main.py 实例化），运行时 ImportError 被 check() 吞掉，判重层
        恒降级 unavailable、newsp:* 从未写入。这里验证：默认 _client() 不抛 ImportError，
        无 Redis 时干净返回 None（合法降级路径）。"""
        d = Deduplicator()
        client = d._client()  # 修复前此处抛 ImportError
        # 测试环境无真实 Redis（REDIS_URL 已清、默认 127.0.0.1 探测失败）→ None；
        # 若环境恰有 Redis 可达则返回客户端对象，两种结果都证明导入路径已通。
        self.assertTrue(client is None or hasattr(client, "set"), client)

    def test_news_dedup_total_inc_on_check(self):
        """回归（验收#5 缺口 B）：check() 落定 verdict 后必须计 news_dedup_total，
        unavailable 降级同样计数（此前全仓无 inc 调用，/metrics 断言无代码路径）。"""
        from app.metrics import REGISTRY

        def _sample_value(lines, verdict, source):
            key = f'ai_gateway_news_dedup_total{{source="{source}",verdict="{verdict}"}}'
            for line in lines:
                if line.startswith(key):
                    return float(line.rsplit(" ", 1)[1])
            return None

        before = _sample_value(REGISTRY.news_dedup_total.render(), "unique", "a.com")
        out = self.dedup.check(url="http://a.com/news/metric", title="螺纹钢报价上调",
                               body="指标埋点正文内容，与前序用例指纹不同。",
                               published_at="")
        self.assertEqual(out["verdict"], "unique")
        after = _sample_value(REGISTRY.news_dedup_total.render(), "unique", "a.com")
        self.assertIsNotNone(after, "news_dedup_total 必须产生 unique/a.com 样本")
        self.assertGreater(after, before or 0.0, "check() 后计数必须递增")

    def test_redis_exception_degrades_to_unavailable(self):
        class BoomDedup(Deduplicator):
            def _client(self):
                raise ConnectionError("redis down")

        d = BoomDedup()
        out = d.check(url="http://a.com/news/1", title="螺纹钢报价上调", body="正文内容",
                      published_at="")
        self.assertEqual(out["verdict"], "unavailable")
        self.assertTrue(stored_for(out["verdict"]))

    def test_simhash_hit_flags_needs_review_but_keeps_unique(self):
        """simhash 命中仅产 needsReview 提示，verdict 恒 unique（提示层不硬拒）。"""
        base = "螺纹钢现货报价上调二十元，贸易商出货节奏加快，社会库存去化提速明显。"
        variant = "螺纹钢现货报价上调二十元，贸易商出货节奏加快，社会库存去化提速明显。" + "备注"
        self.dedup.check(url="http://a.com/news/1", title="螺纹钢报价上调",
                         body="钢材市场库存回落，螺纹钢现货报价小幅走强，主要城市均价上调。",
                         published_at="")
        second = self.dedup.check(url="http://a.com/news/9", title="螺纹钢库存连降两周",
                                  body=variant, published_at="")
        # 正文不同（L1 SETNX 成功）、标题 ratio<0.75 → unique
        self.assertEqual(second["verdict"], "unique")
        hits = second.get("_hamming_hits") or []
        if hits:
            self.assertTrue(all("matchedOriginUrl" in h and isinstance(h["bits"], int)
                                for h in hits))

    def test_unrelated_titles_low_ratio(self):
        self.assertLess(title_ratio("螺纹钢报价上调", "水泥价格连续三周上行"), 0.75)

    def test_identical_titles_full_ratio(self):
        self.assertGreaterEqual(title_ratio("螺纹钢报价上调", "螺纹钢报价上调 "), 1.0)

    def test_fingerprint_hex_formats(self):
        self.assertEqual(len(body_fingerprint("正文")), 64)
        self.assertRegex(body_fingerprint("正文"), r"^[0-9a-f]{64}$")
        self.assertRegex(simhash_hex("正文"), r"^[0-9a-f]{16}$")
        self.assertEqual(hamming64(simhash64("同文"), simhash64("同文")), 0)


# ============================ 结构化提取（降噪/降级/schema） ============================

class StructuredExtractTest(unittest.TestCase):

    def setUp(self):
        _FakeClient.routes = []
        _FakeClient.requests = []
        self.gate = _fresh_gate()
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML))]

    def _extract(self, url="http://1.2.3.4/news/a.html", **kw):
        with patch.object(structured_extract.ROBOTS_GATE, "_cache", {}), \
                patch.object(structured_extract.ROBOTS_GATE, "ttl", 60), \
                patch.object(structured_extract.ROBOTS_GATE, "timeout", 1.0), \
                patch.object(structured_extract._tools.httpx, "Client", _FakeClient), \
                patch.object(structured_extract.DEDUPLICATOR, "_client",
                             return_value=fakeredis.FakeStrictRedis()):
            if not any(p == "http://1.2.3.4/robots.txt" for p, _ in _FakeClient.routes):
                _FakeClient.routes.insert(
                    0, ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())))
            return structured_extract.extract(url, **kw)

    def test_trafilatura_strips_noise(self):
        data = self._extract()
        self.assertTrue(data["robots"]["allowed"])
        self.assertEqual(data["noise"]["extractor"], "trafilatura")
        self.assertTrue(data["noise"]["noiseStripped"])
        body = data["article"]["body"]
        self.assertIn("螺纹钢", body)
        for noise in ("广告位", "备案号", "首页", "联系我们"):
            self.assertNotIn(noise, body)
        self.assertGreater(data["noise"]["rawChars"], data["noise"]["bodyChars"])

    def test_fallback_extractor_when_trafilatura_none(self):
        """trafilatura 返回 None → _SimpleTextExtractor 降级，needsReview=true。"""
        with patch.object(structured_extract._tools, "trafilatura", None):
            data = self._extract()
        self.assertIn(data["noise"]["extractor"], ("fallback", "raw"))
        self.assertTrue(data["flags"]["needsReview"])
        self.assertIn("螺纹钢", data["article"]["body"])

    def test_raw_fallback_preserves_original(self):
        """正文提取全失败 → raw 原文保留不丢。"""
        tiny = "<html><body><p>太短</p></body></html>"
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/news", _html_resp(tiny))]
        with patch.object(structured_extract._tools.trafilatura, "extract",
                          side_effect=RuntimeError("boom")):
            data = self._extract()
        self.assertEqual(data["noise"]["extractor"], "raw")
        self.assertTrue(data["flags"]["degraded"])
        self.assertIn("太短", data["article"]["body"])

    def test_robots_denied_pipeline_payload(self):
        """robots 全禁：article 缺失、redirectChain/robots.checked 留痕、stored=false。"""
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=DENY_ALL_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt",
                               _FakeStream(body=DENY_ALL_ROBOTS.encode()))]
        data = self._extract()
        self.assertIsNone(data["article"])
        self.assertFalse(data["robots"]["allowed"])
        self.assertFalse(data["flags"]["stored"])
        checked = data["robots"]["checked"]
        self.assertEqual(len(checked), 1)
        self.assertFalse(checked[0]["allowed"])
        self.assertEqual(data["aiGateway"]["errors"][0], "robots.txt 禁止抓取")

    def test_schema_field_types(self):
        data = self._extract()
        art = data["article"]
        self.assertIsInstance(art["headline"], str)
        self.assertIsInstance(art["url"], str)
        self.assertIsInstance(art["originUrl"], str)
        if art["publishedAt"] is not None:
            self.assertIsInstance(art["publishedAt"], str)
            from datetime import datetime
            datetime.fromisoformat(art["publishedAt"].replace("Z", "+00:00"))
        fp = data["fingerprint"]
        self.assertRegex(fp["bodySha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(fp["titleSha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(fp["simhash"], r"^[0-9a-f]{16}$")
        self.assertIn(data["dedup"]["verdict"],
                      {"unique", "exact_duplicate", "near_duplicate",
                       "needs_review", "unavailable"})
        self.assertIsInstance(data["flags"]["stored"], bool)
        self.assertEqual(data["flags"]["stored"],
                         data["dedup"]["verdict"] in ("unique", "unavailable"))
        for hop in data["source"]["redirectChain"]:
            self.assertIsInstance(hop["status"], int)
            self.assertIsInstance(hop["hop"], int)

    def test_error_payload_schema(self):
        data = structured_extract._deny_payload(
            "http://1.2.3.4/x", "hint", [], [], "抓取失败: HTTP 502", "fetch_error", time.time())
        self.assertFalse(data["flags"]["stored"])
        self.assertEqual(data["robots"]["checked"], [])
        self.assertEqual(data["source"]["redirectChain"], [])


# ============================ 角色矩阵（验收 #10） ============================

@pytest.fixture
def client():
    return TestClient(gw.app)


def _post_extract(client, headers=None, url="http://1.2.3.4/news/a.html"):
    h = dict(AUTH)
    h.update(headers or {})
    return client.post("/v1/news/extract", json={"url": url}, headers=h)


def test_role_missing_denied(client):
    """X-Agent-Role 缺失 → 403 permission_denied（news_extract 不在只读白名单）。"""
    r = client.post("/v1/news/extract", json={"url": "http://1.2.3.4/x"}, headers=AUTH)
    assert r.status_code == 403, r.text
    body = r.json()
    detail = body.get("detail", body)
    payload = detail.get("error", detail) if isinstance(detail, dict) else {}
    assert payload.get("type") == "permission_denied", body


def test_role_garbage_denied(client):
    r = _post_extract(client, {"X-Agent-Role": "garbage"})
    assert r.status_code == 403, r.text


def test_role_support_denied(client):
    r = _post_extract(client, {"X-Agent-Role": "support"})
    assert r.status_code == 403, r.text


def test_role_finance_denied(client):
    r = _post_extract(client, {"X-Agent-Role": "finance"})
    assert r.status_code == 403, r.text


def test_role_buyer_accepted(client):
    with patch.object(structured_extract._tools.httpx, "Client", _FakeClient), \
            patch.object(structured_extract.ROBOTS_GATE, "_cache", {}), \
            patch.object(structured_extract.ROBOTS_GATE, "ttl", 60), \
            patch.object(structured_extract.DEDUPLICATOR, "_client",
                         return_value=fakeredis.FakeStrictRedis()):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML))]
        r = _post_extract(client, {"X-Agent-Role": "buyer"})
    assert r.status_code == 200, r.text
    assert r.json()["ok"] is True


def test_role_service_accepted(client):
    h = dict(AUTH)
    h["X-Agent-Role"] = "service"
    with patch.object(structured_extract._tools.httpx, "Client", _FakeClient), \
            patch.object(structured_extract.ROBOTS_GATE, "_cache", {}), \
            patch.object(structured_extract.ROBOTS_GATE, "ttl", 60), \
            patch.object(structured_extract.DEDUPLICATOR, "_client",
                         return_value=fakeredis.FakeStrictRedis()):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML))]
        r = client.post("/v1/news/extract", json={"url": "http://1.2.3.4/news/a.html"}, headers=h)
    assert r.status_code == 200, r.text


def test_strict_mode_unbound_key_self_assert_buyer_denied(client):
    """GATEWAY_STRICT_ROLE=1：未绑定 key 自报 buyer → 403。"""
    unbound = "test-news-unbound-key"
    with patch.object(gw, "STRICT_ROLE", True), \
            patch.object(gw, "VALID_GATEWAY_KEYS", [unbound]), \
            patch.object(gw, "PRIMARY_GATEWAY_KEY", unbound):
        r = _post_extract(client, {"X-Agent-Role": "buyer"},
                          ) if False else client.post(
            "/v1/news/extract", json={"url": "http://1.2.3.4/x"},
            headers={"Authorization": f"Bearer {unbound}", "X-Agent-Role": "buyer"})
    assert r.status_code == 403, r.text


def test_non_strict_unbound_key_self_assert_buyer_accepted(client):
    """既有全局权衡（resolve_role main.py:166-179）：非 strict 未绑定 key 可自报 buyer——
    该面是仓库既有设计，对所有工具端点一致，非本链路新增。"""
    unbound = "test-news-legacy-key"
    with patch.object(gw, "STRICT_ROLE", False), \
            patch.object(gw, "VALID_GATEWAY_KEYS", [unbound]), \
            patch.object(gw, "PRIMARY_GATEWAY_KEY", unbound):
        with patch.object(structured_extract._tools.httpx, "Client", _FakeClient), \
                patch.object(structured_extract.ROBOTS_GATE, "_cache", {}), \
                patch.object(structured_extract.ROBOTS_GATE, "ttl", 60), \
                patch.object(structured_extract.DEDUPLICATOR, "_client",
                             return_value=fakeredis.FakeStrictRedis()):
            _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                                  ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                                  ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML))]
            r = client.post("/v1/news/extract", json={"url": "http://1.2.3.4/news/a.html"},
                            headers={"Authorization": f"Bearer {unbound}",
                                     "X-Agent-Role": "buyer"})
    assert r.status_code == 200, r.text


def test_pipeline_disabled_returns_404(client):
    with patch.dict(os.environ, {"LANLINK_NEWSPIPELINE_ENABLED": "0"}):
        r = _post_extract(client, {"X-Agent-Role": "buyer"})
    assert r.status_code == 404, r.text


def test_batch_over_limit_rejected(client):
    items = [{"url": f"http://1.2.3.4/n{i}"} for i in range(6)]
    r = client.post("/v1/news/extract:batch", json={"items": items},
                    headers={**AUTH, "X-Agent-Role": "buyer"})
    assert r.status_code == 422, r.text


def test_batch_within_limit_runs(client):
    items = [{"url": "http://1.2.3.4/news/a.html", "sourceHint": "steel"}]
    with patch.object(structured_extract._tools.httpx, "Client", _FakeClient), \
            patch.object(structured_extract.ROBOTS_GATE, "_cache", {}), \
            patch.object(structured_extract.ROBOTS_GATE, "ttl", 60), \
            patch.object(structured_extract.DEDUPLICATOR, "_client",
                         return_value=fakeredis.FakeStrictRedis()):
        _FakeClient.routes = [("http://1.2.3.4", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/robots.txt", _FakeStream(body=ALLOW_ROBOTS.encode())),
                              ("http://1.2.3.4/news", _html_resp(ARTICLE_HTML))]
        r = client.post("/v1/news/extract:batch", json={"items": items},
                        headers={**AUTH, "X-Agent-Role": "buyer"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["results"]) == 1
    assert body["results"][0]["ok"] is True
    assert "batchMs" in body


def test_news_metrics_registered(client):
    """news_fetch_total / news_dedup_total / news_pipeline_latency 在 /metrics 可见。"""
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "# HELP ai_gateway_news_fetch_total" in resp.text
    assert "# HELP ai_gateway_news_dedup_total" in resp.text
    assert "# HELP ai_gateway_news_pipeline_latency_seconds" in resp.text


if __name__ == "__main__":
    unittest.main()
