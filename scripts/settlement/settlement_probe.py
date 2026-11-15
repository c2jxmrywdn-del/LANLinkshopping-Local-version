#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merchant settlement slice: one-shot, self-cleaning, live-stack acceptance probe.

Proves what the settlement ledger actually does on the running API (no rebuild, no
container restart, no browser):

  1. precondition: admin login (admin:all) + merchant login (merchant:manage); the
     single completed order (order_status=2, pay_status=1) exists; baseline of the
     three settlement tables, t_merchant.commission_rate and the order's refund fields.
  2. sync records EARN: POST /admin/settlement/sync -> code 200; DB gets exactly one
     EARN row for the order; sum(EARN.gross) == order.total_amount (single line ->
     the tail-difference absorber takes the whole total); commission == round(gross*7/100,2);
     net == gross - commission; status PENDING; available_at set in the future.
  3. idempotent: a second sync does not add an EARN row (uk_earning_line).
  4. holding promote: SQL pulls available_at into the past -> sync -> EARN AVAILABLE.
  5. generate batch: POST /admin/settlement/generate?merId=1 -> DRAFT batch, item_count
     == AVAILABLE EARN count, sum(item.net) == batch.total_net, EARN -> SETTLED.
  6. state machine: approve -> APPROVED; approve again -> code 400; pay without voucher
     -> 400; pay with voucher -> PAID (+ pay_voucher persisted).
  7. refund reversal: partial refund 1000 -> REVERSE row (sum == 1000, net negative,
     refund_seq=1); full refund 3550 -> sum(REVERSE.gross) == sum(EARN.gross). The
     EARN->REVERSED transition is deliberately skipped for SETTLED rows (the ledger
     must not rewrite money already frozen into a batch), so that guard is recorded as
     a check, and the transition itself is then proven on the same EARN line moved back
     to AVAILABLE with the batch rows removed (the documented "state migration runs
     independently of delta" path).
  8. commission override: PUT /admin/settlement/commission/1 5.00 -> DB 5.00; null ->
     back to NULL (falls back to the global 7.00); 120 -> code 400 (and rate unchanged).
  9. gates: anonymous GET /merchant/settlement/summary -> 401; merchant session -> 200;
     merchant session POST /admin/settlement/sync -> 403 (no admin:all).
 10. cleanup: delete this run's rows from the three settlement tables (all began at 0),
     restore t_merchant.commission_rate=NULL for mer_id=1 and the order's
     refund_amount=NULL / refund_status='none'; verify every one of them is back.

Rate limit: POST /admin/settlement/* is throttled at 20 req / 60 s per client IP
(AuthRateLimitInterceptor). This probe issues ~11 such writes, each through a
429-tolerant helper that waits out the window and retries; a small pause is inserted
between admin writes to stay well inside the budget.

Credentials are arguments and are never printed. DB access runs through `docker exec`
using the container's own environment variables, so no password appears in this
script, on the command line, or in the output.

Usage:
  python scripts/settlement/settlement_probe.py \\
      --admin-phone 13800000000 --admin-password '***' \\
      --merchant-phone 13700000002 --merchant-password '***' \\
      [--backend http://127.0.0.1:8080/api] [--mysql-container shopping-mysql-1] \\
      [--keep] [--out reports/settlement-probe-evidence.json]

Exit codes: 0 all checks passed, 1 check failure, 2 precondition missing
(account/order), 3 docker or DB unreachable.

Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the UTF-8
evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from http.cookiejar import CookieJar

ORDER_NO = "20261022170813519896"
EXPECTED_TOTAL = Decimal("3550.00")
DEFAULT_RATE = Decimal("7.00")
PARTIAL_REFUND = Decimal("1000.00")
OVERRIDE_RATE = Decimal("5.00")
MER_ID = 1
PAY_VOUCHER = "TEST-V-001"
TABLES = ("t_merchant_earning", "t_merchant_settlement", "t_merchant_settlement_item")


class Client:
    """Cookie-jar session with CSRF header (same shape as rfq_accept_probe)."""

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

    def write(self, path, body=None, csrf=True, method="POST"):
        headers = {"X-CSRF-TOKEN": self.csrf} if csrf else {}
        return self.call(path, method, body, headers)


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

    def one(self, stmt: str):
        rows = self.rows(stmt)
        return rows[0] if rows else None

    def scalar(self, stmt: str, default="0"):
        row = self.one(stmt)
        return row[0] if row else default


class Probe:
    def __init__(self, out_path: str, meta: dict):
        self.out_path = out_path
        self.lock = threading.RLock()
        self.evidence = {
            "probe": "settlement-live-acceptance",
            "purpose": "Merchant settlement ledger on the running stack: sync records "
                       "EARN (proportional allocation + commission snapshot), holding "
                       "period promotion, idempotency, batch generate, DRAFT->APPROVED->"
                       "PAID state machine, refund reversal identity, commission "
                       "override, merchant/admin gates, full self-cleanup.",
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


def money(value) -> Decimal:
    return dec(value).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def is_null(value) -> bool:
    return value is None or str(value).strip() in ("NULL", "None", "")


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


class StopProbe(Exception):
    """Abort the main flow but still run the cleanup block and write evidence."""

    def __init__(self, code: int):
        super().__init__("probe stopped")
        self.code = code


def login_client(backend: str, phone: str, password: str, p: Probe,
                 label: str) -> Client:
    """Login with 429 tolerance (POST /auth/login is 8 req / 120 s per IP)."""
    client = Client(backend)
    last = ""
    for attempt in range(1, 5):
        try:
            client.login(phone, password)
            p.check(label, True, {"http": 200, "attempt": attempt})
            return client
        except SystemExit as e:
            last = str(e)
            if "HTTP=429" in last:
                wait = int(120 - (time.time() % 120)) + 2
                p.note("%s hit the login throttle, waiting %ss" % (label, wait),
                       {"attempt": attempt, "wait_s": wait})
                print("[WAIT] login 429 on %s, sleeping %ss" % (label, wait))
                time.sleep(wait)
                client = Client(backend)
                continue
            break
    p.check(label, False, {"error": last})
    raise StopProbe(2)


def admin_call(p: Probe, admin: Client, path: str, method: str, body, label: str,
               attempts: int = 6):
    """Admin write with 429 tolerance: record the throttle, wait, retry."""
    hits = []
    st, bd = -1, None
    for attempt in range(1, attempts + 1):
        st, bd = admin.write(path, body, method=method)
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
    time.sleep(0.3)  # stay well inside 20 req / 60 s
    return st, bd, hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--admin-phone", required=True)
    ap.add_argument("--admin-password", required=True)
    ap.add_argument("--merchant-phone", required=True)
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true",
                    help="skip cleanup (leaves ledger rows and overrides behind)")
    ap.add_argument("--out", default="reports/settlement-probe-evidence.json")
    args = ap.parse_args()

    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "admin_suffix": args.admin_phone[-4:],
        "merchant_suffix": args.merchant_phone[-4:],
        "run_id": run_id,
        "order_no": ORDER_NO,
        "expected_total": str(EXPECTED_TOTAL),
        "default_rate": str(DEFAULT_RATE),
        "partial_refund": str(PARTIAL_REFUND),
        "override_rate": str(OVERRIDE_RATE),
        "mer_id": MER_ID,
        "rate_limit": "POST /admin/settlement/* -> 20 req / 60 s per client IP",
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

    # ---- shared state (also read by the finally-block cleanup) ----
    base_counts = {}
    base_max_ids = {}           # AUTO_INCREMENT watermark per table (scoped deletes)
    base_rate = None            # raw text of t_merchant.commission_rate (may be 'NULL')
    base_refund_amount = None
    base_refund_status = None
    order_row = None
    settle_id = None
    settle_ids = []             # every batch this run created (for scoped cleanup)
    admin = None
    merchant = None
    stop_code = 0

    def table_count(name: str) -> int:
        return int(db.scalar("SELECT COUNT(*) FROM %s" % name))

    def earning_rows(order_no: str = ORDER_NO):
        return db.rows(
            "SELECT earning_id, mer_id, item_id, type, refund_seq, gross_amount, "
            "commission_rate, commission_amount, net_amount, status, "
            "(available_at IS NOT NULL), (available_at > NOW()), "
            "(ref_earning_id IS NULL) "
            "FROM t_merchant_earning WHERE order_no = %s ORDER BY earning_id"
            % q(order_no))

    def earn_status_counts(order_no: str = ORDER_NO) -> dict:
        rows = db.rows(
            "SELECT status, COUNT(*) FROM t_merchant_earning WHERE order_no = %s "
            "AND type='EARN' GROUP BY status" % q(order_no))
        return {r[0]: int(r[1]) for r in rows}

    def sum_where(where: str, order_no: str = ORDER_NO) -> Decimal:
        return money(db.scalar(
            "SELECT COALESCE(SUM(gross_amount),0) FROM t_merchant_earning "
            "WHERE order_no = %s AND %s" % (q(order_no), where)))

    try:
        # ---------------- 1) precondition + baseline ----------------
        admin = login_client(args.backend, args.admin_phone, args.admin_password,
                             p, "1: admin login (role admin / admin:all)")
        merchant = login_client(args.backend, args.merchant_phone,
                                args.merchant_password, p,
                                "1: merchant login (merchant:manage)")

        for name in TABLES:
            base_counts[name] = table_count(name)
        # Id watermark per table: cleanup deletes only rows created after it.
        for name, pk in (("t_merchant_earning", "earning_id"),
                         ("t_merchant_settlement", "settle_id"),
                         ("t_merchant_settlement_item", "id")):
            base_max_ids[name] = int(db.scalar(
                "SELECT COALESCE(MAX(%s),0) FROM %s" % (pk, name)))
        rate_row = db.one("SELECT commission_rate FROM t_merchant WHERE mer_id = %d"
                          % MER_ID)
        base_rate = rate_row[0] if rate_row else None
        order_row = db.one(
            "SELECT order_no, user_id, total_amount, refund_amount, refund_status, "
            "order_status, pay_status FROM t_order WHERE order_no = %s" % q(ORDER_NO))
        if order_row is None:
            p.note("precondition", {"reason": "completed order not found",
                                    "order_no": ORDER_NO})
            p.check("1: the completed order exists (order_status=2 pay_status=1)",
                    False, {"order_no": ORDER_NO})
            raise StopProbe(2)
        base_refund_amount = order_row[3]
        base_refund_status = order_row[4]

        item_rows = db.rows(
            "SELECT item_id, prod_id, subtotal FROM t_order_item WHERE order_no = %s "
            "ORDER BY item_id" % q(ORDER_NO))
        completed_orders = int(db.scalar(
            "SELECT COUNT(*) FROM t_order WHERE order_status=2 AND pay_status=1"))

        p.note("baseline", {
            "settlement_table_counts": base_counts,
            "mer_commission_rate": base_rate,
            "order": {"order_no": order_row[0], "user_id": order_row[1],
                      "total_amount": order_row[2], "refund_amount": base_refund_amount,
                      "refund_status": base_refund_status,
                      "order_status": order_row[5], "pay_status": order_row[6]},
            "order_items": item_rows,
            "completed_order_count": completed_orders,
            "run_id": run_id,
        })

        p.check("1: the completed order exists (order_status=2 pay_status=1) with "
                "total_amount=3550.00",
                order_row[5] == "2" and order_row[6] == "1"
                and money(order_row[2]) == EXPECTED_TOTAL,
                {"row": order_row, "expected_total": str(EXPECTED_TOTAL),
                 "completed_order_count": completed_orders})
        p.check("1: baseline recorded - three settlement tables at 0 rows",
                all(v == 0 for v in base_counts.values()), base_counts)
        p.check("1: baseline recorded - t_merchant.commission_rate for mer_id=1 is NULL "
                "(falls back to the global 7.00)",
                is_null(base_rate), {"mer_id": MER_ID, "commission_rate": base_rate})

        # ---------------- 2) sync records EARN ----------------
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[first]")
        p.check("2: POST /admin/settlement/sync -> HTTP 200 / code 200",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})

        rows = earning_rows()
        earn = [r for r in rows if r[3] == "EARN"]
        e = earn[0] if len(earn) == 1 else None
        gross = money(e[5]) if e else Decimal("0")
        commission = money(e[7]) if e else Decimal("0")
        net = money(e[8]) if e else Decimal("0")
        expected_commission = money(gross * DEFAULT_RATE / Decimal("100"))
        p.note("2: EARN row of the order", e)

        p.check("2: exactly one EARN row recorded for the order (one order line)",
                len(earn) == 1 and len(rows) == 1,
                {"earning_rows": len(rows), "earn_rows": len(earn),
                 "types": [r[3] for r in rows], "row": e})
        p.check("2: sum(EARN.gross) == order.total_amount == 3550.00 "
                "(single line absorbs the allocation)",
                money(sum_where("type='EARN'")) == EXPECTED_TOTAL
                and gross == EXPECTED_TOTAL,
                {"sum_earn_gross": str(sum_where("type='EARN'")),
                 "earn_gross": str(gross), "expected": str(EXPECTED_TOTAL)})
        p.check("2: commission == round(gross*7/100, 2) and rate snapshot == 7.00",
                e is not None and commission == expected_commission
                and money(e[6]) == DEFAULT_RATE,
                {"commission": str(commission),
                 "expected_commission": str(expected_commission),
                 "rate_snapshot": e[6] if e else None})
        p.check("2: net == gross - commission",
                net == gross - commission,
                {"gross": str(gross), "commission": str(commission), "net": str(net),
                 "expected_net": str(gross - commission)})
        p.check("2: EARN status PENDING with available_at set in the future "
                "(holding period)",
                e is not None and e[9] == "PENDING" and e[10] == "1" and e[11] == "1",
                {"status": e[9] if e else None,
                 "available_at_set": e[10] if e else None,
                 "available_at_future": e[11] if e else None})

        # ---------------- 3) idempotent sync ----------------
        earn_before = len([r for r in earning_rows() if r[3] == "EARN"])
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[idempotent]")
        earn_after = len([r for r in earning_rows() if r[3] == "EARN"])
        p.check("3: a second sync is idempotent - EARN row count unchanged "
                "(uk_earning_line)",
                st == 200 and (bd or {}).get("code") == 200
                and earn_after == earn_before == 1,
                {"http": st, "code": (bd or {}).get("code"),
                 "earn_before": earn_before, "earn_after": earn_after,
                 "rate_limit_hits": hits})

        # ---------------- 4) holding promote ----------------
        db.run("UPDATE t_merchant_earning SET available_at = NOW() - INTERVAL 1 MINUTE "
               "WHERE type='EARN' AND order_no = %s" % q(ORDER_NO))
        pulled = db.scalar("SELECT (available_at < NOW()) FROM t_merchant_earning "
                           "WHERE type='EARN' AND order_no = %s" % q(ORDER_NO))
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[promote]")
        counts = earn_status_counts()
        p.check("4: SQL pulled available_at into the past, sync promoted EARN to "
                "AVAILABLE",
                pulled == "1" and st == 200 and (bd or {}).get("code") == 200
                and counts.get("AVAILABLE") == 1 and counts.get("PENDING", 0) == 0,
                {"available_at_pulled": pulled, "http": st,
                 "code": (bd or {}).get("code"), "status_counts": counts,
                 "rate_limit_hits": hits})

        # ---------------- 5) generate batch ----------------
        available_earn = int(db.scalar(
            "SELECT COUNT(*) FROM t_merchant_earning WHERE mer_id=%d AND type='EARN' "
            "AND status='AVAILABLE'" % MER_ID))
        st, bd, hits = admin_call(p, admin, "/admin/settlement/generate?merId=%d" % MER_ID,
                                  "POST", None, "POST /admin/settlement/generate")
        data = (bd or {}).get("data") or {}
        settle_id = data.get("settleId")
        if settle_id:
            settle_ids.append(settle_id)
        p.note("5: generate response", data)
        batch_rows = db.rows(
            "SELECT settle_id, settle_no, total_net, commission_total, item_count, "
            "status FROM t_merchant_settlement WHERE mer_id=%d" % MER_ID)
        b = batch_rows[0] if len(batch_rows) == 1 else None
        item_rows_db = db.rows(
            "SELECT earning_id, type, net_amount FROM t_merchant_settlement_item "
            "WHERE settle_id = %s" % q(settle_id)) if settle_id else []
        sum_item_net = money(sum((money(r[2]) for r in item_rows_db), Decimal("0")))
        settled_earn = int(db.scalar(
            "SELECT COUNT(*) FROM t_merchant_earning WHERE mer_id=%d AND type='EARN' "
            "AND status='SETTLED'" % MER_ID))

        p.check("5: POST /admin/settlement/generate?merId=1 -> code 200 with a settleId",
                st == 200 and (bd or {}).get("code") == 200 and bool(settle_id),
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "settleId": settle_id,
                 "rate_limit_hits": hits})
        p.check("5: DB has exactly one DRAFT batch for mer_id=1",
                b is not None and b[5] == "DRAFT",
                {"batch_rows": len(batch_rows), "row": b})
        p.check("5: batch.item_count == AVAILABLE EARN count and every EARN is SETTLED",
                b is not None and int(b[4]) == available_earn == settled_earn
                and settled_earn == 1,
                {"item_count": b[4] if b else None,
                 "available_earn_before": available_earn,
                 "settled_earn": settled_earn,
                 "batch_item_rows": len(item_rows_db)})
        p.check("5: sum(item.net) == batch.total_net",
                b is not None and sum_item_net == money(b[2]),
                {"sum_item_net": str(sum_item_net),
                 "batch_total_net": b[2] if b else None,
                 "items": item_rows_db})

        if not settle_id:
            raise StopProbe(1)

        # ---------------- 6) state machine ----------------
        st, bd, hits = admin_call(p, admin, "/admin/settlement/%s/approve" % settle_id,
                                  "POST", None, "POST approve")
        approve_status = db.scalar("SELECT status FROM t_merchant_settlement "
                                   "WHERE settle_id = %s" % q(settle_id))
        p.check("6: approve -> DRAFT to APPROVED (code 200 + DB)",
                st == 200 and (bd or {}).get("code") == 200
                and approve_status == "APPROVED",
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "db_status": approve_status,
                 "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/settlement/%s/approve" % settle_id,
                                  "POST", None, "POST approve[again]")
        p.check("6: approve again -> body code 400 (no silent success)",
                (bd or {}).get("code") == 400
                and db.scalar("SELECT status FROM t_merchant_settlement "
                              "WHERE settle_id = %s" % q(settle_id)) == "APPROVED",
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/settlement/%s/pay" % settle_id,
                                  "POST", {"payVoucher": ""}, "POST pay[empty voucher]")
        p.check("6: pay with an empty voucher -> body code 400",
                (bd or {}).get("code") == 400
                and db.scalar("SELECT status FROM t_merchant_settlement "
                              "WHERE settle_id = %s" % q(settle_id)) == "APPROVED",
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/settlement/%s/pay" % settle_id,
                                  "POST", {"payVoucher": PAY_VOUCHER},
                                  "POST pay[with voucher]")
        pay_row = db.one("SELECT status, pay_voucher FROM t_merchant_settlement "
                         "WHERE settle_id = %s" % q(settle_id))
        p.check("6: pay with voucher TEST-V-001 -> APPROVED to PAID (voucher persisted)",
                st == 200 and (bd or {}).get("code") == 200 and pay_row is not None
                and pay_row[0] == "PAID" and pay_row[1] == PAY_VOUCHER,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "db_row": pay_row,
                 "rate_limit_hits": hits})

        # ---------------- 7) refund reversal ----------------
        db.run("UPDATE t_order SET refund_amount = %s, refund_status='partial' "
               "WHERE order_no = %s" % (str(PARTIAL_REFUND), q(ORDER_NO)))
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[partial refund]")
        rev_rows = [r for r in earning_rows() if r[3] == "REVERSE"]
        r1 = rev_rows[0] if len(rev_rows) == 1 else None
        p.note("7: REVERSE row after a 1000.00 partial refund", r1)
        p.check("7: partial refund 1000.00 -> exactly one REVERSE row, "
                "sum(REVERSE.gross) == 1000.00, net negative, refund_seq=1",
                st == 200 and (bd or {}).get("code") == 200
                and len(rev_rows) == 1
                and money(sum_where("type='REVERSE'")) == PARTIAL_REFUND
                and r1 is not None and money(r1[5]) == PARTIAL_REFUND
                and money(r1[8]) < 0 and r1[4] == "1",
                {"http": st, "code": (bd or {}).get("code"),
                 "reverse_rows": len(rev_rows),
                 "sum_reverse_gross": str(sum_where("type='REVERSE'")),
                 "row": r1, "rate_limit_hits": hits})

        db.run("UPDATE t_order SET refund_amount = %s, refund_status='success' "
               "WHERE order_no = %s" % (str(EXPECTED_TOTAL), q(ORDER_NO)))
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[full refund]")
        sum_earn = sum_where("type='EARN'")
        sum_rev = sum_where("type='REVERSE'")
        counts = earn_status_counts()
        p.check("7: full refund 3550.00 -> sum(REVERSE.gross) == sum(EARN.gross) "
                "(full-refund identity, no over-reversal)",
                st == 200 and (bd or {}).get("code") == 200
                and sum_rev == sum_earn == EXPECTED_TOTAL,
                {"http": st, "code": (bd or {}).get("code"),
                 "sum_reverse_gross": str(sum_rev), "sum_earn_gross": str(sum_earn),
                 "reverse_rows": len([r for r in earning_rows() if r[3] == "REVERSE"]),
                 "rate_limit_hits": hits})
        p.check("7: EARN already SETTLED stays SETTLED on full refund - the ledger "
                "does not rewrite money frozen into a paid batch (guard, by design)",
                counts.get("SETTLED") == 1 and counts.get("REVERSED", 0) == 0,
                {"status_counts": counts,
                 "note": "SettlementSyncService.reverseRefunds restricts the "
                         "EARN->REVERSED update to status IN ('PENDING','AVAILABLE')"})

        # Prove the EARN->REVERSED transition itself: drop this run's batch rows and
        # put the line back to AVAILABLE (the documented "state migration runs
        # independently of delta" path), then sync again with the same full refund.
        safe_run(p, db, "DELETE FROM t_merchant_settlement_item WHERE settle_id = %s"
                 % q(settle_id), "t_merchant_settlement_item(reverse-guard setup)")
        safe_run(p, db, "DELETE FROM t_merchant_settlement WHERE settle_id = %s"
                 % q(settle_id), "t_merchant_settlement(reverse-guard setup)")
        db.run("UPDATE t_merchant_earning SET status='AVAILABLE' WHERE order_no = %s "
               "AND type='EARN'" % q(ORDER_NO))
        rev_before = len([r for r in earning_rows() if r[3] == "REVERSE"])
        st, bd, hits = admin_call(p, admin, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[reversed transition]")
        counts = earn_status_counts()
        rev_after = len([r for r in earning_rows() if r[3] == "REVERSE"])
        p.check("7: EARN AVAILABLE + full refund already reversed -> sync flips EARN to "
                "REVERSED with no extra REVERSE row (delta converges to 0)",
                st == 200 and (bd or {}).get("code") == 200
                and counts.get("REVERSED") == 1
                and rev_after == rev_before == 2,
                {"http": st, "code": (bd or {}).get("code"),
                 "status_counts": counts, "reverse_rows_before": rev_before,
                 "reverse_rows_after": rev_after, "rate_limit_hits": hits})

        # ---------------- 8) commission override ----------------
        st, bd, hits = admin_call(p, admin, "/admin/settlement/commission/%d" % MER_ID,
                                  "PUT", {"commissionRate": str(OVERRIDE_RATE)},
                                  "PUT commission[5.00]")
        rate_now = db.scalar("SELECT commission_rate FROM t_merchant WHERE mer_id=%d"
                             % MER_ID)
        p.check("8: PUT commission 5.00 -> code 200 and DB commission_rate = 5.00",
                st == 200 and (bd or {}).get("code") == 200
                and not is_null(rate_now) and money(rate_now) == OVERRIDE_RATE,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "db_commission_rate": rate_now,
                 "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/settlement/commission/%d" % MER_ID,
                                  "PUT", {"commissionRate": None},
                                  "PUT commission[null]")
        rate_now = db.scalar("SELECT commission_rate FROM t_merchant WHERE mer_id=%d"
                             % MER_ID)
        p.check("8: PUT commission null -> cleared back to NULL (falls back to 7.00)",
                st == 200 and (bd or {}).get("code") == 200 and is_null(rate_now),
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "db_commission_rate": rate_now,
                 "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/settlement/commission/%d" % MER_ID,
                                  "PUT", {"commissionRate": 120},
                                  "PUT commission[120]")
        rate_now = db.scalar("SELECT commission_rate FROM t_merchant WHERE mer_id=%d"
                             % MER_ID)
        p.check("8: PUT commission 120 -> body code 400 and the rate stays NULL",
                (bd or {}).get("code") == 400 and is_null(rate_now),
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "db_commission_rate": rate_now,
                 "rate_limit_hits": hits})

        # ---------------- 9) gates ----------------
        anon = Client(args.backend)
        st, bd = anon.call("/merchant/settlement/summary")
        p.check("9: anonymous GET /merchant/settlement/summary -> body code 401",
                (bd or {}).get("code") == 401,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message")})

        st, bd = merchant.call("/merchant/settlement/summary")
        sdata = (bd or {}).get("data") or {}
        p.check("9: merchant session GET /merchant/settlement/summary -> code 200 with "
                "the four pools",
                st == 200 and (bd or {}).get("code") == 200
                and all(k in sdata for k in ("pending", "available", "settled",
                                             "totalCommission")),
                {"http": st, "code": (bd or {}).get("code"), "data": sdata})

        st, bd, hits = admin_call(p, merchant, "/admin/settlement/sync", "POST", None,
                                  "POST /admin/settlement/sync[merchant session]")
        p.check("9: merchant session POST /admin/settlement/sync -> body code 403 "
                "(no admin:all)",
                (bd or {}).get("code") == 403,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rate_limit_hits": hits})

        st, bd = admin.call("/admin/settlement/list?current=1&size=10&merId=%d" % MER_ID)
        p.check("9: admin session GET /admin/settlement/list -> code 200 (admin:all "
                "reads the ops list)",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "total": ((bd or {}).get("data") or {}).get("total")})

    except StopProbe as e:
        stop_code = e.code
        p.note("probe stopped early", {"exit_code": e.code})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        p.check("probe ran to completion without unexpected error", False,
                {"error": repr(e)[:400]})
    finally:
        cleanup = p.evidence["cleanup"]
        cleanup["baselines"] = {
            "settlement_table_counts": base_counts,
            "settlement_table_id_watermarks": base_max_ids,
            "mer_commission_rate": base_rate,
            "order_refund_amount": base_refund_amount,
            "order_refund_status": base_refund_status,
        }
        cleanup["recorded_settle_ids"] = list(settle_ids)
        if args.keep:
            cleanup["mode"] = "kept"
            cleanup["reason"] = "--keep"
        else:
            cleanup["mode"] = "cleanup"
            try:
                # 1) delete this run's ledger rows. Scoped by the AUTO_INCREMENT
                #    watermark recorded before the run, so nothing that existed
                #    beforehand can be touched even if the table was not empty.
                for name, pk in (("t_merchant_settlement_item", "id"),
                                 ("t_merchant_settlement", "settle_id"),
                                 ("t_merchant_earning", "earning_id")):
                    safe_run(p, db, "DELETE FROM %s WHERE %s > %d"
                             % (name, pk, base_max_ids.get(name, 0)), name)

                # 2) restore t_merchant.commission_rate for mer_id=1
                if is_null(base_rate):
                    safe_run(p, db, "UPDATE t_merchant SET commission_rate = NULL "
                                    "WHERE mer_id = %d" % MER_ID,
                             "t_merchant.commission_rate -> NULL")
                else:
                    safe_run(p, db, "UPDATE t_merchant SET commission_rate = %s "
                                    "WHERE mer_id = %d" % (str(base_rate), MER_ID),
                             "t_merchant.commission_rate -> baseline")

                # 3) restore the order's refund fields
                refund_amt_sql = ("NULL" if is_null(base_refund_amount)
                                  else str(base_refund_amount))
                refund_st_sql = ("NULL" if is_null(base_refund_status)
                                 else q(base_refund_status))
                safe_run(p, db,
                         "UPDATE t_order SET refund_amount = %s, refund_status = %s "
                         "WHERE order_no = %s"
                         % (refund_amt_sql, refund_st_sql, q(ORDER_NO)),
                         "t_order refund fields -> baseline")

                # 4) verify back to baseline
                final_counts = {name: table_count(name) for name in TABLES}
                final_rate = db.scalar("SELECT commission_rate FROM t_merchant "
                                       "WHERE mer_id=%d" % MER_ID)
                final_order = db.one(
                    "SELECT refund_amount, refund_status FROM t_order "
                    "WHERE order_no = %s" % q(ORDER_NO))
                final_refund_amount = final_order[0] if final_order else None
                final_refund_status = final_order[1] if final_order else None

                final = {
                    "settlement_table_counts": final_counts,
                    "settlement_table_baseline": base_counts,
                    "mer_commission_rate": final_rate,
                    "mer_commission_rate_baseline": base_rate,
                    "order_refund_amount": final_refund_amount,
                    "order_refund_amount_baseline": base_refund_amount,
                    "order_refund_status": final_refund_status,
                    "order_refund_status_baseline": base_refund_status,
                }
                cleanup["final"] = final
                p.check("10: three settlement tables back to baseline (0 rows)",
                        final_counts == base_counts
                        and all(v == 0 for v in final_counts.values()),
                        {"baseline": base_counts, "final": final_counts})
                p.check("10: t_merchant.commission_rate for mer_id=1 back to baseline "
                        "(NULL)",
                        (is_null(final_rate) and is_null(base_rate))
                        or final_rate == base_rate,
                        {"baseline": base_rate, "final": final_rate})
                p.check("10: order refund_amount/refund_status back to baseline "
                        "(NULL / none)",
                        (is_null(final_refund_amount)
                         and is_null(base_refund_amount))
                        and final_refund_status == base_refund_status,
                        {"refund_amount": {"baseline": base_refund_amount,
                                           "final": final_refund_amount},
                         "refund_status": {"baseline": base_refund_status,
                                           "final": final_refund_status}})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("10: cleanup verification failed", False,
                        {"error": str(e)[:300]})

        p.note("scope", {
            "image_rebuilt": False,
            "containers_restarted": False,
            "secrets_printed": False,
            "java_modified": False,
            "touched_rows": "t_merchant_earning / t_merchant_settlement / "
                            "t_merchant_settlement_item (all deleted), "
                            "t_merchant.commission_rate for mer_id=1 (restored), "
                            "t_order refund_amount/refund_status of the probe order "
                            "(restored)",
            "other_orders_untouched": True,
            "audit_note": "the 403 gate check writes one ACCESS_DENIED row into the "
                          "audit log; it is a legitimate audit trail and is kept",
            "auto_increment_note": "AUTO_INCREMENT counters on the three tables are "
                                   "not reset (row data is gone, numbering state kept)",
        })

    code = 1 if p.failed else stop_code
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
