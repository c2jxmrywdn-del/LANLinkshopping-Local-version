#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RFQ S5 buyer-accepts-quote probe (one-shot, self-cleaning, live stack).

Proves what the S5 "accept a quote -> order" slice actually does on the running
API, and how its guard rails fire:

  a) read-only baseline: t_rfq / t_rfq_quote / t_rfq_event / t_order row
     counts, the demo merchant's mer_id and stock, and one sellable product of
     that merchant with plenty of stock that is NOT listed in an active
     product-scoped campaign (no promo interference).
  b) main chain: buyer publishes an RFQ (quantity=20, deadline +3d) -> merchant
     quotes 98.50 (server total 98.50 x 20 = 1970.00) -> buyer GET
     /rfq/{rfqNo}/quotes sees that quote -> buyer POST
     /rfq/quotes/{quoteId}/accept (expectedTotal=1970.00) -> body code 200.
  c) DB assertions: t_order.total_amount=1970.00 with promo/coupon/point
     discounts all zero, rfq_no + rfq_quote_id wired; t_order_item price=98.50,
     quantity=20, subtotal=1970.00; stock = baseline - 20; quote ACCEPTED; RFQ
     ORDERED; exactly one t_rfq_event(ACCEPT) row.
  d) replay: the same accept again returns the same orderNo, t_order does not
     grow and stock is not deducted twice.
  e) concurrency: a second RFQ + quote, then two buyer sessions fire accept on
     the same quoteId behind a Barrier. Exactly one order, one stock deduction.
     Accepted outcomes: one 200 + one non-200, or two 200 with the same orderNo.
  f) anti-tamper / boundary (third RFQ + quote):
       - expectedTotal=1 -> failure whose message contains "报价总额不一致",
         no order;
       - quote.valid_until moved into the past -> body code 400 mentioning
         "有效期", no order (then restored);
       - couponId / usePoints fed to the accept body -> failure, no order
         (recorded honestly: the accept DTO has no such fields).
  g) merchant visibility: merchant GET /order/merchant/list returns the deal
     with total_amount=1970.00 (hard check of the t_order JOIN t_product
     ownership rule).
  h) cleanup: POST /order/cancel for every order this run created (stock comes
     back, order row kept by the server), then SQL-delete ONLY this run's rows
     from t_order / t_order_item / t_message / t_user_risk_event /
     t_tamper_alert / t_idempotent_token(rfq-accept) / t_rfq_event /
     t_rfq_quote / t_rfq, and verify the four tables and the product stock are
     back to baseline. t_sys_sequence is deliberately kept.

Rate limits: POST /rfq, /rfq/* and /merchant/rfq/* are throttled at 10 req /
60 s per client IP (AuthRateLimitInterceptor). This probe issues well over ten
such writes, so every write goes through a 429-tolerant helper that waits out
the current minute window and retries. A fresh window is forced before the
barrier so the two concurrent accepts really race.

Credentials are arguments and are never printed. DB access runs through
`docker exec` using the container's own environment variables, so no password
appears in this script, on the command line, or in the output.

Usage:
  python scripts/rfq/rfq_accept_probe.py \\
      --buyer-phone 13900000001 --buyer-password 'xxx' \\
      --merchant-phone 13700000002 --merchant-password 'xxx' \\
      [--backend http://127.0.0.1:18181/api] [--mysql-container shopping-mysql-1] \\
      [--keep] [--out reports/rfq-accept-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure,
2 precondition missing (accounts/merchant/product), 3 docker or DB unreachable.

Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the
UTF-8 evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation
from http.cookiejar import CookieJar

QTY = Decimal("20")
UNIT = "kg"
UNIT_PRICE = Decimal("98.50")
EXPECTED_TOTAL = Decimal("1970.00")
TAMPERED_TOTAL = Decimal("1.00")
MIN_STOCK_FOR_PROBE = 60          # main 20 + concurrency 20 + one spare order
RFQ_NO_RE = re.compile(r"^RFQ\d{14}$")
ACCEPT_PATH = "/rfq/quotes/%s/accept"


class Client:
    """Cookie-jar session with CSRF header (same shape as rfq_quote_probe)."""

    def __init__(self, backend: str):
        self.backend = backend.rstrip("/")
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, path, method="GET", body=None, headers=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.backend + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, _json(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, _json(e.read())
        except Exception as e:  # noqa: BLE001 - probe must report, not crash
            return -1, {"error": str(e)}

    def login(self, phone: str, password: str) -> None:
        status, body = self.call("/auth/login", "POST",
                                 {"phone": phone, "password": password})
        if status != 200 or (body or {}).get("code") != 200:
            raise SystemExit("login failed HTTP=%s code=%s"
                             % (status, (body or {}).get("code")))
        if (body.get("data") or {}).get("require2fa"):
            raise SystemExit("account has 2FA enabled; use the demo account without 2FA")
        _, csrf_body = self.call("/user/csrf-token")
        data = (csrf_body or {}).get("data") or {}
        self.csrf = str(data.get("token") or data.get("csrfToken") or "")
        if not self.csrf:
            raise SystemExit("no CSRF token returned")

    def write(self, path, body=None, csrf=True):
        headers = {"X-CSRF-TOKEN": self.csrf} if csrf else {}
        return self.call(path, "POST", body, headers)


def _json(payload):
    try:
        return json.loads(payload.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None


class Db:
    """Read/write helper that keeps credentials inside the container."""

    def __init__(self, container: str):
        self.container = container

    def run(self, stmt: str) -> str:
        inner = ('mysql --default-character-set=utf8mb4 -uroot '
                 '-p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -N -B -e '
                 + shlex.quote(stmt))
        proc = subprocess.run(
            ["docker", "exec", self.container, "sh", "-c", inner],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=90)
        if proc.returncode != 0:
            raise SystemExit("docker/mysql failed: %s" % (proc.stderr or "")[:300])
        return (proc.stdout or "").strip()

    def rows(self, stmt: str):
        out = self.run(stmt)
        return [line.split("\t") for line in out.splitlines() if line]


class Probe:
    def __init__(self, out_path: str, meta: dict):
        self.out_path = out_path
        self.lock = threading.RLock()
        self.evidence = {
            "probe": "rfq-accept",
            "purpose": "RFQ S5 buyer accepts a merchant quote: order wiring, "
                       "zero discounts, single stock deduction, replay and "
                       "concurrent idempotency, anti-tamper / validity guards, "
                       "merchant-side visibility, cleanup -- live stack",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "meta": meta,
            "checks": [],
            "notes": [],
            "rate_limit": [],
            "cleanup": {},
            "summary": {},
        }

    def check(self, name: str, ok: bool, detail=None):
        with self.lock:
            self.evidence["checks"].append(
                {"name": name, "ok": bool(ok), "detail": detail})
            print("[%s] %s" % ("PASS" if ok else "FAIL", name))
            if not ok:
                print("       detail: %s" % json.dumps(detail, ensure_ascii=True)[:900])
            self.flush()

    def note(self, name: str, detail):
        with self.lock:
            self.evidence["notes"].append({"name": name, "detail": detail})
            self.flush()

    def flush(self):
        with self.lock:
            if self.out_path:
                with open(self.out_path, "w", encoding="utf-8") as fh:
                    json.dump(self.evidence, fh, ensure_ascii=False, indent=2)

    @property
    def failed(self):
        return [c for c in self.evidence["checks"] if c.get("ok") is False]

    def finish(self, code: int):
        total = len(self.evidence["checks"])
        failed = len(self.failed)
        self.evidence["summary"] = {
            "checks": total,
            "passed": total - failed,
            "failed": failed,
            "exit_code": code,
        }
        self.flush()
        print("\n%d checks, %d failed, exit=%d, evidence: %s"
              % (total, failed, code, self.out_path))
        return code


def q(value: str) -> str:
    """Quote an internally-generated SQL literal."""
    return "'" + str(value).replace("'", "''") + "'"


def sql_in(values):
    return ",".join(q(v) for v in values)


def dec(value) -> Decimal:
    if value is None:
        return Decimal("0")
    text = str(value).strip()
    if text in ("", "NULL", "None"):
        return Decimal("0")
    try:
        return Decimal(text)
    except InvalidOperation:
        return Decimal("0")


def safe_run(p: Probe, db: Db, stmt: str, label: str) -> bool:
    """Run one cleanup statement; a DB failure becomes a failed check, not a crash."""
    try:
        db.run(stmt)
        return True
    except BaseException as e:  # noqa: BLE001 - cleanup must fully report
        p.check("cleanup step failed: %s" % label, False, {"error": str(e)[:300]})
        return False


def seconds_to_next_window() -> int:
    """Sleep until the next whole minute plus a small margin (rate-limit
    windows are aligned on floor(now/60s) by the server)."""
    return int(60 - (time.time() % 60)) + 2


def post_retry(p: Probe, client: Client, path: str, body, label: str,
               attempts: int = 6):
    """POST with 429 tolerance: record the throttle, wait out the window,
    then retry. Returns (http, body, throttle_hits)."""
    hits = []
    st, bd = -1, None
    for attempt in range(1, attempts + 1):
        st, bd = client.write(path, body)
        code = (bd or {}).get("code")
        if st == 429 or code == 429:
            wait = seconds_to_next_window()
            hits.append({"attempt": attempt, "http": st, "code": code, "wait_s": wait})
            with p.lock:
                p.evidence["rate_limit"].append({"path": label, "attempt": attempt,
                                                 "http": st, "wait_s": wait})
            p.note("429 on %s, waiting for the next window then retrying" % label,
                   {"attempt": attempt, "wait_s": wait})
            print("[WAIT] 429 on %s, sleeping %ss then retrying" % (label, wait))
            time.sleep(wait)
            continue
        break
    return st, bd, hits


def login_client(backend: str, phone: str, password: str, p: Probe,
                 label: str) -> Client:
    client = Client(backend)
    try:
        client.login(phone, password)
    except SystemExit as e:
        p.check(label, False, {"error": str(e)})
        raise StopProbe(1)
    p.check(label, True, {"http": 200})
    return client


class StopProbe(Exception):
    """Abort the main flow but still run the cleanup block and write evidence."""

    def __init__(self, code: int):
        super().__init__("probe stopped")
        self.code = code


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:18181/api")
    ap.add_argument("--buyer-phone", required=True)
    ap.add_argument("--buyer-password", required=True)
    ap.add_argument("--merchant-phone", required=True)
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true",
                    help="skip cleanup (leaves the probe RFQs/orders behind)")
    ap.add_argument("--out", default="reports/rfq-accept-evidence.json")
    args = ap.parse_args()

    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "buyer_suffix": args.buyer_phone[-4:],
        "merchant_suffix": args.merchant_phone[-4:],
        "run_id": run_id,
        "qty": str(QTY),
        "unit": UNIT,
        "unit_price": str(UNIT_PRICE),
        "expected_total": str(EXPECTED_TOTAL),
        "tampered_total": str(TAMPERED_TOTAL),
        "min_stock_for_probe": MIN_STOCK_FOR_PROBE,
        "rate_limit": "POST /rfq, /rfq/*, /merchant/rfq/* -> 10 req / 60 s per IP",
    })
    if args.out:
        parent = os.path.dirname(os.path.abspath(args.out))
        if parent:
            os.makedirs(parent, exist_ok=True)
    db = Db(args.mysql_container)

    try:
        db.run("SELECT 1")
    except SystemExit as e:
        print(str(e))
        return p.finish(3)

    # Record which container serves /api (the probe never starts/restarts it).
    try:
        proc = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Status}}", "rfq-img-verify"],
            capture_output=True, text=True, timeout=30)
        container_status = (proc.stdout or "").strip() if proc.returncode == 0 else "not-found"
    except Exception:  # noqa: BLE001
        container_status = "unknown"
    p.evidence["meta"]["backend_container"] = {
        "name": "rfq-img-verify", "status": container_status,
        "url": args.backend,
    }
    p.flush()

    # ---- shared state (also read by the finally-block cleanup) ----
    base_rfq = base_quote = base_event = base_order = -1
    base_stock = -1
    buyer_uid = merchant_uid = mer_id = None
    prod = None
    buyer = None
    merchant = None
    run_start_db = None
    rfqs = []          # [{"tag","no","id","quote_id"}]
    order_nos = []     # every order this run created (deduped)
    stop_code = 0

    def remember_order(order_no):
        if order_no and order_no not in order_nos:
            order_nos.append(order_no)

    # Timestamps are derived from the DB clock on purpose: the host shell and
    # the MySQL/JVM containers may sit in different time zones, and an
    # "expired" value computed from the host clock could land in the future.
    def db_iso_future(days: int) -> str:
        return db.rows("SELECT DATE_FORMAT(NOW() + INTERVAL %d DAY, "
                       "'%%Y-%%m-%%dT%%H:%%i:%%s')" % days)[0][0]

    def db_sql_past(hours: int) -> str:
        return db.rows("SELECT DATE_FORMAT(NOW() - INTERVAL %d HOUR, "
                       "'%%Y-%%m-%%d %%H:%%i:%%s')" % hours)[0][0]

    def stock_now():
        return int(db.rows("SELECT stock FROM t_product WHERE prod_id = %s"
                           % q(prod[0]))[0][0])

    def count_orders_for_quote(quote_id):
        return int(db.rows("SELECT COUNT(*) FROM t_order WHERE rfq_quote_id = %s"
                           % q(quote_id))[0][0])

    def accept_body(expected_total, extra=None):
        body = {
            "receiver": "RFQ Probe Buyer",
            "phone": "13900000001",
            "address": "Probe address, no real delivery, order cancelled by probe",
            "payType": "corporate",
            "expectedTotal": str(expected_total),
            "remark": "rfq-accept-probe-%s" % run_id,
        }
        if extra:
            body.update(extra)
        return body

    def publish(tag: str):
        title = "RFQ-ACCEPT-%s-%s" % (run_id, tag)
        payload = {
            "title": title,
            "description": "rfq-accept-probe-%s-%s" % (run_id, tag),
            "quantity": str(QTY),
            "unit": UNIT,
            "quoteDeadline": db_iso_future(3),
        }
        st, bd, hits = post_retry(p, buyer, "/rfq", payload, "POST /rfq[%s]" % tag)
        rfq_no = ((bd or {}).get("data") or {}).get("rfqNo")
        p.check("b%s: buyer publishes RFQ (HTTP 200 / code 200)" % tag,
                st == 200 and (bd or {}).get("code") == 200 and bool(rfq_no),
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": rfq_no,
                 "rate_limit_hits": hits})
        if not rfq_no:
            raise StopProbe(1)
        # Track the rfqNo immediately so cleanup can find it even if a later
        # assertion on this RFQ fails.
        entry = {"tag": tag, "no": rfq_no, "id": None, "quote_id": None}
        rfqs.append(entry)
        rows = db.rows("SELECT rfq_id, user_id, quantity, status FROM t_rfq "
                       "WHERE rfq_no = %s" % q(rfq_no))
        rfq_id = rows[0][0] if rows else None
        entry["id"] = rfq_id
        ok = (len(rows) == 1 and rows[0][1] == str(buyer_uid)
              and dec(rows[0][2]) == QTY and rows[0][3] == "PUBLISHED")
        p.check("b%s: RFQ row owned by buyer, qty=20, PUBLISHED" % tag, ok,
                {"row": rows[0] if rows else None})
        if not ok:
            raise StopProbe(1)
        return rfq_no, rfq_id

    def quote(rfq_no: str, tag: str):
        payload = {
            "prodId": int(prod[0]),
            "unitPrice": str(UNIT_PRICE),
            "validUntil": db_iso_future(3),
            "remark": "rfq-accept-probe-%s-%s" % (run_id, tag),
        }
        st, bd, hits = post_retry(p, merchant, "/merchant/rfq/%s/quote" % rfq_no,
                                  payload, "POST quote[%s]" % tag)
        data = (bd or {}).get("data") or {}
        quote_id = data.get("quoteId")
        ok = (st == 200 and (bd or {}).get("code") == 200 and quote_id
              and dec(data.get("unitPrice")) == UNIT_PRICE
              and dec(data.get("quantity")) == QTY
              and dec(data.get("totalAmount")) == EXPECTED_TOTAL)
        p.check("b%s: merchant quotes 98.50 -> total 1970.00 (code 200)" % tag, ok,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"),
                 "quoteId": quote_id, "unitPrice": data.get("unitPrice"),
                 "quantity": data.get("quantity"),
                 "totalAmount": data.get("totalAmount"),
                 "rate_limit_hits": hits})
        if not ok:
            raise StopProbe(1)
        for item in rfqs:
            if item["no"] == rfq_no:
                item["quote_id"] = int(quote_id)
        return int(quote_id)

    try:
        # ---------------- a) read-only baseline ----------------
        base_rfq = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
        base_quote = int(db.rows("SELECT COUNT(*) FROM t_rfq_quote")[0][0])
        base_event = int(db.rows("SELECT COUNT(*) FROM t_rfq_event")[0][0])
        base_order = int(db.rows("SELECT COUNT(*) FROM t_order")[0][0])
        run_start_db = db.rows("SELECT NOW()")[0][0]

        buyer_rows = db.rows("SELECT user_id FROM t_user WHERE phone = %s LIMIT 1"
                             % q(args.buyer_phone))
        merchant_rows = db.rows("SELECT user_id FROM t_user WHERE phone = %s LIMIT 1"
                                % q(args.merchant_phone))
        if not buyer_rows or not merchant_rows:
            p.note("precondition", {
                "reason": "demo buyer or merchant missing from t_user",
                "buyer_found": bool(buyer_rows),
                "merchant_found": bool(merchant_rows)})
            print("precondition missing: demo account not found")
            raise StopProbe(2)
        buyer_uid = buyer_rows[0][0]
        merchant_uid = merchant_rows[0][0]

        mer_rows = db.rows("SELECT mer_id FROM t_merchant WHERE user_id = %s "
                           "AND deleted = 0 ORDER BY mer_id LIMIT 1"
                           % q(merchant_uid))
        if not mer_rows:
            p.note("precondition", {"reason": "demo merchant has no t_merchant row",
                                    "merchant_user_id": merchant_uid})
            print("precondition missing: no t_merchant row for the demo merchant")
            raise StopProbe(2)
        mer_id = mer_rows[0][0]

        prod_rows = db.rows(
            "SELECT p.prod_id, p.title, p.price, p.stock FROM t_product p "
            "WHERE p.mer_id = %s AND p.status = 1 AND p.deleted = 0 "
            "AND p.stock >= %d AND NOT EXISTS ("
            "SELECT 1 FROM t_campaign c WHERE c.status = 1 "
            "AND c.scope = 'prod' AND c.prod_ids IS NOT NULL "
            "AND FIND_IN_SET(p.prod_id, c.prod_ids) > 0 "
            "AND (c.start_time IS NULL OR c.start_time <= NOW()) "
            "AND (c.end_time IS NULL OR c.end_time >= NOW())) "
            "ORDER BY p.stock DESC, p.prod_id ASC LIMIT 1"
            % (q(mer_id), MIN_STOCK_FOR_PROBE))
        if not prod_rows:
            p.note("precondition", {
                "reason": "demo merchant has no sellable, non-campaign product "
                          "with stock >= %d" % MIN_STOCK_FOR_PROBE,
                "mer_id": mer_id})
            print("precondition missing: no eligible product for the demo merchant")
            raise StopProbe(2)
        prod = prod_rows[0]
        base_stock = int(prod[3])

        active_flash_all = db.rows(
            "SELECT COUNT(*) FROM t_campaign WHERE status = 1 AND type = 'flash_sale' "
            "AND scope = 'all' AND (start_time IS NULL OR start_time <= NOW()) "
            "AND (end_time IS NULL OR end_time >= NOW())")[0][0]

        p.note("baseline", {
            "t_rfq_total": base_rfq,
            "t_rfq_quote_total": base_quote,
            "t_rfq_event_total": base_event,
            "t_order_total": base_order,
            "buyer_user_id": buyer_uid,
            "merchant_user_id": merchant_uid,
            "demo_mer_id": mer_id,
            "probe_product": {"prod_id": prod[0], "title": prod[1],
                              "price": prod[2], "stock": base_stock},
            "active_flash_sale_scope_all": int(active_flash_all),
            "db_now": run_start_db,
            "run_id": run_id,
            "thresholds": {"rate_limit_per_min": 10},
        })
        p.check("a: demo buyer and merchant are distinct accounts",
                buyer_uid != merchant_uid,
                {"buyer_user_id": buyer_uid, "merchant_user_id": merchant_uid})
        p.check("a: merchant owns a sellable non-campaign product with stock >= 60",
                base_stock >= MIN_STOCK_FOR_PROBE,
                {"prod_id": prod[0], "stock": base_stock,
                 "active_flash_sale_scope_all": int(active_flash_all)})

        # ---------------- b) main chain ----------------
        buyer = login_client(args.backend, args.buyer_phone, args.buyer_password,
                             p, "b: buyer login")
        merchant = login_client(args.backend, args.merchant_phone,
                                args.merchant_password, p, "b: merchant login")

        rfq1_no, rfq1_id = publish("main")
        quote1 = quote(rfq1_no, "main")

        st, bd = buyer.call("/rfq/%s/quotes" % rfq1_no)
        seen = [i for i in ((bd or {}).get("data") or [])
                if int(i.get("quoteId") or 0) == quote1]
        p.check("b: buyer GET /rfq/{rfqNo}/quotes shows the quote (98.50 / 1970.00)",
                st == 200 and (bd or {}).get("code") == 200 and len(seen) == 1
                and dec(seen[0].get("unitPrice")) == UNIT_PRICE
                and dec(seen[0].get("totalAmount")) == EXPECTED_TOTAL,
                {"http": st, "code": (bd or {}).get("code"),
                 "matches": len(seen), "quote": seen[0] if seen else None,
                 "quote_total": len((bd or {}).get("data") or [])})

        st, bd, hits = post_retry(p, buyer, ACCEPT_PATH % quote1,
                                  accept_body(EXPECTED_TOTAL), "POST accept[main]")
        order1 = ((bd or {}).get("data") or {}).get("orderNo")
        p.check("b: buyer accepts the quote (HTTP 200 / code 200, orderNo returned)",
                st == 200 and (bd or {}).get("code") == 200 and bool(order1),
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "orderNo": order1,
                 "rate_limit_hits": hits})
        remember_order(order1)
        if not order1:
            raise StopProbe(1)

        # ---------------- c) DB assertions ----------------
        order_rows = db.rows(
            "SELECT order_no, user_id, total_amount, promo_discount, coupon_discount, "
            "point_deduct_amount, coupon_id, pay_type, pay_status, order_status, "
            "rfq_no, rfq_quote_id FROM t_order WHERE order_no = %s" % q(order1))
        r = order_rows[0] if order_rows else None
        ok_order = (r is not None and len(order_rows) == 1
                    and dec(r[2]) == EXPECTED_TOTAL
                    and dec(r[3]) == 0 and dec(r[4]) == 0 and dec(r[5]) == 0
                    and r[6] == "NULL" and r[7] == "corporate"
                    and r[8] == "0" and r[9] == "0"
                    and r[10] == rfq1_no and r[11] == str(quote1)
                    and r[1] == str(buyer_uid))
        p.note("c: t_order row of the accepted deal", r)
        p.check("c: order total 1970.00 with promo/coupon/point discounts = 0 and "
                "rfq_no / rfq_quote_id wired", ok_order,
                {"row": r, "expected_total": str(EXPECTED_TOTAL),
                 "expected_rfq_no": rfq1_no, "expected_quote_id": quote1})

        item_rows = db.rows(
            "SELECT prod_id, price, quantity, subtotal FROM t_order_item "
            "WHERE order_no = %s" % q(order1))
        i0 = item_rows[0] if item_rows else None
        ok_item = (i0 is not None and len(item_rows) == 1
                   and i0[0] == str(prod[0]) and dec(i0[1]) == UNIT_PRICE
                   and dec(i0[2]) == QTY and dec(i0[3]) == EXPECTED_TOTAL)
        p.note("c: t_order_item row", i0)
        p.check("c: order item price=98.50 qty=20 subtotal=1970.00 (single row)",
                ok_item, {"row": i0, "expected_prod_id": prod[0]})

        stock_after_main = stock_now()
        p.check("c: product stock = baseline - 20",
                stock_after_main == base_stock - 20,
                {"baseline": base_stock, "after_accept": stock_after_main})

        q_status = db.rows("SELECT status FROM t_rfq_quote WHERE quote_id = %s"
                           % q(quote1))
        rfq_status = db.rows("SELECT status FROM t_rfq WHERE rfq_id = %s"
                             % q(rfq1_id))
        p.check("c: quote status ACCEPTED and RFQ status ORDERED",
                bool(q_status) and q_status[0][0] == "ACCEPTED"
                and bool(rfq_status) and rfq_status[0][0] == "ORDERED",
                {"quote_status": q_status[0][0] if q_status else None,
                 "rfq_status": rfq_status[0][0] if rfq_status else None})

        ev_rows = db.rows(
            "SELECT COUNT(*), MIN(event_type), MIN(quote_id), MIN(actor_role) "
            "FROM t_rfq_event WHERE rfq_id = %s" % q(rfq1_id))
        ok_ev = (bool(ev_rows) and ev_rows[0][0] == "1"
                 and ev_rows[0][1] == "ACCEPT" and ev_rows[0][2] == str(quote1)
                 and ev_rows[0][3] == "BUYER")
        p.note("c: t_rfq_event aggregate", ev_rows[0] if ev_rows else None)
        p.check("c: exactly one t_rfq_event ACCEPT for this RFQ", ok_ev,
                {"aggregate": ev_rows[0] if ev_rows else None})

        # ---------------- d) replay ----------------
        st, bd, hits = post_retry(p, buyer, ACCEPT_PATH % quote1,
                                  accept_body(EXPECTED_TOTAL), "POST accept[replay]")
        order1_replay = ((bd or {}).get("data") or {}).get("orderNo")
        remember_order(order1_replay)
        p.check("d: replay returns the same orderNo with code 200",
                st == 200 and (bd or {}).get("code") == 200
                and order1_replay == order1,
                {"http": st, "code": (bd or {}).get("code"),
                 "orderNo_first": order1, "orderNo_replay": order1_replay,
                 "rate_limit_hits": hits})
        p.check("d: replay created no extra order (1 row for this quote) and no "
                "extra stock deduction",
                count_orders_for_quote(quote1) == 1
                and stock_now() == stock_after_main,
                {"orders_for_quote": count_orders_for_quote(quote1),
                 "stock": stock_now(), "stock_after_main": stock_after_main})

        # ---------------- e) concurrent accept ----------------
        rfq2_no, rfq2_id = publish("conc")
        quote2 = quote(rfq2_no, "conc")
        buyer_b = login_client(args.backend, args.buyer_phone,
                               args.buyer_password, p, "e: buyer login (session B)")
        buyer_c = login_client(args.backend, args.buyer_phone,
                               args.buyer_password, p, "e: buyer login (session C)")
        stock_before_conc = stock_now()

        # Force a fresh throttle window so both accepts really race.
        wait = seconds_to_next_window()
        p.note("e: waiting for a fresh rate-limit window before the barrier",
               {"wait_s": wait})
        print("[WAIT] fresh rate window before barrier: %ss" % wait)
        time.sleep(wait)

        barrier = threading.Barrier(2)
        results = [None, None]

        def worker(idx, client):
            try:
                barrier.wait(timeout=60)
            except Exception as e:  # noqa: BLE001
                results[idx] = {"http": -1, "code": None,
                                "error": "barrier: %s" % e}
                return
            st2, bd2, hits2 = post_retry(
                p, client, ACCEPT_PATH % quote2, accept_body(EXPECTED_TOTAL),
                "POST accept[conc-%d]" % idx)
            data2 = (bd2 or {}).get("data") or {}
            results[idx] = {
                "http": st2, "code": (bd2 or {}).get("code"),
                "orderNo": data2.get("orderNo"),
                "message": (bd2 or {}).get("message"),
                "rate_limit_hits": hits2,
            }

        threads = [threading.Thread(target=worker, args=(0, buyer_b)),
                   threading.Thread(target=worker, args=(1, buyer_c))]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=300)

        for res in results:
            remember_order((res or {}).get("orderNo"))
        conc_rows = db.rows("SELECT order_no FROM t_order WHERE rfq_quote_id = %s"
                            % q(quote2))
        for row in conc_rows:
            remember_order(row[0])
        conc_orders = len(conc_rows)
        stock_after_conc = stock_now()
        ok_codes = [r for r in results if (r or {}).get("code") == 200]
        same_no = (len(ok_codes) == 2
                   and ok_codes[0].get("orderNo")
                   and ok_codes[0].get("orderNo") == ok_codes[1].get("orderNo"))
        one_ok = (len(ok_codes) == 1
                  and all((r or {}).get("code") != 200 for r in results
                          if r is not ok_codes[0]))
        p.note("e: concurrent accept responses", results)
        p.check("e: two concurrent accepts -> exactly one order row for the quote",
                conc_orders == 1,
                {"orders_for_quote": conc_orders,
                 "order_nos": [row[0] for row in conc_rows]})
        p.check("e: stock deducted exactly once by the racing pair",
                stock_after_conc == stock_before_conc - 20,
                {"before": stock_before_conc, "after": stock_after_conc,
                 "baseline": base_stock})
        p.check("e: responses are (one 200 + one non-200) or (two 200, same orderNo)",
                one_ok or same_no,
                {"codes": [((r or {}).get("http"), (r or {}).get("code"))
                           for r in results],
                 "order_nos": [(r or {}).get("orderNo") for r in results],
                 "same_order_no": bool(same_no),
                 "single_success": bool(one_ok)})
        p.check("e: concurrency order total is 1970.00",
                bool(conc_rows) and dec(db.rows(
                    "SELECT total_amount FROM t_order WHERE rfq_quote_id = %s"
                    % q(quote2))[0][0]) == EXPECTED_TOTAL,
                {"total": db.rows("SELECT total_amount FROM t_order "
                                  "WHERE rfq_quote_id = %s" % q(quote2))})

        # ---------------- f) anti-tamper / boundary ----------------
        rfq3_no, rfq3_id = publish("guard")
        quote3 = quote(rfq3_no, "guard")
        stock_before_guard = stock_now()

        st, bd, hits = post_retry(p, buyer, ACCEPT_PATH % quote3,
                                  accept_body(TAMPERED_TOTAL),
                                  "POST accept[guard-total]")
        msg = str((bd or {}).get("message") or "")
        p.check("f: expectedTotal=1 -> rejected with a message containing "
                "the quote-total mismatch wording, no order",
                (bd or {}).get("code") != 200 and "报价总额不一致" in msg
                and count_orders_for_quote(quote3) == 0
                and stock_now() == stock_before_guard,
                {"http": st, "code": (bd or {}).get("code"), "message": msg,
                 "orders_for_quote": count_orders_for_quote(quote3),
                 "stock": stock_now(), "rate_limit_hits": hits})

        orig_valid = db.rows("SELECT valid_until FROM t_rfq_quote WHERE quote_id = %s"
                             % q(quote3))
        orig_valid = orig_valid[0][0] if orig_valid else None
        past = db_sql_past(1)
        db.run("UPDATE t_rfq_quote SET valid_until = %s WHERE quote_id = %s"
               % (q(past), q(quote3)))
        moved = db.rows("SELECT valid_until FROM t_rfq_quote WHERE quote_id = %s"
                        % q(quote3))
        p.check("f: SQL moved quote.valid_until into the past",
                bool(moved) and moved[0][0].replace(" ", " ") == past,
                {"valid_until": moved[0][0] if moved else None, "sent": past})

        st, bd, hits = post_retry(p, buyer, ACCEPT_PATH % quote3,
                                  accept_body(EXPECTED_TOTAL),
                                  "POST accept[guard-expired]")
        msg = str((bd or {}).get("message") or "")
        p.check("f: expired quote -> body code 400 mentioning the validity window, "
                "no order",
                (bd or {}).get("code") == 400 and "有效期" in msg
                and count_orders_for_quote(quote3) == 0
                and stock_now() == stock_before_guard,
                {"http": st, "code": (bd or {}).get("code"), "message": msg,
                 "orders_for_quote": count_orders_for_quote(quote3),
                 "rate_limit_hits": hits})

        if orig_valid and orig_valid != "NULL":
            db.run("UPDATE t_rfq_quote SET valid_until = %s WHERE quote_id = %s"
                   % (q(orig_valid), q(quote3)))
            restored = db.rows("SELECT valid_until FROM t_rfq_quote "
                               "WHERE quote_id = %s" % q(quote3))
            p.check("f: original valid_until restored",
                    bool(restored) and restored[0][0] == orig_valid,
                    {"valid_until": restored[0][0] if restored else None,
                     "original": orig_valid})

        st, bd, hits = post_retry(
            p, buyer, ACCEPT_PATH % quote3,
            accept_body(EXPECTED_TOTAL, {"couponId": 1, "usePoints": 100}),
            "POST accept[guard-coupon-points]")
        coupon_order = ((bd or {}).get("data") or {}).get("orderNo")
        remember_order(coupon_order)
        no_order = count_orders_for_quote(quote3) == 0
        p.check("f: couponId/usePoints on the accept body -> rejected, no order "
                "(accept DTO carries no coupon/points fields)",
                (bd or {}).get("code") != 200 and no_order
                and stock_now() == stock_before_guard,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"),
                 "orderNo_created_unexpectedly": coupon_order,
                 "orders_for_quote": count_orders_for_quote(quote3),
                 "stock": stock_now(), "rate_limit_hits": hits})
        if coupon_order:
            p.note("f: coupon/points accept unexpectedly created an order; it is "
                   "tracked for cancel + SQL cleanup", {"orderNo": coupon_order})

        # ---------------- g) merchant-side visibility ----------------
        st, bd = merchant.call("/order/merchant/list?page=1&size=20&keyword=%s"
                               % order1)
        records = (((bd or {}).get("data") or {}).get("records") or [])
        hit = [x for x in records
               if ((x.get("order") or {}).get("orderNo") == order1)]
        vis = hit[0] if hit else None
        ok_vis = (st == 200 and (bd or {}).get("code") == 200 and len(hit) == 1
                  and dec((vis.get("order") or {}).get("totalAmount")) == EXPECTED_TOTAL
                  and dec(vis.get("myAmount")) == EXPECTED_TOTAL
                  and bool(vis.get("myItems"))
                  and dec((vis.get("myItems") or [{}])[0].get("price")) == UNIT_PRICE)
        p.note("g: merchant list record", vis)
        p.check("g: merchant GET /order/merchant/list sees the deal at 1970.00 "
                "(t_order JOIN t_product ownership)",
                ok_vis,
                {"http": st, "code": (bd or {}).get("code"),
                 "records_matched": len(hit),
                 "order_total": (vis.get("order") or {}).get("totalAmount")
                 if vis else None,
                 "my_amount": vis.get("myAmount") if vis else None,
                 "my_items": vis.get("myItems") if vis else None})
    except StopProbe as e:
        stop_code = e.code
        p.note("probe stopped early", {"exit_code": e.code})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        p.check("probe ran to completion without unexpected error", False,
                {"error": repr(e)[:400]})
    finally:
        cleanup = p.evidence["cleanup"]
        cleanup["recorded_rfq_nos"] = [x["no"] for x in rfqs]
        cleanup["recorded_quote_ids"] = [x["quote_id"] for x in rfqs if x["quote_id"]]
        cleanup["recorded_order_nos"] = list(order_nos)
        cleanup["baselines"] = {
            "t_rfq": base_rfq, "t_rfq_quote": base_quote,
            "t_rfq_event": base_event, "t_order": base_order,
            "stock": base_stock,
        }
        if args.keep:
            cleanup["mode"] = "kept"
            cleanup["reason"] = "--keep"
        else:
            cleanup["mode"] = "cleanup"
            cancelled = []
            try:
                # 1) cancel every order this run created -> server restores stock
                if order_nos and buyer is not None:
                    for order_no in order_nos:
                        exists = db.rows("SELECT order_status FROM t_order "
                                         "WHERE order_no = %s" % q(order_no))
                        if not exists:
                            cancelled.append({"orderNo": order_no, "skipped": "missing"})
                            continue
                        if exists[0][0] == "3":
                            cancelled.append({"orderNo": order_no, "skipped": "already cancelled"})
                            continue
                        st, bd, hits = post_retry(
                            p, buyer, "/order/cancel/%s" % order_no, None,
                            "POST /order/cancel[%s]" % order_no)
                        ok = st == 200 and (bd or {}).get("code") == 200
                        cancelled.append({"orderNo": order_no, "ok": ok,
                                          "http": st, "code": (bd or {}).get("code"),
                                          "message": (bd or {}).get("message"),
                                          "rate_limit_hits": hits})
                        p.check("cleanup: order %s cancelled via /order/cancel" % order_no,
                                ok, cancelled[-1])
                cleanup["cancel"] = cancelled
                cleanup["stock_after_cancel"] = (
                    stock_now() if prod is not None else None)
                if base_stock >= 0 and prod is not None:
                    p.check("cleanup: stock restored to baseline by cancel",
                            stock_now() == base_stock,
                            {"baseline": base_stock, "stock": stock_now()})

                # 2) SQL-delete ONLY this run's rows
                rfq_nos = [x["no"] for x in rfqs]
                rfq_ids = [x["id"] for x in rfqs if x["id"]]
                quote_ids = [x["quote_id"] for x in rfqs if x["quote_id"]]
                order_list = list(order_nos)

                if order_list:
                    safe_run(p, db, "DELETE FROM t_order_item WHERE order_no IN (%s)"
                             % sql_in(order_list), "t_order_item(probe order_nos)")
                    safe_run(p, db, "DELETE FROM t_message WHERE related_no IN (%s)"
                             % sql_in(order_list), "t_message(probe order_nos)")
                    safe_run(p, db,
                             "DELETE FROM t_user_risk_event WHERE "
                             "(ref_type = 'order' AND ref_id IN (%s))"
                             % sql_in(order_list),
                             "t_user_risk_event(probe order refs)")
                    safe_run(p, db, "DELETE FROM t_order WHERE order_no IN (%s)"
                             % sql_in(order_list), "t_order(probe order_nos)")
                if quote_ids and run_start_db:
                    safe_run(p, db,
                             "DELETE FROM t_user_risk_event WHERE user_id = %s "
                             "AND ref_type = 'rfq' AND ref_id = 'expectedTotal' "
                             "AND create_time >= %s"
                             % (q(buyer_uid), q(run_start_db)),
                             "t_user_risk_event(probe rfq tamper)")
                    safe_run(p, db,
                             "DELETE FROM t_tamper_alert WHERE user_id = %s "
                             "AND biz_type = 'rfq' AND field_name = 'expectedTotal' "
                             "AND create_time >= %s"
                             % (q(buyer_uid), q(run_start_db)),
                             "t_tamper_alert(probe rfq tamper)")
                    safe_run(p, db,
                             "DELETE FROM t_idempotent_token WHERE biz_type = 'rfq-accept' "
                             "AND biz_key IN (%s)"
                             % sql_in([str(v) for v in quote_ids]),
                             "t_idempotent_token(rfq-accept)")
                if rfq_ids:
                    safe_run(p, db, "DELETE FROM t_rfq_event WHERE rfq_id IN (%s)"
                             % sql_in(rfq_ids), "t_rfq_event(probe rfq_ids)")
                    safe_run(p, db, "DELETE FROM t_rfq_quote WHERE rfq_id IN (%s)"
                             % sql_in(rfq_ids), "t_rfq_quote(probe rfq_ids)")
                if rfq_nos:
                    safe_run(p, db, "DELETE FROM t_rfq WHERE rfq_no IN (%s) "
                             "AND user_id = %s"
                             % (sql_in(rfq_nos), q(buyer_uid)),
                             "t_rfq(probe rfq_nos)")

                cleanup["deleted_scope"] = [
                    "t_order/t_order_item/t_message/t_user_risk_event by this run's order_nos",
                    "t_tamper_alert + R05 risk row by user + 'rfq'/expectedTotal "
                    "created after the run started",
                    "t_idempotent_token biz_type='rfq-accept' and biz_key in this run's quote_ids",
                    "t_rfq_event/t_rfq_quote by this run's rfq_ids, t_rfq by this run's rfq_nos",
                ]
                cleanup["sequence_untouched"] = (
                    "t_sys_sequence rfq:<day> deliberately NOT deleted (system "
                    "numbering state, not probe data)")

                # 3) verify no residue
                if order_list:
                    left = db.rows("SELECT order_no FROM t_order WHERE order_no IN (%s)"
                                   % sql_in(order_list))
                    p.check("cleanup: no t_order row left for the probe order_nos",
                            not left, {"left": left})
                if rfq_nos:
                    left = db.rows("SELECT rfq_no FROM t_rfq WHERE rfq_no IN (%s)"
                                   % sql_in(rfq_nos))
                    p.check("cleanup: no t_rfq row left for the probe rfq_nos",
                            not left, {"left": left})
                if rfq_ids:
                    orphans = db.rows(
                        "SELECT (SELECT COUNT(*) FROM t_rfq_quote WHERE rfq_id IN (%s))"
                        " + (SELECT COUNT(*) FROM t_rfq_event WHERE rfq_id IN (%s))"
                        % (sql_in(rfq_ids), sql_in(rfq_ids)))
                    p.check("cleanup: no quote/event row left for the probe rfq_ids",
                            bool(orphans) and orphans[0][0] == "0",
                            {"orphan_count": orphans[0][0] if orphans else None})
                if quote_ids:
                    left = db.rows(
                        "SELECT COUNT(*) FROM t_idempotent_token WHERE "
                        "biz_type = 'rfq-accept' AND biz_key IN (%s)"
                        % sql_in([str(v) for v in quote_ids]))
                    p.check("cleanup: no rfq-accept idempotency token left",
                            bool(left) and left[0][0] == "0",
                            {"left": left[0][0] if left else None})
                if order_list:
                    left = db.rows("SELECT COUNT(*) FROM t_message WHERE "
                                   "related_no IN (%s)" % sql_in(order_list))
                    p.check("cleanup: no t_message left for the probe order_nos",
                            bool(left) and left[0][0] == "0",
                            {"left": left[0][0] if left else None})

                final = {
                    "t_rfq_total": int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0]),
                    "t_rfq_baseline": base_rfq,
                    "t_rfq_quote_total": int(db.rows(
                        "SELECT COUNT(*) FROM t_rfq_quote")[0][0]),
                    "t_rfq_quote_baseline": base_quote,
                    "t_rfq_event_total": int(db.rows(
                        "SELECT COUNT(*) FROM t_rfq_event")[0][0]),
                    "t_rfq_event_baseline": base_event,
                    "t_order_total": int(db.rows("SELECT COUNT(*) FROM t_order")[0][0]),
                    "t_order_baseline": base_order,
                    "stock": stock_now() if prod is not None else None,
                    "stock_baseline": base_stock,
                }
                seq_rows = db.rows(
                    "SELECT seq_key, current_val FROM t_sys_sequence "
                    "WHERE seq_key LIKE 'rfq:%%' ORDER BY seq_key")
                final["t_sys_sequence_rfq_rows"] = seq_rows
                cleanup["final"] = final
                p.check("cleanup: t_rfq rows back to baseline",
                        final["t_rfq_total"] == base_rfq,
                        {"baseline": base_rfq, "final": final["t_rfq_total"]})
                p.check("cleanup: t_rfq_quote rows back to baseline",
                        final["t_rfq_quote_total"] == base_quote,
                        {"baseline": base_quote,
                         "final": final["t_rfq_quote_total"]})
                p.check("cleanup: t_rfq_event rows back to baseline",
                        final["t_rfq_event_total"] == base_event,
                        {"baseline": base_event,
                         "final": final["t_rfq_event_total"]})
                p.check("cleanup: t_order rows back to baseline",
                        final["t_order_total"] == base_order,
                        {"baseline": base_order, "final": final["t_order_total"]})
                if prod is not None and base_stock >= 0:
                    p.check("cleanup: product stock back to baseline",
                            final["stock"] == base_stock,
                            {"baseline": base_stock, "final": final["stock"]})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("cleanup: final state verification failed", False,
                        {"error": str(e)[:300]})

        p.note("scope", {
            "containers_restarted": False,
            "secrets_printed": False,
            "java_modified": False,
            "touched_rows": "only the probe RFQs, their quotes/events, the orders "
                            "created by accepting them, and the probe's own "
                            "tamper/risk/message/idempotency rows (all deleted)",
            "other_users_untouched": True,
            "sequence_untouched": True,
        })

    code = 1 if p.failed else stop_code
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
