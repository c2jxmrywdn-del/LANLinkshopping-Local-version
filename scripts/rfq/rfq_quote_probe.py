#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RFQ S4 merchant-quote acceptance probe (self-cleaning, one-shot).

Proves on the running stack what S4 ships and how its guard rails fire:

  a) read-only baseline: t_rfq / t_rfq_quote row counts; demo merchant merId
     (resolved from t_merchant via the demo user), several status=1 products
     owned by that merchant, and one status=1 product of ANOTHER merchant.
  b) buyer logs in and publishes one legal RFQ (quantity=20, future deadline).
  c) merchant logs in: GET /merchant/rfq/open contains the rfqNo; GET
     /merchant/rfq/{rfqNo} returns the order WITHOUT buyer identity
     (userId/phone/contact) and WITHOUT unitPrice (no competitor pricing).
  d) merchant POSTs /merchant/rfq/{rfqNo}/quote with EXTRA client fields
     "totalAmount":1 and "quantity":1. The server must ignore them:
     DB t_rfq_quote.total_amount = 98.50 x 20 = 1970.00, quantity = 20,
     and the RFQ status flips to QUOTED.
  e) buyer session POSTs the same quote endpoint -> body code 403
     (merchant:manage missing; no quote row is written).
  f) merchant quotes with ANOTHER merchant's product -> body code 403
     (ownership guard); the stored quote is unchanged.
  g) merchant re-quotes at 100.00 -> total becomes 2000.00 and the DB still
     holds exactly ONE row for (rfq_id, mer_id) with the SAME quote_id
     (update, not insert).
  h) GET /merchant/rfq/{rfqNo}/quote returns the caller's own quote. A second
     merchant account (optional args) must NOT see it (404 "not quoted");
     without a second demo merchant the probe asserts anonymous GET ->
     HTTP/body 401 and records that competitor invisibility is by endpoint
     design plus RfqQuoteServiceTest unit coverage.
  i) expiry: SQL moves quote_deadline into the past -> new quote attempt gets
     body code 400; the stored quote is untouched; the deadline is restored.
  j) cleanup: SQL-delete ONLY this run's quote/event/rfq rows (by rfq_id /
     rfq_no), verify t_rfq and t_rfq_quote are back to baseline. The
     t_sys_sequence row is deliberately NOT deleted (system numbering state).

Rate limits: POST /rfq and /merchant/rfq/* are throttled at 10 req / 60 s per
client IP (AuthRateLimitInterceptor). This probe issues 1 POST /rfq and 5
POSTs on the quote path; any 429 is recorded, the probe waits out the window
and retries instead of aborting.

Credentials are arguments and never printed. DB access goes through
`docker exec` with the container's own environment variables, so no password
appears in this script, the command line, or the output.

Usage:
  python scripts/rfq/rfq_quote_probe.py \\
      --buyer-phone 13900000001 --buyer-password 'xxx' \\
      --merchant-phone 13700000002 --merchant-password 'xxx' \\
      [--merchant2-phone ... --merchant2-password ...] \\
      [--backend http://127.0.0.1:8080/api] [--mysql-container shopping-mysql-1] \\
      [--keep] [--out reports/rfq-quote-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure,
2 precondition missing (accounts/merchant/products), 3 docker or DB unreachable.

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
UPDATED_PRICE = Decimal("100.00")
UPDATED_TOTAL = Decimal("2000.00")
RFQ_NO_RE = re.compile(r"^RFQ\d{14}$")
# keys that must never appear in a merchant-facing RFQ payload
FORBIDDEN_KEYS = ("userId", "buyerId", "phone", "contact", "unitPrice",
                  "totalAmount", "quoteId", "merId")


class Client:
    """Cookie-jar session with CSRF header, same shape as rfq_publish_probe."""

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
        self.evidence = {
            "probe": "rfq-quote",
            "purpose": "RFQ S4 merchant quote: open list/detail invisibility, "
                       "total anti-tamper, ownership/role guards, update semantics, "
                       "expiry guard, cleanup -- live stack",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "meta": meta,
            "checks": [],
            "notes": [],
            "rate_limit": [],
            "cleanup": {},
            "summary": {},
        }

    def check(self, name: str, ok: bool, detail=None):
        self.evidence["checks"].append({"name": name, "ok": bool(ok), "detail": detail})
        print("[%s] %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            print("       detail: %s" % json.dumps(detail, ensure_ascii=True)[:900])
        self.flush()

    def note(self, name: str, detail):
        self.evidence["notes"].append({"name": name, "detail": detail})
        self.flush()

    def flush(self):
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


def post_with_retry(p: Probe, client: Client, path: str, body, label: str,
                    attempts: int = 4):
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
            p.evidence["rate_limit"].append({"path": label, "attempt": attempt,
                                             "http": st, "wait_s": wait})
            p.note("429 on %s, waiting for the next window then retrying" % label,
                   {"attempt": attempt, "wait_s": wait,
                    "retry_after": str(wait) + "s"})
            print("[WAIT] 429 on %s, sleeping %ss then retrying" % (label, wait))
            time.sleep(wait)
            continue
        break
    return st, bd, hits


def forbidden_hits(text: str):
    low = text.lower()
    return [k for k in FORBIDDEN_KEYS if k.lower() in low]


def iso_future(days: int) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S",
                         time.localtime(time.time() + days * 86400))


def sql_past(hours: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S",
                         time.localtime(time.time() - hours * 3600))


class StopProbe(Exception):
    """Abort the main flow but still run the cleanup block and write evidence."""

    def __init__(self, code: int):
        super().__init__("probe stopped")
        self.code = code


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--buyer-phone", required=True)
    ap.add_argument("--buyer-password", required=True)
    ap.add_argument("--merchant-phone", required=True)
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--merchant2-phone", default=None,
                    help="optional second merchant account (competitor invisibility)")
    ap.add_argument("--merchant2-password", default=None)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true",
                    help="skip cleanup (leaves the probe RFQ behind)")
    ap.add_argument("--out", default="reports/rfq-quote-evidence.json")
    args = ap.parse_args()

    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]
    title_main = "RFQ-S4-quote-probe-%s" % run_id

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "buyer_suffix": args.buyer_phone[-4:],
        "merchant_suffix": args.merchant_phone[-4:],
        "merchant2_suffix": (args.merchant2_phone or "")[-4:] or None,
        "run_id": run_id,
        "qty": str(QTY),
        "unit": UNIT,
        "unit_price": str(UNIT_PRICE),
        "expected_total": str(EXPECTED_TOTAL),
        "updated_price": str(UPDATED_PRICE),
        "updated_total": str(UPDATED_TOTAL),
        "rate_limit": "POST /rfq and /merchant/rfq/* -> 10 req / 60 s per IP",
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

    # Record which process actually serves /api, so the evidence is honest when
    # a local-dev launcher has replaced the container on port 8080. The probe
    # never starts or restarts anything.
    try:
        proc = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Status}}", "shopping-backend-1"],
            capture_output=True, text=True, timeout=30)
        container_status = (proc.stdout or "").strip() if proc.returncode == 0 else "not-found"
    except Exception:  # noqa: BLE001
        container_status = "unknown"
    p.evidence["meta"]["backend_container_status"] = container_status
    if container_status != "running":
        p.note("serving environment", {
            "container_status": container_status,
            "served_by": "another process on the recorded --backend URL "
                         "(e.g. a local jar launched by start-local-env.ps1); "
                         "the probe never restarts containers",
            "caveat": "assertions prove the S4 code live on this API, not the "
                      "container image build itself",
        })
    p.flush()

    # ---------------- a) read-only baseline ----------------
    base_rfq = -1
    base_quote = -1
    base_event = -1
    buyer_uid = None
    merchant_uid = None
    mer_id = None
    own_prod = None
    other_mer_id = None
    other_prod = None

    created_published = False
    rfq_no = None
    rfq_id = None
    quote_id = None
    seq_key_backend = None
    deleted = False

    try:
        base_rfq = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
        base_quote = int(db.rows("SELECT COUNT(*) FROM t_rfq_quote")[0][0])
        base_event = int(db.rows("SELECT COUNT(*) FROM t_rfq_event")[0][0])

        buyer_rows = db.rows("SELECT user_id FROM t_user WHERE phone = %s LIMIT 1"
                             % q(args.buyer_phone))
        merchant_rows = db.rows("SELECT user_id FROM t_user WHERE phone = %s LIMIT 1"
                                % q(args.merchant_phone))
        if not buyer_rows:
            p.note("precondition", {"reason": "demo buyer missing",
                                    "phone_suffix": args.buyer_phone[-4:]})
            print("precondition missing: demo buyer not found in t_user")
            return p.finish(2)
        if not merchant_rows:
            p.note("precondition", {"reason": "demo merchant missing",
                                    "phone_suffix": args.merchant_phone[-4:]})
            print("precondition missing: demo merchant not found in t_user")
            return p.finish(2)
        buyer_uid = buyer_rows[0][0]
        merchant_uid = merchant_rows[0][0]

        mer_rows = db.rows("SELECT mer_id FROM t_merchant WHERE user_id = %s "
                           "ORDER BY mer_id LIMIT 1" % q(merchant_uid))
        own_products = []
        if mer_rows:
            mer_id = mer_rows[0][0]
            own_products = db.rows(
                "SELECT prod_id, title, price FROM t_product "
                "WHERE mer_id = %s AND status = 1 AND deleted = 0 "
                "ORDER BY prod_id LIMIT 5" % q(mer_id))
        if mer_id is None:
            p.note("precondition", {"reason": "demo merchant has no t_merchant row",
                                    "merchant_user_id": merchant_uid})
            print("precondition missing: no t_merchant row for the demo merchant")
            return p.finish(2)
        if not own_products:
            p.note("precondition", {"reason": "demo merchant has no status=1 product",
                                    "mer_id": mer_id})
            print("precondition missing: no status=1 product for the demo merchant")
            return p.finish(2)
        own_prod = own_products[0]

        other_rows = db.rows(
            "SELECT m.mer_id FROM t_merchant m "
            "WHERE m.mer_id <> %s AND EXISTS ("
            "SELECT 1 FROM t_product p WHERE p.mer_id = m.mer_id "
            "AND p.status = 1 AND p.deleted = 0) "
            "ORDER BY m.mer_id LIMIT 1" % q(mer_id))
        if not other_rows:
            p.note("precondition", {"reason": "no other merchant with a status=1 product"})
            print("precondition missing: no other merchant product for the 403 check")
            return p.finish(2)
        other_mer_id = other_rows[0][0]
        other_rows = db.rows(
            "SELECT prod_id, title, price FROM t_product "
            "WHERE mer_id = %s AND status = 1 AND deleted = 0 "
            "ORDER BY prod_id LIMIT 1" % q(other_mer_id))
        other_prod = other_rows[0]

        p.note("baseline", {
            "t_rfq_total": base_rfq,
            "t_rfq_quote_total": base_quote,
            "t_rfq_event_total": base_event,
            "buyer_user_id": buyer_uid,
            "merchant_user_id": merchant_uid,
            "demo_mer_id": mer_id,
            "demo_merchant_products": own_products,
            "other_mer_id": other_mer_id,
            "other_merchant_product": other_prod,
            "run_id": run_id,
            "thresholds": {"max_quotes": 10, "rate_limit_per_min": 10},
        })
        p.check("baseline: demo buyer and merchant are distinct accounts",
                buyer_uid != merchant_uid,
                {"buyer_user_id": buyer_uid, "merchant_user_id": merchant_uid})
        p.check("baseline: demo merchant owns >=1 status=1 product and another "
                "merchant product exists",
                bool(own_products) and bool(other_prod)
                and other_prod[0] != own_prod[0],
                {"own_prod_id": own_prod[0], "other_prod_id": other_prod[0],
                 "other_mer_id": other_mer_id})

        # ---------------- b) buyer publishes one legal RFQ ----------------
        buyer = Client(args.backend)
        try:
            buyer.login(args.buyer_phone, args.buyer_password)
        except SystemExit as e:
            p.check("b: buyer login", False, {"error": str(e)})
            raise StopProbe(1)

        deadline = iso_future(7)
        publish_payload = {
            "title": title_main,
            "description": "rfq-s4-quote-probe-%s-desc" % run_id,
            "quantity": str(QTY),
            "unit": UNIT,
            "quoteDeadline": deadline,
        }
        st, bd, hits = post_with_retry(p, buyer, "/rfq", publish_payload, "POST /rfq")
        data = (bd or {}).get("data") or {}
        rfq_no = data.get("rfqNo")
        created_published = bool(rfq_no)
        p.check("b: buyer publishes one legal RFQ (HTTP 200 / code 200)",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": rfq_no,
                 "rate_limit_hits": hits})
        if not rfq_no:
            raise StopProbe(1)
        p.check("b: rfqNo matches ^RFQ\\d{14}$", bool(RFQ_NO_RE.match(str(rfq_no))),
                {"rfqNo": rfq_no})

        row = db.rows(
            "SELECT rfq_id, user_id, title, quantity, unit, status, "
            "DATE_FORMAT(quote_deadline,'%%Y-%%m-%%d %%H:%%i:%%s'), max_quotes "
            "FROM t_rfq WHERE rfq_no = %s" % q(rfq_no))
        ok_row = len(row) == 1
        if ok_row:
            r = row[0]
            rfq_id = r[0]
            ok_row = (r[1] == str(buyer_uid) and r[2] == title_main
                      and dec(r[3]) == QTY and r[4] == UNIT
                      and r[5] == "PUBLISHED" and r[7] == "10")
        p.note("b: DB row for the legal publish", row[0] if row else None)
        p.check("b: DB row carries buyer user_id, qty=20, PUBLISHED, max_quotes=10",
                ok_row, {"row": row[0] if row else None,
                         "expected_user_id": buyer_uid, "expected_qty": str(QTY)})
        seq_key_backend = "rfq:" + str(rfq_no)[3:11]

        # ---------------- c) merchant visibility ----------------
        merchant = Client(args.backend)
        try:
            merchant.login(args.merchant_phone, args.merchant_password)
        except SystemExit as e:
            p.check("c: merchant login", False, {"error": str(e)})
            raise StopProbe(1)

        st, bd = merchant.call("/merchant/rfq/open")
        items = (bd or {}).get("data") or []
        mine = [i for i in items if i.get("rfqNo") == rfq_no]
        p.check("c: GET /merchant/rfq/open lists the rfqNo exactly once",
                st == 200 and (bd or {}).get("code") == 200 and len(mine) == 1,
                {"http": st, "code": (bd or {}).get("code"),
                 "matches": len(mine), "open_count": len(items)})
        open_item_text = json.dumps(mine[0] if mine else {}, ensure_ascii=False)
        open_hits = forbidden_hits(open_item_text)
        p.check("c: open list item exposes no buyer identity / competitor price",
                bool(mine) and not open_hits,
                {"forbidden_hits": open_hits,
                 "keys": sorted((mine[0] if mine else {}).keys())})
        p.check("c: open list item keeps quantity=20 and status PUBLISHED",
                bool(mine) and dec(mine[0].get("quantity")) == QTY
                and mine[0].get("status") == "PUBLISHED",
                {"item": mine[0] if mine else None})

        st, bd = merchant.call("/merchant/rfq/%s" % rfq_no)
        detail = (bd or {}).get("data") or {}
        detail_text = json.dumps(bd or {}, ensure_ascii=False)
        detail_hits = forbidden_hits(detail_text)
        p.check("c: GET /merchant/rfq/{rfqNo} returns the order (code 200)",
                st == 200 and (bd or {}).get("code") == 200
                and detail.get("rfqNo") == rfq_no and detail.get("title") == title_main,
                {"http": st, "code": (bd or {}).get("code"),
                 "rfqNo": detail.get("rfqNo"), "title": detail.get("title")})
        p.check("c: merchant detail body has NO userId/phone/contact and NO unitPrice",
                not detail_hits,
                {"forbidden_hits": detail_hits,
                 "keys": sorted(detail.keys()),
                 "snippet": detail_text[:400]})

        # ---------------- d) quote with tampered totalAmount/quantity ----------------
        valid_until = iso_future(3)
        quote_path = "/merchant/rfq/%s/quote" % rfq_no
        tampered_body = {
            "prodId": int(own_prod[0]),
            "unitPrice": str(UNIT_PRICE),
            "remark": "rfq-s4-probe-%s" % run_id,
            "validUntil": valid_until,
            "totalAmount": 1,
            "quantity": 1,
        }
        st, bd, hits = post_with_retry(p, merchant, quote_path, tampered_body,
                                       "POST " + quote_path)
        qdata = (bd or {}).get("data") or {}
        p.check("d: merchant quote accepted (HTTP 200 / code 200)",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})
        p.check("d: server ignores client totalAmount/quantity (response echoes "
                "1970.00 x 20)",
                dec(qdata.get("totalAmount")) == EXPECTED_TOTAL
                and dec(qdata.get("quantity")) == QTY
                and dec(qdata.get("unitPrice")) == UNIT_PRICE,
                {"unitPrice": qdata.get("unitPrice"),
                 "quantity": qdata.get("quantity"),
                 "totalAmount": qdata.get("totalAmount")})

        quote_rows = db.rows(
            "SELECT quote_id, mer_id, prod_id, unit_price, quantity, total_amount, status "
            "FROM t_rfq_quote WHERE rfq_id = %s AND mer_id = %s"
            % (q(rfq_id), q(mer_id)))
        ok_quote = len(quote_rows) == 1
        if ok_quote:
            r = quote_rows[0]
            quote_id = r[0]
            ok_quote = (r[1] == str(mer_id) and r[2] == str(own_prod[0])
                        and dec(r[3]) == UNIT_PRICE and dec(r[4]) == QTY
                        and dec(r[5]) == EXPECTED_TOTAL and r[6] == "SUBMITTED")
        p.note("d: DB row for the first quote", quote_rows[0] if quote_rows else None)
        p.check("d: DB total_amount = 98.50 x 20 = 1970.00, quantity = 20 (tamper "
                "attempt ignored)", ok_quote,
                {"row": quote_rows[0] if quote_rows else None,
                 "expected_total": str(EXPECTED_TOTAL)})

        rfq_status = db.rows("SELECT status FROM t_rfq WHERE rfq_id = %s"
                             % q(rfq_id))
        p.check("d: RFQ status flipped to QUOTED",
                bool(rfq_status) and rfq_status[0][0] == "QUOTED",
                {"status": rfq_status[0][0] if rfq_status else None})
        p.check("d: t_rfq_quote total == baseline + 1",
                int(db.rows("SELECT COUNT(*) FROM t_rfq_quote")[0][0]) == base_quote + 1,
                {"baseline": base_quote})

        # ---------------- e) buyer cannot quote ----------------
        st, bd = buyer.write(quote_path, tampered_body)
        p.check("e: buyer session POST quote -> body code 403 (merchant:manage missing)",
                (bd or {}).get("code") == 403,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message")})
        rows_after = db.rows(
            "SELECT COUNT(*), MIN(unit_price), MIN(total_amount) FROM t_rfq_quote "
            "WHERE rfq_id = %s" % q(rfq_id))
        p.check("e: buyer attempt wrote nothing (1 row, 98.50 / 1970.00 intact)",
                bool(rows_after) and rows_after[0][0] == "1"
                and dec(rows_after[0][1]) == UNIT_PRICE
                and dec(rows_after[0][2]) == EXPECTED_TOTAL,
                {"aggregate": rows_after[0] if rows_after else None})

        # ---------------- f) another merchant's product is rejected ----------------
        foreign_body = {
            "prodId": int(other_prod[0]),
            "unitPrice": "90.00",
            "remark": "rfq-s4-probe-foreign-%s" % run_id,
            "validUntil": valid_until,
        }
        st, bd, hits = post_with_retry(p, merchant, quote_path, foreign_body,
                                       "POST " + quote_path)
        p.check("f: quoting another merchant's product -> body code 403",
                (bd or {}).get("code") == 403,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"),
                 "other_prod_id": other_prod[0], "rate_limit_hits": hits})
        rows_after = db.rows(
            "SELECT COUNT(*), MIN(prod_id), MIN(unit_price), MIN(total_amount) "
            "FROM t_rfq_quote WHERE rfq_id = %s" % q(rfq_id))
        p.check("f: stored quote unchanged (own prod, 98.50 / 1970.00)",
                bool(rows_after) and rows_after[0][0] == "1"
                and rows_after[0][1] == str(own_prod[0])
                and dec(rows_after[0][2]) == UNIT_PRICE
                and dec(rows_after[0][3]) == EXPECTED_TOTAL,
                {"aggregate": rows_after[0] if rows_after else None})

        # ---------------- g) re-quote updates in place ----------------
        update_body = {
            "prodId": int(own_prod[0]),
            "unitPrice": str(UPDATED_PRICE),
            "remark": "rfq-s4-probe-update-%s" % run_id,
            "validUntil": valid_until,
        }
        st, bd, hits = post_with_retry(p, merchant, quote_path, update_body,
                                       "POST " + quote_path)
        p.check("g: merchant re-quote at 100.00 accepted (code 200)",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})
        rows_after = db.rows(
            "SELECT quote_id, unit_price, quantity, total_amount, status "
            "FROM t_rfq_quote WHERE rfq_id = %s AND mer_id = %s"
            % (q(rfq_id), q(mer_id)))
        ok_update = len(rows_after) == 1
        if ok_update:
            r = rows_after[0]
            ok_update = (r[0] == quote_id and dec(r[1]) == UPDATED_PRICE
                         and dec(r[2]) == QTY and dec(r[3]) == UPDATED_TOTAL
                         and r[4] == "SUBMITTED")
        p.check("g: same quote_id, total 2000.00, still exactly 1 row (UPDATE not INSERT)",
                ok_update,
                {"row": rows_after[0] if rows_after else None,
                 "quote_id_before": quote_id})
        p.check("g: t_rfq_quote total still baseline + 1",
                int(db.rows("SELECT COUNT(*) FROM t_rfq_quote")[0][0]) == base_quote + 1,
                {"baseline": base_quote})

        # ---------------- h) own quote readable; competitors cannot read it ----------------
        st, bd = merchant.call(quote_path)
        qdata = (bd or {}).get("data") or {}
        p.check("h: GET /merchant/rfq/{rfqNo}/quote returns the caller's own quote",
                st == 200 and (bd or {}).get("code") == 200
                and dec(qdata.get("unitPrice")) == UPDATED_PRICE
                and dec(qdata.get("totalAmount")) == UPDATED_TOTAL
                and qdata.get("status") == "SUBMITTED",
                {"http": st, "code": (bd or {}).get("code"),
                 "unitPrice": qdata.get("unitPrice"),
                 "totalAmount": qdata.get("totalAmount"),
                 "status": qdata.get("status")})

        anon = Client(args.backend)
        st, bd = anon.call(quote_path)
        body_code = (bd or {}).get("code")
        p.check("h: anonymous GET quote -> HTTP 401 or body code 401",
                st == 401 or body_code == 401,
                {"http": st, "code": body_code,
                 "message": (bd or {}).get("message")})

        merchant2 = None
        if args.merchant2_phone and args.merchant2_password:
            merchant2 = Client(args.backend)
            try:
                merchant2.login(args.merchant2_phone, args.merchant2_password)
                st, bd = merchant2.call(quote_path)
                p.check("h: second merchant GET quote -> body code 404 (not quoted / "
                        "no cross-merchant read)",
                        (bd or {}).get("code") == 404,
                        {"http": st, "code": (bd or {}).get("code"),
                         "message": (bd or {}).get("message")})
            except SystemExit as e:
                p.check("h: second merchant login", False, {"error": str(e)})
                merchant2 = None
        else:
            p.note("h: competitor invisibility", {
                "live_second_merchant": False,
                "reason": "only one demo merchant credential was supplied; no "
                          "endpoint exposes another merchant's quote (myQuote is "
                          "filtered by the caller's own mer_id)",
                "asserted_instead": "anonymous GET /merchant/rfq/{rfqNo}/quote -> 401",
                "unit_coverage": "RfqQuoteServiceTest (competitor quotes not exposed; "
                                 "ownership guard; update/recompute semantics)",
            })

        # ---------------- i) expired RFQ cannot be quoted ----------------
        orig_deadline_rows = db.rows(
            "SELECT DATE_FORMAT(quote_deadline,'%%Y-%%m-%%d %%H:%%i:%%s') "
            "FROM t_rfq WHERE rfq_id = %s" % q(rfq_id))
        orig_deadline = orig_deadline_rows[0][0] if orig_deadline_rows else None
        past_deadline = sql_past(1)
        db.run("UPDATE t_rfq SET quote_deadline = %s WHERE rfq_id = %s"
               % (q(past_deadline), q(rfq_id)))
        moved = db.rows(
            "SELECT DATE_FORMAT(quote_deadline,'%%Y-%%m-%%d %%H:%%i:%%s') "
            "FROM t_rfq WHERE rfq_id = %s" % q(rfq_id))
        p.check("i: SQL moved quote_deadline into the past",
                bool(moved) and moved[0][0] == past_deadline,
                {"quote_deadline": moved[0][0] if moved else None,
                 "sent_past": past_deadline})

        expired_body = {
            "prodId": int(own_prod[0]),
            "unitPrice": "50.00",
            "remark": "rfq-s4-probe-expired-%s" % run_id,
        }
        st, bd, hits = post_with_retry(p, merchant, quote_path, expired_body,
                                       "POST " + quote_path)
        p.check("i: quote after deadline -> body code 400",
                (bd or {}).get("code") == 400,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})
        rows_after = db.rows(
            "SELECT COUNT(*), MIN(unit_price), MIN(total_amount) FROM t_rfq_quote "
            "WHERE rfq_id = %s" % q(rfq_id))
        p.check("i: expired attempt left the quote untouched (100.00 / 2000.00)",
                bool(rows_after) and rows_after[0][0] == "1"
                and dec(rows_after[0][1]) == UPDATED_PRICE
                and dec(rows_after[0][2]) == UPDATED_TOTAL,
                {"aggregate": rows_after[0] if rows_after else None})

        if orig_deadline:
            db.run("UPDATE t_rfq SET quote_deadline = %s WHERE rfq_id = %s"
                   % (q(orig_deadline), q(rfq_id)))
            restored = db.rows(
                "SELECT DATE_FORMAT(quote_deadline,'%%Y-%%m-%%d %%H:%%i:%%s') "
                "FROM t_rfq WHERE rfq_id = %s" % q(rfq_id))
            p.check("i: original quote_deadline restored",
                    bool(restored) and restored[0][0] == orig_deadline,
                    {"quote_deadline": restored[0][0] if restored else None,
                     "original": orig_deadline})
    except StopProbe as e:
        p.note("probe stopped early", {"exit_code": e.code})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        p.check("probe ran to completion without unexpected error", False,
                {"error": repr(e)[:400]})
    finally:
        cleanup = p.evidence["cleanup"]
        if args.keep:
            cleanup["mode"] = "kept"
            cleanup["reason"] = "--keep"
            cleanup["rfq_nos_left_behind"] = [rfq_no] if rfq_no else []
        else:
            cleanup["mode"] = "cleanup"
            cleanup["recorded_rfq_no"] = rfq_no
            cleanup["t_rfq_baseline"] = base_rfq
            cleanup["t_rfq_quote_baseline"] = base_quote
            try:
                if rfq_no:
                    ids = db.rows("SELECT rfq_id FROM t_rfq WHERE rfq_no = %s"
                                  % q(rfq_no))
                    if ids:
                        rfq_id = ids[0][0]
                    if rfq_id:
                        # Delete ONLY rows hanging off this run's rfq_id.
                        safe_run(p, db, "DELETE FROM t_rfq_quote WHERE rfq_id = %s"
                                 % q(rfq_id), "t_rfq_quote(probe rfq_id)")
                        safe_run(p, db, "DELETE FROM t_rfq_event WHERE rfq_id = %s"
                                 % q(rfq_id), "t_rfq_event(probe rfq_id)")
                        deleted = True
                    safe_run(p, db, "DELETE FROM t_rfq WHERE rfq_no = %s AND user_id = %s"
                             % (q(rfq_no), q(buyer_uid)), "t_rfq(probe rfq_no)")
                    cleanup["deleted_scope"] = [
                        "t_rfq_quote/t_rfq_event WHERE rfq_id = this run's rfq_id",
                        "t_rfq WHERE rfq_no = this run's rfq_no AND user_id = <buyer>",
                    ]
                    cleanup["sequence_untouched"] = (
                        "t_sys_sequence rfq:<day> deliberately NOT deleted "
                        "(system numbering state, not probe data)")
                    left = db.rows("SELECT rfq_no FROM t_rfq WHERE rfq_no = %s"
                                   % q(rfq_no))
                    p.check("cleanup: the probe rfqNo is gone",
                            not left, {"left": left})
                    if rfq_id:
                        orphans = db.rows(
                            "SELECT (SELECT COUNT(*) FROM t_rfq_quote WHERE rfq_id = %s) + "
                            "(SELECT COUNT(*) FROM t_rfq_event WHERE rfq_id = %s)"
                            % (q(rfq_id), q(rfq_id)))
                        p.check("cleanup: no quote/event rows left for the probe rfq_id",
                                bool(orphans) and orphans[0][0] == "0",
                                {"orphan_count": orphans[0][0] if orphans else None,
                                 "probe_rfq_id": rfq_id})
                else:
                    cleanup["reason"] = "no probe RFQ was created"

                final_rfq = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
                final_quote = int(db.rows("SELECT COUNT(*) FROM t_rfq_quote")[0][0])
                final_event = int(db.rows("SELECT COUNT(*) FROM t_rfq_event")[0][0])
                seq_rows = db.rows("SELECT current_val FROM t_sys_sequence "
                                   "WHERE seq_key = %s" % q(seq_key_backend or "rfq:unknown"))
                cleanup["final"] = {
                    "t_rfq_total": final_rfq, "t_rfq_baseline": base_rfq,
                    "t_rfq_quote_total": final_quote,
                    "t_rfq_quote_baseline": base_quote,
                    "t_rfq_event_total": final_event,
                    "t_rfq_event_baseline": base_event,
                    "sequence_key": seq_key_backend,
                    "sequence_final_value": seq_rows[0][0] if seq_rows else "<no-row>",
                    "sequence_note": "t_sys_sequence row kept on purpose "
                                     "(system numbering state; never rolled back)",
                }
                p.check("cleanup: t_rfq total back to baseline",
                        final_rfq == base_rfq,
                        {"baseline": base_rfq, "final": final_rfq})
                p.check("cleanup: t_rfq_quote total back to baseline",
                        final_quote == base_quote,
                        {"baseline": base_quote, "final": final_quote})
                p.check("cleanup: t_rfq_event total back to baseline",
                        final_event == base_event,
                        {"baseline": base_event, "final": final_event})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("cleanup: final state verification failed", False,
                        {"error": str(e)[:300], "deleted": deleted})

        p.note("scope", {
            "containers_restarted": False,
            "secrets_printed": False,
            "touched_rows": "only the probe RFQ + its quote/event rows (deleted in cleanup)",
            "sequence_untouched": True,
            "unit_side_coverage": "RfqQuoteServiceTest covers totals, ownership guard, "
                                  "update-in-place, closed RFQ and competitor invisibility",
        })

    code = 1 if p.failed else 0
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
