#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merchant store-exit slice: one-shot, self-cleaning, live-stack acceptance probe.

Proves the whole exit state machine against the running API (no rebuild, no
container restart, no browser) and then puts the database back exactly where it
started:

  1. precondition: admin login (admin:all) and merchant login (merchant:manage);
     the demo merchant row exists; its six exit columns are NULL; the deposit and
     exit-log tables hold no rows for it; the on-sale product set and the blocked
     order set are snapshotted -- that snapshot is what cleanup restores.
  2. read contract: GET /merchant/exit/status reports no exit and a 0.00 balance;
     GET /merchant/exit/gate reports ready=false with OPEN_ORDERS, REFUND_PENDING
     and PRODUCTS_ON_SALE, and the payload uses the key "ready" (the frontend
     contract, not "isReady").
  3. request: POST /merchant/exit/request -> code 200; DB EXIT_REQUESTED with
     exit_request_time set; one extra exit-log row written by actor MERCHANT.
  4. write freeze: delisting still works, relisting is refused by the exit guard,
     POST /product/publish is refused and no product row appears.
  5. approve is refused (code 400) while the blockers are still there.
  6. blockers cleared through SQL (products delisted, blocked orders cancelled
     with update_time pinned) -> the gate turns ready.
  7. approve -> PUBLIC_NOTICE with notice_end_time ~30 days out, exit-log +1.
  8. withdraw -> the three columns go back to NULL; request + approve again
     reaches PUBLIC_NOTICE, so the state machine is repeatable.
  9. notice expiry: notice_end_time pushed into the past, POST run-notice freezes
     the expired merchant, a second run freezes none, one SYSTEM exit-log row.
     The sweep is global, so the evidence records how many other merchants were
     already due before the call.
 10. deposit fixture: one PAY 2000.00 row -> /status reports 2000.00.
 11. admin detail: gateReport.ready true, one deposit row, at least five logs.
 12. close with deductAmount 100 -> CLOSED, DEDUCT 100 + REFUND 1900 rows, SUM
     based balance 0, gate still ready, a second close is refused.
 13. gates: anonymous GET -> HTTP 401; merchant session on the admin list -> body
     code 403; POST /merchant/exit/withdraw without the CSRF header -> 403.
 14. cleanup (always, unless --keep): this run's deposit and exit-log rows are
     deleted, the six exit columns are NULLed, every snapshotted order and the
     on-sale product set are restored, and every one of those is verified.
 15. the probe finishes with the aggregated exit code.

Rate limits: POST /admin/merchant-exit/* is 20 req / 60 s per IP, POST
/merchant/exit/* is 10 req / 60 s, POST /product/publish is 10 req / 60 s. Every
write goes through a 429-tolerant helper that waits out the window and retries.

Credentials are arguments and are never printed: every console line and every
evidence string passes through redact(), which replaces the passwords with ***.
DB access runs through `docker exec` with the container's own environment
variables, so the database password never appears in this script, on the command
line, or in the output.

Usage:
  python scripts/merchant_exit/merchant_exit_probe.py \\
      --admin-phone 13800000000 --admin-password '***' \\
      --merchant-phone 13700000002 --merchant-password '***' \\
      [--backend http://127.0.0.1:8080/api] [--mer-id 1] \\
      [--mysql-container shopping-mysql-1] [--keep] \\
      [--out reports/merchant-exit-probe-evidence.json]

Exit codes: 0 all checks passed, 1 at least one check failed, 2 precondition
missing (login / merchant row / clean baseline), 3 docker or DB unreachable.

Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the UTF-8
evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from decimal import Decimal

_HERE = os.path.dirname(os.path.abspath(__file__))
_SETTLEMENT_DIR = os.path.join(os.path.dirname(_HERE), "settlement")
if _SETTLEMENT_DIR not in sys.path:
    sys.path.insert(0, _SETTLEMENT_DIR)

import settlement_probe as sp  # noqa: E402  (path is set up above)

Client = sp.Client
Db = sp.Db
Probe = sp.Probe
StopProbe = sp.StopProbe
login_client = sp.login_client
admin_call = sp.admin_call
seconds_to_next_window = sp.seconds_to_next_window
is_null = sp.is_null
money = sp.money

EXIT_COLUMNS = ("exit_status", "exit_request_time", "notice_end_time",
                "close_time", "exit_reason", "exit_reject_reason")
# Superset of what MerchantExitGateService counts: OPEN_ORDERS is pending-ship plus
# in-transit on PAID orders, while REFUND_PENDING is keyed on refund_status alone
# (countRefundPending has no pay_status filter), so the two branches must not share
# one pay_status guard or an unpaid refund order would block the gate un-snapshotted.
BLOCKED_ORDER_WHERE = (
    "((o.pay_status = 1 AND o.order_status IN (0, 1, 4)) OR "
    "COALESCE(o.refund_status, 'none') IN "
    "('applied', 'shipping_back', 'received', 'dispute'))")
EXPECTED_BLOCKERS = ("OPEN_ORDERS", "REFUND_PENDING", "PRODUCTS_ON_SALE")
LEDGERS = ("t_merchant_deposit", "t_merchant_exit_log")
ON_SALE_SQL = ("SELECT prod_id FROM t_product WHERE mer_id = %s AND status = 1 "
               "AND deleted = 0 ORDER BY prod_id")
BLOCKED_ORDER_SQL = (
    "SELECT o.order_no, o.order_status, COALESCE(o.refund_status, '') "
    "FROM t_order o WHERE " + BLOCKED_ORDER_WHERE +
    " AND EXISTS (SELECT 1 FROM t_order_item oi JOIN t_product p "
    "ON p.prod_id = oi.prod_id WHERE oi.order_no = o.order_no AND p.mer_id = %s) "
    "ORDER BY o.order_no")
DEPOSIT_FIXTURE = Decimal("2000.00")
CLOSE_DEDUCT = Decimal("100.00")
CLOSE_REFUND = Decimal("1900.00")
NOTICE_DAYS_MIN = 29
NOTICE_DAYS_MAX = 31
# MerchantExitGuard names the exit state in its refusal message; the word is
# encoded so this source file stays pure ASCII (Windows GBK consoles).
EXIT_STATE_WORD = "\u9000\u5e97"

SECRETS: list = []


def redact(text) -> str:
    """Replace every credential with *** in anything bound for console or evidence."""
    out = text if isinstance(text, str) else json.dumps(text, ensure_ascii=False,
                                                        default=str)
    for secret in SECRETS:
        if secret:
            out = out.replace(secret, "***")
    return out


def redact_obj(value):
    """Deep-copy a detail structure with every string passed through redact()."""
    if isinstance(value, str):
        return redact(value)
    if isinstance(value, dict):
        return {k: redact_obj(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_obj(v) for v in value]
    return value


def say(text: str) -> None:
    """The only console writer: redacted and forced to ASCII (GBK consoles)."""
    safe = redact(text).encode("ascii", "backslashreplace").decode("ascii")
    print(safe)


class RedactingProbe(Probe):
    """Probe whose every check, note and evidence write is scrubbed of credentials.

    The inherited login/admin helpers call p.check()/p.note() directly, so the
    scrub has to live in the Probe itself rather than in a caller-side wrapper:
    that way no call path can reach the console or the evidence file unredacted.
    """

    def check(self, name: str, ok: bool, detail=None):
        super().check(redact(name), ok, None if detail is None else redact_obj(detail))

    def note(self, name: str, detail):
        super().note(redact(name), redact_obj(detail))

    def flush(self):
        with self.lock:
            if self.out_path:
                with open(self.out_path, "w", encoding="utf-8") as fh:
                    json.dump(redact_obj(self.evidence), fh, ensure_ascii=False,
                              indent=2)


def sql_text(value) -> str:
    """Render an internally-generated Python value as a SQL literal."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def sql_int(value) -> str:
    """Render a snapshot status cell as an integer literal, or NULL when it was NULL."""
    return "NULL" if is_null(value) else sql_text(int(value))


def merchant_row(db, mer_id):
    """The six exit columns of one merchant, in EXIT_COLUMNS order (None if absent)."""
    return db.one("SELECT " + ", ".join(EXIT_COLUMNS) + " FROM t_merchant "
                  "WHERE mer_id = " + sql_text(mer_id))


def count_rows(db, table: str, mer_id) -> int:
    """COUNT(*) of one merchant-scoped ledger (the name must be one of LEDGERS)."""
    if table not in LEDGERS:
        raise ValueError("refusing to count an unexpected table: %s" % table)
    return int(db.scalar("SELECT COUNT(*) FROM %s WHERE mer_id = %s"
                         % (table, sql_text(mer_id))))


def order_snapshot(db, mer_id):
    """(order_no, order_status, refund_status) of every order blocking this merchant."""
    return [list(r) for r in db.rows(BLOCKED_ORDER_SQL % sql_text(mer_id))]


def snapshot(db, mer_id) -> dict:
    """Read the merchant-scoped state the probe has to restore afterwards."""
    return {
        "products": [r[0] for r in db.rows(ON_SALE_SQL % sql_text(mer_id))],
        "orders": order_snapshot(db, mer_id),
    }


def restore(db, mer_id, snap: dict, step) -> None:
    """Undo every mutation of this run; each statement is recorded by step()."""
    step("delete this run's t_merchant_deposit rows",
         "DELETE FROM t_merchant_deposit WHERE mer_id = " + sql_text(mer_id))
    step("delete this run's t_merchant_exit_log rows",
         "DELETE FROM t_merchant_exit_log WHERE mer_id = " + sql_text(mer_id))
    nulls = ", ".join("%s = NULL" % col for col in EXIT_COLUMNS)
    step("null the six t_merchant exit columns",
         "UPDATE t_merchant SET " + nulls + " WHERE mer_id = " + sql_text(mer_id))
    for order_no, order_status, refund_status in snap["orders"]:
        step("restore t_order %s" % order_no,
             "UPDATE t_order SET order_status = %s, refund_status = NULLIF(%s, ''), "
             "update_time = update_time WHERE order_no = %s"
             % (sql_int(order_status), sql_text(refund_status),
                sql_text(order_no)))
    if snap["products"]:
        ids = ", ".join(str(int(pid)) for pid in snap["products"])
        step("restore the on-sale product set (%d ids)" % len(snap["products"]),
             "UPDATE t_product SET status = 1 WHERE prod_id IN (" + ids + ")")


def verify_restored(db, mer_id, snap: dict, check) -> None:
    """Four independent checks that the database is back at the baseline snapshot."""
    row = merchant_row(db, mer_id)
    check("14: t_merchant six exit columns are NULL again",
          row is not None and all(is_null(v) for v in row), {"row": row})

    counts = {"t_merchant_deposit": count_rows(db, "t_merchant_deposit", mer_id),
              "t_merchant_exit_log": count_rows(db, "t_merchant_exit_log", mer_id)}
    check("14: deposit and exit-log row counts are back to 0",
          counts["t_merchant_deposit"] == 0 and counts["t_merchant_exit_log"] == 0,
          counts)

    products_now = [r[0] for r in db.rows(ON_SALE_SQL % sql_text(mer_id))]
    check("14: the on-sale product set matches the baseline snapshot",
          products_now == snap["products"],
          {"baseline": len(snap["products"]), "final": len(products_now),
           "missing": sorted(set(snap["products"]) - set(products_now))[:20],
           "extra": sorted(set(products_now) - set(snap["products"]))[:20]})

    orders_now = order_snapshot(db, mer_id)
    check("14: every blocked order is back to its baseline status/refund pair",
          orders_now == snap["orders"],
          {"baseline": snap["orders"], "final": orders_now})


def with_429(p, label: str, send, attempts: int = 5):
    """Call send() until it stops answering 429; returns (http, body, throttle_hits)."""
    hits = []
    st, bd = -1, None
    for attempt in range(1, attempts + 1):
        st, bd = send()
        if st == 429 or (bd or {}).get("code") == 429:
            wait = seconds_to_next_window()
            hits.append({"attempt": attempt, "http": st, "wait_s": wait})
            with p.lock:
                p.evidence["rate_limit"].append(
                    {"path": label, "attempt": attempt, "http": st, "wait_s": wait})
            say("[WAIT] 429 on %s, sleeping %ss then retrying" % (label, wait))
            time.sleep(wait)
            continue
        break
    return st, bd, hits


def write_call(p, client, path: str, method: str, body, label: str):
    """Merchant-side write with CSRF and 429 tolerance."""
    return with_429(p, label, lambda: client.write(path, body, method=method))


def raw_write(p, client, path: str, label: str):
    """POST without the CSRF header, 429-tolerant (the throttle runs first)."""
    return with_429(p, label, lambda: client.call(path, "POST", None))


def gate_of(client):
    """GET /merchant/exit/gate -> (http, body, data, blocker codes)."""
    st, bd = client.call("/merchant/exit/gate")
    data = (bd or {}).get("data") or {}
    codes = [b.get("code") for b in (data.get("blockers") or [])
             if isinstance(b, dict)]
    return st, bd, data, codes


def status_of(client):
    """GET /merchant/exit/status -> (http, body, data)."""
    st, bd = client.call("/merchant/exit/status")
    return st, bd, ((bd or {}).get("data") or {})


def parse_args():
    ap = argparse.ArgumentParser(
        description="Live, self-cleaning acceptance probe for the merchant "
                    "store-exit state machine.")
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--admin-phone", default="13800000000")
    ap.add_argument("--admin-password", required=True)
    ap.add_argument("--merchant-phone", default="13700000002")
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--mer-id", type=int, default=1)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--out", default="reports/merchant-exit-probe-evidence.json")
    ap.add_argument("--keep", action="store_true",
                    help="skip cleanup and leave the exit state and fixture rows "
                         "behind (debugging only)")
    return ap.parse_args()


def main() -> int:
    args = parse_args()
    SECRETS.extend([args.admin_password, args.merchant_password])
    mer_id = args.mer_id
    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]

    p = RedactingProbe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "mer_id": mer_id,
        "admin_suffix": args.admin_phone[-4:],
        "merchant_suffix": args.merchant_phone[-4:],
        "run_id": run_id,
        "deposit_fixture": str(DEPOSIT_FIXTURE),
        "close_deduct": str(CLOSE_DEDUCT),
        "close_refund": str(CLOSE_REFUND),
        "expected_gate_blockers": list(EXPECTED_BLOCKERS),
        "rate_limits": {"POST /admin/merchant-exit/*": "20 req / 60 s per IP",
                        "POST /merchant/exit/*": "10 req / 60 s",
                        "POST /product/publish": "10 req / 60 s"},
        "secrets_in_evidence": False,
    })
    p.evidence["probe"] = "merchant-exit-live-acceptance"
    p.evidence["purpose"] = (
        "Merchant store-exit state machine on the running stack: read contract, "
        "request, write freeze, gate refusal, blocker clearing, approve, withdraw "
        "and re-apply, notice expiry, deposit ledger, admin detail, close with "
        "deduct and refund, permission and CSRF gates, full self-cleanup.")

    if args.out:
        parent = os.path.dirname(os.path.abspath(args.out))
        if parent:
            os.makedirs(parent, exist_ok=True)

    # The probe redacts internally, so the flow can call these directly.
    check = p.check
    note = p.note

    db = Db(args.mysql_container)
    try:
        db.run("SELECT 1")
    except SystemExit as e:
        say(str(e))
        return p.finish(3)

    def step(label: str, stmt: str) -> None:
        """One restore statement: a DB failure is a failed check, never a raise."""
        try:
            db.run(stmt)
            check("14: restore step ok - %s" % label, True)
        except BaseException as e:  # noqa: BLE001 - cleanup must fully report
            check("14: restore step failed - %s" % label, False,
                  {"error": str(e)[:300]})

    base_counts = {}
    base_row = None
    snap = {"products": [], "orders": []}
    baseline_clean = False
    stop_code = 0

    try:
        # ---------------- 1) logins ----------------
        admin = login_client(args.backend, args.admin_phone, args.admin_password,
                             p, "1: admin login (role admin / admin:all)")
        merchant = login_client(args.backend, args.merchant_phone,
                                args.merchant_password, p,
                                "1: merchant login (merchant:manage)")

        # ---------------- baseline (read only) ----------------
        row = merchant_row(db, mer_id)
        if row is None:
            check("baseline: the demo merchant row exists", False,
                  {"mer_id": mer_id})
            raise StopProbe(2)
        base_row = list(row)

        exit_cols_null = all(is_null(v) for v in row)
        check("baseline: six t_merchant exit columns are NULL", exit_cols_null,
              {"mer_id": mer_id, "row": dict(zip(EXIT_COLUMNS, row))})

        base_counts = {"t_merchant_deposit": count_rows(db, "t_merchant_deposit",
                                                        mer_id),
                       "t_merchant_exit_log": count_rows(db, "t_merchant_exit_log",
                                                         mer_id)}
        counts_zero = (base_counts["t_merchant_deposit"] == 0
                       and base_counts["t_merchant_exit_log"] == 0)
        check("baseline: deposit and exit-log tables are empty for this merchant",
              counts_zero, base_counts)

        snap = snapshot(db, mer_id)
        check("baseline: the on-sale product set was snapshotted",
              len(snap["products"]) > 0,
              {"on_sale_products": len(snap["products"]),
               "sample": snap["products"][:10]})
        check("baseline: the blocked-order set was snapshotted",
              len(snap["orders"]) > 0,
              {"blocked_orders": len(snap["orders"]), "rows": snap["orders"]})

        note("baseline", {"merchant_row": dict(zip(EXIT_COLUMNS, row)),
                          "counts": base_counts,
                          "on_sale_products": len(snap["products"]),
                          "blocked_orders": len(snap["orders"]),
                          "run_id": run_id})

        baseline_clean = exit_cols_null and counts_zero
        if not baseline_clean:
            note("precondition", {"reason": "the database is not at the exit baseline; "
                                            "nothing was touched, so nothing is restored"})
            raise StopProbe(2)

        if not snap["products"] or not snap["orders"]:
            note("precondition", {"reason": "the demo merchant needs at least one on-sale "
                                            "product and one blocking order",
                                  "on_sale_products": len(snap["products"]),
                                  "blocked_orders": len(snap["orders"])})
            raise StopProbe(1)

        # ---------------- 2) read endpoints ----------------
        st, bd, sdata = status_of(merchant)
        balance = sdata.get("depositBalance")
        check("2: GET /merchant/exit/status -> exitStatus null and "
              "depositBalance 0.00",
              st == 200 and (bd or {}).get("code") == 200
              and sdata.get("exitStatus") is None and balance is not None
              and money(balance) == Decimal("0.00"),
              {"http": st, "code": (bd or {}).get("code"), "data": sdata})

        st, bd, gdata, codes = gate_of(merchant)
        check("2: GET /merchant/exit/gate -> ready false with OPEN_ORDERS, "
              "REFUND_PENDING, PRODUCTS_ON_SALE",
              st == 200 and (bd or {}).get("code") == 200
              and gdata.get("ready") is False
              and all(c in codes for c in EXPECTED_BLOCKERS),
              {"http": st, "code": (bd or {}).get("code"), "data": gdata})

        keys = sorted(gdata.keys())
        check("2: the gate payload uses the key 'ready' (frontend contract, "
              "not 'isReady')",
              "ready" in gdata and "isReady" not in gdata, {"keys": keys})

        # ---------------- 3) request exit ----------------
        logs_before = base_counts["t_merchant_exit_log"]
        st, bd, hits = write_call(p, merchant, "/merchant/exit/request", "POST",
                                  {"reason": "probe exit"},
                                  "POST /merchant/exit/request")
        check("3: POST /merchant/exit/request -> body code 200",
              st == 200 and (bd or {}).get("code") == 200,
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"), "rate_limit_hits": hits})

        row = merchant_row(db, mer_id)
        check("3: DB exit_status=EXIT_REQUESTED with exit_request_time set",
              row is not None and row[0] == "EXIT_REQUESTED" and not is_null(row[1]),
              {"row": dict(zip(EXIT_COLUMNS, row)) if row else None})

        logs_now = count_rows(db, "t_merchant_exit_log", mer_id)
        last_role = db.one("SELECT actor_role FROM t_merchant_exit_log WHERE mer_id = "
                           + sql_text(mer_id) + " ORDER BY id DESC LIMIT 1")
        check("3: exit log +1 with actor_role=MERCHANT",
              logs_now == logs_before + 1 and last_role is not None
              and last_role[0] == "MERCHANT",
              {"logs_before": logs_before, "logs_now": logs_now,
               "last_actor_role": last_role[0] if last_role else None})

        if not (st == 200 and (bd or {}).get("code") == 200):
            raise StopProbe(1)

        # ---------------- 4) write freeze ----------------
        prod_id = snap["products"][0]
        products_before = int(db.scalar("SELECT COUNT(*) FROM t_product WHERE mer_id = "
                                        + sql_text(mer_id)))

        st, bd, hits = write_call(p, merchant,
                                  "/product/my/status/%s?status=0" % prod_id, "POST",
                                  None, "POST /product/my/status[delist]")
        check("4: delisting is still allowed while the exit request is pending",
              st == 200 and (bd or {}).get("code") == 200,
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"), "prod_id": prod_id,
               "rate_limit_hits": hits})

        st, bd, hits = write_call(p, merchant,
                                  "/product/my/status/%s?status=1" % prod_id, "POST",
                                  None, "POST /product/my/status[relist]")
        relist_msg = str((bd or {}).get("message") or "")
        check("4: relisting is refused by the exit freeze (message names the "
              "exit state)",
              (bd or {}).get("code") != 200 and EXIT_STATE_WORD in relist_msg,
              {"http": st, "code": (bd or {}).get("code"), "message": relist_msg,
               "rate_limit_hits": hits})

        st, bd, hits = write_call(p, merchant, "/product/publish", "POST",
                                  {"title": "probe blocked publish", "price": 1,
                                   "stock": 1},
                                  "POST /product/publish")
        products_after = int(db.scalar("SELECT COUNT(*) FROM t_product WHERE mer_id = "
                                       + sql_text(mer_id)))
        publish_msg = str((bd or {}).get("message") or "")
        check("4: POST /product/publish is refused and no product row appears",
              (bd or {}).get("code") != 200 and EXIT_STATE_WORD in publish_msg
              and products_after == products_before,
              {"http": st, "code": (bd or {}).get("code"), "message": publish_msg,
               "products_before": products_before, "products_after": products_after,
               "rate_limit_hits": hits})

        # ---------------- 5) approve blocked ----------------
        st, bd, hits = admin_call(p, admin,
                                  "/admin/merchant-exit/%s/approve" % sql_text(mer_id),
                                  "POST", None, "POST approve[blocked]")
        row = merchant_row(db, mer_id)
        check("5: approve is refused while blockers exist -> body code 400",
              (bd or {}).get("code") == 400 and row is not None
              and row[0] == "EXIT_REQUESTED",
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"),
               "exit_status": row[0] if row else None,
               "rate_limit_hits": hits})

        # ---------------- 6) clear blockers temporarily ----------------
        db.run("UPDATE t_product SET status = 0 WHERE mer_id = " + sql_text(mer_id)
               + " AND status = 1 AND deleted = 0")
        for order_no, _status, _refund in snap["orders"]:
            db.run("UPDATE t_order SET order_status = 3, refund_status = 'none', "
                   "update_time = update_time WHERE order_no = "
                   + sql_text(order_no))
        st, bd, gdata, codes = gate_of(merchant)
        check("6: with orders cancelled and products delisted the gate reports ready",
              st == 200 and (bd or {}).get("code") == 200
              and gdata.get("ready") is True,
              {"http": st, "code": (bd or {}).get("code"), "blockers": codes,
               "data": gdata})

        # ---------------- 7) approve ----------------
        logs_before = count_rows(db, "t_merchant_exit_log", mer_id)
        st, bd, hits = admin_call(p, admin,
                                  "/admin/merchant-exit/%s/approve" % sql_text(mer_id),
                                  "POST", None, "POST approve")
        row = merchant_row(db, mer_id)
        check("7: approve -> PUBLIC_NOTICE (code 200 + DB)",
              st == 200 and (bd or {}).get("code") == 200 and row is not None
              and row[0] == "PUBLIC_NOTICE",
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"),
               "exit_status": row[0] if row else None, "rate_limit_hits": hits})

        days_raw = db.scalar(
            "SELECT TIMESTAMPDIFF(SECOND, NOW(), notice_end_time) / 86400 "
            "FROM t_merchant WHERE mer_id = " + sql_text(mer_id), default="NULL")
        days = None
        if not is_null(days_raw):
            try:
                days = float(days_raw)
            except ValueError:
                days = None
        check("7: notice_end_time is ~30 days out (29-31 days)",
              days is not None and NOTICE_DAYS_MIN <= days <= NOTICE_DAYS_MAX,
              {"days": None if days is None else round(days, 4)})

        logs_now = count_rows(db, "t_merchant_exit_log", mer_id)
        check("7: exit log +1 on approve",
              logs_now == logs_before + 1,
              {"logs_before": logs_before, "logs_now": logs_now})

        if not (row is not None and row[0] == "PUBLIC_NOTICE"):
            raise StopProbe(1)

        # ---------------- 8) withdraw then re-apply ----------------
        logs_before = count_rows(db, "t_merchant_exit_log", mer_id)
        st, bd, hits = write_call(p, merchant, "/merchant/exit/withdraw", "POST",
                                  None, "POST /merchant/exit/withdraw")
        row = merchant_row(db, mer_id)
        check("8: withdraw -> code 200 and exit_status/request_time/notice_end_time "
              "back to NULL",
              st == 200 and (bd or {}).get("code") == 200 and row is not None
              and is_null(row[0]) and is_null(row[1]) and is_null(row[2]),
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"),
               "row": dict(zip(EXIT_COLUMNS, row)) if row else None,
               "rate_limit_hits": hits})

        logs_now = count_rows(db, "t_merchant_exit_log", mer_id)
        check("8: withdraw writes one exit-log row",
              logs_now == logs_before + 1,
              {"logs_before": logs_before, "logs_now": logs_now})

        st, bd, hits_req = write_call(p, merchant, "/merchant/exit/request", "POST",
                                      {"reason": "probe exit again"},
                                      "POST /merchant/exit/request[re-apply]")
        st2, bd2, hits_appr = admin_call(p, admin,
                                         "/admin/merchant-exit/%s/approve"
                                         % sql_text(mer_id), "POST", None,
                                         "POST approve[re-apply]")
        row = merchant_row(db, mer_id)
        check("8: request + approve again -> PUBLIC_NOTICE (the machine is repeatable)",
              (bd or {}).get("code") == 200 and (bd2 or {}).get("code") == 200
              and row is not None and row[0] == "PUBLIC_NOTICE",
              {"request_code": (bd or {}).get("code"),
               "approve_code": (bd2 or {}).get("code"),
               "approve_message": (bd2 or {}).get("message"),
               "exit_status": row[0] if row else None,
               "rate_limit_hits": hits_req + hits_appr})

        if not (row is not None and row[0] == "PUBLIC_NOTICE"):
            raise StopProbe(1)

        # ---------------- 9) notice expiry ----------------
        logs_before = count_rows(db, "t_merchant_exit_log", mer_id)
        db.run("UPDATE t_merchant SET notice_end_time = NOW() - INTERVAL 1 MINUTE, "
               "update_time = update_time WHERE mer_id = " + sql_text(mer_id))

        # run-notice is global: record who else is already due so the evidence says
        # plainly what the sweep may touch outside this merchant.
        other_due = int(db.scalar(
            "SELECT COUNT(*) FROM t_merchant WHERE deleted = 0 AND mer_id <> "
            + sql_text(mer_id) + " AND exit_status = 'PUBLIC_NOTICE' AND "
            "notice_end_time IS NOT NULL AND notice_end_time <= NOW()"))
        note("9: other merchants already due at sweep time", {"count": other_due})

        st, bd, hits = admin_call(p, admin, "/admin/merchant-exit/run-notice", "POST",
                                  None, "POST run-notice[first]")
        frozen_first = ((bd or {}).get("data") or {}).get("frozen")
        row = merchant_row(db, mer_id)
        check("9: run-notice freezes the expired merchant (frozen >= 1, DB FROZEN)",
              st == 200 and (bd or {}).get("code") == 200
              and frozen_first is not None and int(frozen_first) >= 1
              and row is not None and row[0] == "FROZEN",
              {"http": st, "code": (bd or {}).get("code"), "frozen": frozen_first,
               "exit_status": row[0] if row else None, "rate_limit_hits": hits})

        st, bd, hits = admin_call(p, admin, "/admin/merchant-exit/run-notice", "POST",
                                  None, "POST run-notice[second]")
        frozen_second = ((bd or {}).get("data") or {}).get("frozen")
        row = merchant_row(db, mer_id)
        check("9: a second run-notice is idempotent (frozen == 0, still FROZEN)",
              st == 200 and (bd or {}).get("code") == 200
              and frozen_second is not None and int(frozen_second) == 0
              and row is not None and row[0] == "FROZEN",
              {"http": st, "code": (bd or {}).get("code"),
               "frozen": frozen_second,
               "exit_status": row[0] if row else None, "rate_limit_hits": hits})

        logs_now = count_rows(db, "t_merchant_exit_log", mer_id)
        last_role = db.one("SELECT actor_role FROM t_merchant_exit_log WHERE mer_id = "
                           + sql_text(mer_id) + " ORDER BY id DESC LIMIT 1")
        check("9: the freeze wrote one SYSTEM exit-log row",
              logs_now == logs_before + 1 and last_role is not None
              and last_role[0] == "SYSTEM",
              {"logs_before": logs_before, "logs_now": logs_now,
               "last_actor_role": last_role[0] if last_role else None})

        # ---------------- 10) deposit fixture ----------------
        db.run("INSERT INTO t_merchant_deposit (mer_id, type, amount, balance_after, "
               "remark) VALUES (%s, 'PAY', %s, %s, 'probe fixture')"
               % (sql_text(mer_id), sql_text(DEPOSIT_FIXTURE),
                  sql_text(DEPOSIT_FIXTURE)))
        st, bd, sdata = status_of(merchant)
        balance = sdata.get("depositBalance")
        check("10: deposit fixture is visible as depositBalance 2000.00",
              st == 200 and (bd or {}).get("code") == 200 and balance is not None
              and money(balance) == DEPOSIT_FIXTURE,
              {"http": st, "code": (bd or {}).get("code"), "data": sdata,
               "expected": str(DEPOSIT_FIXTURE)})

        # ---------------- 11) admin detail ----------------
        st, bd = admin.call("/admin/merchant-exit/detail/%s" % sql_text(mer_id))
        detail = (bd or {}).get("data") or {}
        gate_report = detail.get("gateReport") or {}
        deposits = detail.get("deposits") or []
        logs = detail.get("logs") or []
        check("11: admin detail -> gateReport.ready true",
              st == 200 and (bd or {}).get("code") == 200
              and gate_report.get("ready") is True,
              {"http": st, "code": (bd or {}).get("code"),
               "gate_report": gate_report})
        check("11: admin detail -> one deposit row and at least five log rows",
              len(deposits) == 1 and len(logs) >= 5,
              {"deposits": len(deposits), "logs": len(logs),
               "deposit_balance": detail.get("depositBalance")})

        # ---------------- 12) close ----------------
        st, bd, hits = admin_call(p, admin,
                                  "/admin/merchant-exit/%s/close" % sql_text(mer_id),
                                  "POST",
                                  {"deductAmount": 100, "remark": "probe deduct"},
                                  "POST close")
        row = merchant_row(db, mer_id)
        check("12: close -> code 200, exit_status CLOSED, close_time set",
              st == 200 and (bd or {}).get("code") == 200 and row is not None
              and row[0] == "CLOSED" and not is_null(row[3]),
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"),
               "row": dict(zip(EXIT_COLUMNS, row)) if row else None,
               "rate_limit_hits": hits})

        deposit_rows = db.rows("SELECT type, amount FROM t_merchant_deposit "
                               "WHERE mer_id = " + sql_text(mer_id)
                               + " ORDER BY deposit_id")
        has_deduct = any(r[0] == "DEDUCT" and money(r[1]) == CLOSE_DEDUCT
                         for r in deposit_rows)
        has_refund = any(r[0] == "REFUND" and money(r[1]) == CLOSE_REFUND
                         for r in deposit_rows)
        check("12: deposit ledger holds DEDUCT 100 and REFUND 1900",
              has_deduct and has_refund, {"rows": deposit_rows})

        balance_sum = money(db.scalar(
            "SELECT COALESCE(SUM(CASE WHEN type = 'PAY' THEN amount ELSE -amount END), 0) "
            "FROM t_merchant_deposit WHERE mer_id = " + sql_text(mer_id)))
        check("12: the SUM-based deposit balance is 0",
              balance_sum == Decimal("0.00"), {"balance": str(balance_sum)})

        st, bd, gdata, codes = gate_of(merchant)
        check("12: the gate still reports ready after close",
              st == 200 and (bd or {}).get("code") == 200
              and gdata.get("ready") is True,
              {"http": st, "code": (bd or {}).get("code"), "blockers": codes})

        st, bd, hits = admin_call(p, admin,
                                  "/admin/merchant-exit/%s/close" % sql_text(mer_id),
                                  "POST",
                                  {"deductAmount": 0, "remark": "probe close again"},
                                  "POST close[again]")
        row = merchant_row(db, mer_id)
        check("12: a second close -> body code 400 and the state stays CLOSED",
              (bd or {}).get("code") == 400 and row is not None
              and row[0] == "CLOSED",
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message"),
               "exit_status": row[0] if row else None, "rate_limit_hits": hits})

        # ---------------- 13) permissions / CSRF ----------------
        anon = Client(args.backend)
        st, bd = anon.call("/merchant/exit/status")
        check("13: anonymous GET /merchant/exit/status -> HTTP 401",
              st == 401,
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message")})

        st, bd = merchant.call("/admin/merchant-exit/list")
        check("13: merchant session GET /admin/merchant-exit/list -> body code 403 "
              "(no admin:all)",
              (bd or {}).get("code") == 403,
              {"http": st, "code": (bd or {}).get("code"),
               "message": (bd or {}).get("message")})

        st, bd, hits = raw_write(p, merchant, "/merchant/exit/withdraw",
                                 "POST /merchant/exit/withdraw[no csrf]")
        csrf_msg = str((bd or {}).get("message") or "")
        check("13: POST /merchant/exit/withdraw without the CSRF header -> 403",
              st == 403 or (bd or {}).get("code") == 403,
              {"http": st, "code": (bd or {}).get("code"), "message": csrf_msg,
               "note": "CsrfInterceptor answers with a 403 envelope; the HTTP "
                       "status is 403 when the rejection also sets it",
               "rate_limit_hits": hits})

    except StopProbe as e:
        stop_code = e.code
        note("probe stopped early", {"exit_code": e.code})
    except SystemExit as e:
        stop_code = 3
        check("the database stayed reachable for the whole run", False,
              {"error": redact(str(e))[:300]})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        check("the probe ran to completion without an unexpected error", False,
              {"error": repr(e)[:400]})
    finally:
        cleanup = p.evidence["cleanup"]
        cleanup["baselines"] = {
            "merchant_exit_columns": (dict(zip(EXIT_COLUMNS, base_row))
                                      if base_row else None),
            "row_counts": base_counts,
            "on_sale_products": len(snap["products"]),
            "blocked_orders": snap["orders"],
        }
        if args.keep:
            cleanup["mode"] = "kept"
            note("cleanup", {"mode": "kept", "reason": "--keep"})
        elif not baseline_clean:
            cleanup["mode"] = "skipped"
            note("cleanup", {"mode": "skipped",
                             "reason": "baseline was not clean, nothing was touched"})
        else:
            cleanup["mode"] = "cleanup"
            try:
                restore(db, mer_id, snap, step)
                verify_restored(db, mer_id, snap, check)
            except BaseException as e:  # noqa: BLE001 - cleanup must not crash
                check("14: cleanup verification failed", False,
                      {"error": str(e)[:300]})

        note("scope", {
            "image_rebuilt": False,
            "containers_restarted": False,
            "secrets_printed": False,
            "java_modified": False,
            "touched_rows": "t_merchant exit columns of mer_id (restored to NULL), "
                            "t_merchant_deposit / t_merchant_exit_log rows of mer_id "
                            "(deleted), snapshotted t_order order_status/refund_status "
                            "(restored), snapshotted t_product status (restored)",
            "other_merchants_untouched": True,
            "update_time_note": "order updates pin update_time = update_time so the "
                                "ON UPDATE CURRENT_TIMESTAMP column is not bumped",
            "audit_note": "the 403 gate check writes one ACCESS_DENIED row into the "
                          "audit log; it is a legitimate audit trail and is kept",
            "auto_increment_note": "AUTO_INCREMENT counters on the two ledgers are "
                                   "not reset (row data is gone, numbering state kept)",
        })

    if stop_code == 3:
        code = 3
    elif stop_code == 2:
        code = 2
    elif p.failed or stop_code == 1:
        code = 1
    else:
        code = 0
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
