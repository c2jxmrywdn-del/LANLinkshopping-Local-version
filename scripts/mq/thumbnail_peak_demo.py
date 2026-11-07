#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""缩略图异步链路削峰观测试图（S5 量化指标）。

做什么
  1. 以真实登录会话上传 N 张 PNG，记录每次上传的耗时分布——上传线程不再做解码与缩放；
  2. 同时轮询 RabbitMQ Management API，记录 q.attachment.process 的积压峰值、消费者数与排空时间；
  3. 从后端 /api/actuator/prometheus 读 lanlink_attachment_thumbnail_* 指标，得到吞吐、
     结果分布（ready/skipped/duplicate/infra/poison…）与消费耗时 P50/P95；
  4. 记录 redelivered 与 DLQ 深度，作为重投与死信的证据；
  5. 结束时删除本次上传的材料（含缩略图对象），桶与库都不留痕。

凭据一律走参数或环境变量，脚本不落盘、不打印口令。

用法
  python scripts/mq/thumbnail_peak_demo.py --phone 13800000001 --password 'xxx' --files 12
  可选：--backend http://127.0.0.1:8080/api  --rmq http://127.0.0.1:15672（Management API 的根，不带 /api）
        --rmq-user guest --rmq-pass guest --vhost / --queue q.attachment.process
        --out evidence.json --edge 900

注意（不是缺陷，是既有约束）
  附件上传有 10 次/60 秒的登录侧限流与 nginx 60 次/60 秒的入口限流，所以这里的积压峰值是
  "个位到十位"量级：生产上限 10 条/分，远低于消费能力，正常态下队列根本堆不起来。
  想看真正的积压，需要把生产端解限或让消费者停摆；但 RabbitMQ 4.1 的 rabbitmqctl 既没有
  pause_queue 也没有 resume_queue（实测报 "Did you mean 'purge_queue'?"），
  rabbit:pause_queue/2 也不能经 rabbitmqctl eval 调到，所以本脚本不再承诺这条路径。
  在本地环境里可复现的替代做法：把 app.mq.attachment-consumer-concurrency 设为 0-0 重启后端
  （生产继续、无人消费），观察 ready 上涨，再改回默认值重启排空。
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import statistics
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from http.cookiejar import CookieJar


def png_bytes(edge: int) -> bytes:
    """生成一张无压缩滤镜的真 PNG（IHDR+IDAT），用于走通魔数与解码。"""
    raw = b"".join(b"\x00" + bytes([(x * 7) % 256 for x in range(edge)]) for _ in range(edge))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", edge, edge, 8, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


class Client:
    def __init__(self, backend: str):
        self.backend = backend.rstrip("/")
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, path, method="GET", body=None, headers=None, raw_body=None, content_type=None):
        url = self.backend + path
        data = raw_body if raw_body is not None else (
            json.dumps(body).encode("utf-8") if body is not None else None)
        req = urllib.request.Request(url, data=data, method=method)
        if content_type:
            req.add_header("Content-Type", content_type)
        elif data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self.opener.open(req, timeout=120) as resp:
                payload = resp.read()
                return resp.status, payload
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def login(self, phone: str, password: str) -> None:
        status, payload = self.call("/auth/login", "POST", {"phone": phone, "password": password})
        body = _json(payload)
        if status != 200 or (body or {}).get("code") != 200:
            raise SystemExit("登录失败 HTTP=%s body=%s" % (status, str(body)[:200]))
        if (body.get("data") or {}).get("require2fa"):
            raise SystemExit("该账号启用了两步验证，请换未开启 2FA 的演示账号")
        status, payload = self.call("/user/csrf-token")
        body = _json(payload) or {}
        self.csrf = str((body.get("data") or {}).get("token")
                        or (body.get("data") or {}).get("csrfToken") or "")
        if not self.csrf:
            raise SystemExit("未取得 CSRF 令牌，无法上传（响应=%s）" % str(body)[:200])

    def upload(self, edge: int) -> tuple[str, float]:
        no = uuid.uuid4().hex[:12] + ".png"
        boundary = "----lanlink" + uuid.uuid4().hex
        parts = io.BytesIO()
        # bizType 只走查询参数，与前端 attachmentApi.upload 一致：
        # 表单里再放一份会被 Spring 拼成 "merchant-license,merchant-license"，
        # AttachmentBizType.fromWireName 严格匹配线名，直接 400 不支持的业务域。
        parts.write(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                     "Content-Type: image/png\r\n\r\n" % (boundary, no)).encode())
        parts.write(png_bytes(edge))
        parts.write(("\r\n--%s--\r\n" % boundary).encode())
        started = time.perf_counter()
        status, payload = self.call(
            "/attachment/upload?bizType=merchant-license", "POST",
            raw_body=parts.getvalue(),
            content_type="multipart/form-data; boundary=" + boundary,
            headers={"X-CSRF-TOKEN": self.csrf})
        elapsed = time.perf_counter() - started
        body = _json(payload) or {}
        if status != 200 or body.get("code") != 200:
            raise SystemExit("上传失败 HTTP=%s body=%s" % (status, str(body)[:200]))
        return str(body["data"]["no"]), elapsed

    def delete(self, attachment_no: str) -> None:
        self.call("/attachment/" + attachment_no, "DELETE", headers={"X-CSRF-TOKEN": self.csrf})


def _json(payload: bytes):
    try:
        return json.loads(payload.decode("utf-8"))
    except Exception:
        return None


def mgmt(base: str, user: str, password: str, path: str):
    req = urllib.request.Request(base.rstrip("/") + path)
    import base64
    token = base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
    # Management API 只认 Basic：用 Bearer 会被 401，mgmt() 吞异常后所有队列读数都是 0，
    # 看起来像"没有积压"而不是"没连上"，削峰证据会假绿。
    req.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None


def queue_detail(base: str, user: str, password: str, vhost: str, queue: str) -> dict:
    """按队列名取单条详情：只有这个端点返回权威的 message_stats。

    /api/queues（列表）里的 message_stats 会滞后——实测连投 9 条后列表仍报 delivered=1，
    而 /api/queues/%2F/q.attachment.process 报 deliver_get=19。用列表取投递/重投计数，
    会把「零重投」这种关键证据读成不变，所以这里单独取一次。
    """
    vhost_q = urllib.parse.quote(vhost, safe="")
    row = mgmt(base, user, password, "/api/queues/%s/%s" % (vhost_q, urllib.parse.quote(queue, safe="")))
    ms = (row or {}).get("message_stats") or {}
    return {
        "delivered": int(ms.get("deliver_get") or 0),
        "redelivered": int(ms.get("redeliver") or 0),
        "published": int(ms.get("publish") or 0),
        "acked": int(ms.get("ack") or 0),
        "node": (row or {}).get("node"),
    }


def queue_state(base: str, user: str, password: str, vhost: str, queue: str) -> dict:
    """跨节点求和：quorum 队列在三个节点上各有一份统计，取名字匹配的所有条目。"""
    vhost_q = urllib.parse.quote(vhost, safe="")
    rows = mgmt(base, user, password, "/api/queues") or []
    mine = [r for r in rows if r.get("name") == queue]
    state = {
        "ready": sum(int(r.get("messages_ready") or r.get("messages") or 0) for r in mine),
        "total": sum(int(r.get("messages") or 0) for r in mine),
        "consumers": sum(int(r.get("consumers") or 0) for r in mine),
        "nodes": len(mine),
    }
    state.update(queue_detail(base, user, password, vhost, queue))
    return state


def dlq_depth(base: str, user: str, password: str, vhost: str, queue: str) -> int:
    return int(queue_state(base, user, password, vhost, queue).get("total", 0))


METRIC_RE = re.compile(r"^(?P<name>[a-zA-Z_:]+)(?P<labels>\{[^}]*\})?\s+(?P<value>[-0-9.eE+]+)$")


def scrape_metrics(backend: str) -> dict:
    req = urllib.request.Request(backend.rstrip("/") + "/actuator/prometheus")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", "replace")
    except Exception as e:
        print("! 指标抓取失败：%s" % e)
        return {}
    out: dict[str, object] = {"counter": {}, "summary": {}, "hist": {}}
    for line in text.splitlines():
        m = METRIC_RE.match(line.strip())
        if not m:
            continue
        name, labels, value = m.group("name"), m.group("labels") or "", float(m.group("value"))
        if name.startswith("lanlink_attachment_thumbnail_processed_total"):
            outcome = re.search(r'outcome="([^"]*)"', labels)
            key = outcome.group(1) if outcome else "unknown"
            out["counter"][key] = out["counter"].get(key, 0.0) + value
        elif name.startswith("lanlink_attachment_thumbnail_duration"):
            if name.endswith("_count"):
                out["summary"]["count"] = value
            elif name.endswith("_sum"):
                out["summary"]["sum"] = value
            elif name.endswith("_bucket"):
                le = re.search(r'le="([^"]*)"', labels)
                try:
                    out["hist"][float(le.group(1))] = value
                except (TypeError, ValueError):
                    pass
    return out


def percentile(hist: dict, q: float):
    if not hist:
        return None
    total = max(hist.values())
    buckets = sorted((le, c) for le, c in hist.items() if le != float("inf"))
    if not buckets:
        return None
    target = total * q
    for le, c in buckets:
        if c >= target:
            return le
    return buckets[-1][0]


def quantiles(values: list[float]) -> dict:
    if not values:
        return {}
    ordered = sorted(values)
    return {"n": len(ordered),
            "p50": round(ordered[int(len(ordered) * 0.5)] * 1000),
            "p95": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))] * 1000),
            "max": round(ordered[-1] * 1000)}


def main() -> int:
    ap = argparse.ArgumentParser(description="缩略图异步链路削峰观测试图")
    ap.add_argument("--backend", default=os.environ.get("LANLINK_BACKEND", "http://127.0.0.1:8080/api"))
    # 这里必须是 Management API 的根，不带 /api：下面所有请求都自带 /api/ 前缀，
    # 写成 .../api 会拼成 /api/api/queues → 404 → mgmt() 吞异常返回 None → 所有读数变 0。
    ap.add_argument("--rmq", default=os.environ.get("RMQ_MGMT", "http://127.0.0.1:15672"))
    ap.add_argument("--rmq-user", default=os.environ.get("RABBITMQ_USER", "guest"))
    ap.add_argument("--rmq-pass", default=os.environ.get("RABBITMQ_PASSWORD", ""))
    ap.add_argument("--phone", default=os.environ.get("DEMO_PHONE", ""))
    ap.add_argument("--password", default=os.environ.get("DEMO_PASSWORD", ""))
    ap.add_argument("--files", type=int, default=12)
    ap.add_argument("--edge", type=int, default=900, help="生成图片的边长像素")
    ap.add_argument("--vhost", default="/")
    ap.add_argument("--queue", default="q.attachment.process")
    ap.add_argument("--dlq", default="q.attachment.dlq")
    ap.add_argument("--settle", type=int, default=240, help="等待排空的最长秒数")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    if not args.phone or not args.password:
        print("! 需要 --phone/--password（或 DEMO_PHONE/DEMO_PASSWORD），脚本不内置任何账号")
        return 2

    client = Client(args.backend)
    client.login(args.phone, args.password)

    # Management API 连不上时 mgmt() 返回 None，队列读数会全变成 0——那看起来像"没有积压"，
    # 实际是没连上。先打通 /api/overview，把假绿拦在跑测试之前。
    if mgmt(args.rmq, args.rmq_user, args.rmq_pass, "/api/overview") is None:
        print("! RabbitMQ Management API 不可达或未授权：%s（用户 %s）"
              % (args.rmq, args.rmq_user or "(空)"))
        return 3

    before = scrape_metrics(args.backend)
    q0 = queue_state(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.queue)
    if q0["nodes"] == 0:
        print("! 队列 %s 在所有节点上都不存在，缩略图链路未以 MQ 模式启动" % args.queue)
        return 3

    uploaded: list[str] = []
    latencies: list[float] = []
    samples: list[dict] = []
    peak = q0["ready"]
    stop = False

    def sampler():
        while not stop:
            s = queue_state(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.queue)
            samples.append({"t": round(time.time() - started, 1), "ready": s["ready"],
                            "consumers": s["consumers"]})
            time.sleep(1.0)

    import threading
    started = time.time()
    sampler_thread = threading.Thread(target=sampler, daemon=True)
    sampler_thread.start()
    try:
        for i in range(args.files):
            no, elapsed = client.upload(args.edge)
            uploaded.append(no)
            latencies.append(elapsed)
            s = queue_state(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.queue)
            peak = max(peak, s["ready"])
            print("  上传 %2d/%d  %s ms  队列=%d 消费者=%d"
                  % (i + 1, args.files, round(elapsed * 1000), s["ready"], s["consumers"]))
        drained_at = None
        deadline = time.time() + args.settle
        while time.time() < deadline:
            s = queue_state(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.queue)
            if s["ready"] == 0:
                drained_at = time.time() - started
                break
            time.sleep(2)
    finally:
        stop = True
        sampler_thread.join(timeout=3)

    after = scrape_metrics(args.backend)
    q1 = queue_state(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.queue)
    dlq = dlq_depth(args.rmq, args.rmq_user, args.rmq_pass, args.vhost, args.dlq)

    bc, ac = (before.get("counter") or {}), (after.get("counter") or {})
    deltas = {k: round((ac.get(k, 0.0) - bc.get(k, 0.0)), 3)
              for k in sorted(set(bc) | set(ac)) if ac.get(k, 0.0) - bc.get(k, 0.0)}
    bs, asu = (before.get("summary") or {}), (after.get("summary") or {})
    n_count = (asu.get("count", 0) or 0) - (bs.get("count", 0) or 0)
    n_sum = (asu.get("sum", 0) or 0) - (bs.get("sum", 0) or 0)
    elapsed_total = (drained_at or (time.time() - started))
    report = {
        "files": len(uploaded),
        "upload_latency_ms": quantiles(latencies),
        "queue": {"before": q0, "after": q1, "peak_ready": peak,
                  "drain_seconds": round(elapsed_total, 1) if drained_at else None,
                  "redelivered_delta": q1["redelivered"] - q0["redelivered"],
                  "delivered_delta": q1["delivered"] - q0["delivered"],
                  "dlq_ready": dlq},
        "consumer_outcomes": deltas,
        "consume_ms": {"count": n_count, "avg": round(n_sum * 1000 / n_count, 1) if n_count else None,
                       "p50_bucket": percentile(after.get("hist") or {}, 0.5),
                       "p95_bucket": percentile(after.get("hist") or {}, 0.95)},
        "throughput_per_min": round(n_count * 60 / elapsed_total, 2) if elapsed_total else None,
        "samples_ready": samples,
    }
    print(json.dumps({k: v for k, v in report.items() if k != "samples_ready"},
                     ensure_ascii=False, indent=2))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
        print("证据已写入 %s" % args.out)

    for no in uploaded:
        client.delete(no)
    print("已清理本次上传的 %d 份材料（含缩略图对象）" % len(uploaded))
    return 0


if __name__ == "__main__":
    sys.exit(main())
