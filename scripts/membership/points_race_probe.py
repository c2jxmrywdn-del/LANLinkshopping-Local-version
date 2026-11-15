#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0-7 points double-spend race probe (self-cleaning).

Proves, against the running stack, that two concurrent checkouts racing to
redeem the SAME member-card balance cannot double-spend:

  * Each request asks for R points, where R is the smallest multiple of 1000
    strictly greater than balance/2 (so 2*R > balance: both cannot fully win).
  * MembershipService.redeemPoints now uses MemberCardMapper.deductPointsAtomic
    (conditional UPDATE points >= amount); a loser gets 0 rows -> no deduction,
    no point log, order still created with 0 deduction.
  * redeem runs inside the checkout transaction (REQUIRED); no payment is
    performed, so the only balance movements are the probe's redeems/refunds.

Assertions:
  a) sum of the two orders' point_deduct_points <= original balance, and they
     did not BOTH deduct the full requested R;
  b) redeem_order logs in t_member_point_log match 1:1 the orders that actually
     deducted (UNIQUE(user_id, change_type, ref_order_no)), with exact values;
  c) balance right after the race == original balance - total actually deducted.

Cleanup (finally): cancel probe orders via /order/cancel (business-level stock
restore + points refund), then SQL-delete ONLY rows produced by this run
(orders/items/point logs/messages/idempotent tokens/risk events keyed by the
probe order numbers and idempotent keys), remove leftover probe cart lines via
the cart API, and verify the balance/stock/cart baseline is restored.

Credentials are passed as arguments and never printed. DB access goes through
`docker exec` with the container's own environment variables, so no password
appears in this script, in the command line, or in the output.

Usage:
  python scripts/membership/points_race_probe.py --phone 13900000001 \
      --password 'xxx' [--backend http://127.0.0.1:8080/api] \
      [--mysql-container shopping-mysql-1] [--keep] [--out evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure, 2 precondition
missing (no member card / balance too small / no suitable products), 3
docker/DB unreachable. Console output is ASCII on purpose (Windows GBK
consoles mangle CJK); the full UTF-8 evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.cookiejar import CookieJar


REMARK = "points-race-probe"


class Client:
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
            raise SystemExit("login failed HTTP=%s code=%s" % (status, (body or {}).get("code")))
        if (body.get("data") or {}).get("require2fa"):
            raise SystemExit("account has 2FA enabled; use a demo account without 2FA")
        _, csrf_body = self.call("/user/csrf-token")
        data = (csrf_body or {}).get("data") or {}
        self.csrf = str(data.get("token") or data.get("csrfToken") or "")
        if not self.csrf:
            raise SystemExit("no CSRF token returned")

    def write(self, path, body=None):
        return self.call(path, "POST", body, {"X-CSRF-TOKEN": self.csrf})

    def delete(self, path):
        return self.call(path, "DELETE", None, {"X-CSRF-TOKEN": self.csrf})


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
    def __init__(self, out_path: str):
        self.results = []
        self.out_path = out_path

    def check(self, name: str, ok: bool, detail=None):
        self.results.append({"name": name, "ok": bool(ok), "detail": detail})
        print("[%s] %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            print("       detail: %s" % json.dumps(detail, ensure_ascii=False)[:600])
        self.flush()

    def note(self, name: str, detail):
        self.results.append({"name": name, "observation": detail})
        self.flush()

    def flush(self):
        if self.out_path:
            with open(self.out_path, "w", encoding="utf-8") as fh:
                json.dump(self.results, fh, ensure_ascii=False, indent=2)

    @property
    def failed(self):
        return [r for r in self.results if r.get("ok") is False]


def sql_in(values):
    """Quote a list of internally-generated identifiers for an SQL IN clause."""
    return ",".join("'" + str(v).replace("'", "''") + "'" for v in values)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--phone", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true", help="skip cleanup (leaves probe data behind)")
    ap.add_argument("--out", default="reports/points-race-evidence.json")
    args = ap.parse_args()

    p = Probe(args.out)
    db = Db(args.mysql_container)

    try:
        db.run("SELECT 1")
    except SystemExit as e:
        print(str(e))
        return 3

    uid_rows = db.rows("SELECT user_id FROM t_user WHERE phone = '%s' LIMIT 1"
                       % args.phone.replace("'", "''"))
    if not uid_rows:
        print("buyer phone not found in t_user")
        return 2
    uid = uid_rows[0][0]

    card = db.rows("SELECT points FROM t_member_card WHERE user_id = %s" % uid)
    balance = int(card[0][0]) if card and card[0][0] not in ("", "NULL") else 0
    if balance < 2000:
        print("precondition missing: member card balance=%s (<2000); "
              "will NOT charge the DB, stopping" % balance)
        p.note("precondition", {"balance": balance, "stopped": True})
        return 2

    # R = smallest multiple of 1000 strictly above balance/2 -> 2R > balance.
    want_points = (balance // 2 // 1000 + 1) * 1000
    if want_points > balance:
        print("precondition missing: balance too small for the race design")
        return 2
    min_price = max(10000, (want_points // 1000) * 10 * 10)  # 10x headroom over the 10% cap
    prods = db.rows(
        "SELECT prod_id, price, stock FROM t_product WHERE status = 1 AND stock >= 2 "
        "AND price >= %d AND prod_id NOT IN (SELECT prod_id FROM t_cart WHERE user_id = %s) "
        "ORDER BY price ASC LIMIT 2" % (min_price, uid))
    if len(prods) < 2:
        print("precondition missing: need 2 buyable products (price>=%d, stock>=2, "
              "not already in cart), found %d" % (min_price, len(prods)))
        return 2
    prod_a, price_a, stock_a = prods[0][0], prods[0][1], int(prods[0][2])
    prod_b, price_b, stock_b = prods[1][0], prods[1][1], int(prods[1][2])

    base_cart = [r[0] for r in db.rows(
        "SELECT cart_id FROM t_cart WHERE user_id = %s ORDER BY cart_id" % uid)]
    base_orders = int(db.rows("SELECT COUNT(*) FROM t_order WHERE user_id = %s" % uid)[0][0])
    base_logs = int(db.rows("SELECT COUNT(*) FROM t_member_point_log WHERE user_id = %s" % uid)[0][0])
    p.note("baseline", {"user_id": uid, "phone_suffix": args.phone[-4:], "balance": balance,
                        "request_points_each": want_points, "two_requests_total": 2 * want_points,
                        "prod_a": {"prod_id": prod_a, "price": price_a, "stock": stock_a},
                        "prod_b": {"prod_id": prod_b, "price": price_b, "stock": stock_b},
                        "cart_ids": base_cart, "orders": base_orders, "point_logs": base_logs})

    # Two independent sessions of the SAME buyer (servlet sessions, no single-session lock).
    c1, c2 = Client(args.backend), Client(args.backend)
    c1.login(args.phone, args.password)
    c2.login(args.phone, args.password)
    p.note("sessions", {"two_logins_ok": True, "csrf_acquired": bool(c1.csrf and c2.csrf)})

    keys = [str(uuid.uuid4()), str(uuid.uuid4())]
    probe_cart_ids = []
    order_nos = []
    race_results = [None, None]
    race_ok = False
    try:
        # One fresh cart line per product (different products -> no cart/stock contention;
        # the ONLY shared mutable state is t_member_card.points).
        for c, prod in ((c1, prod_a), (c2, prod_b)):
            st, bd = c.write("/cart/add", {"prodId": int(prod), "quantity": 1})
            if st != 200 or (bd or {}).get("code") != 200:
                print("cart/add failed HTTP=%s code=%s" % (st, (bd or {}).get("code")))
                return 1
        probe_cart_ids = [r[0] for r in db.rows(
            "SELECT cart_id FROM t_cart WHERE user_id = %s AND prod_id IN (%s,%s)"
            % (uid, prod_a, prod_b))]
        if len(probe_cart_ids) != 2:
            print("expected 2 fresh cart lines, got %d" % len(probe_cart_ids))
            return 1

        # Pre-flight: the 10%-of-payable cap must NOT bind, i.e. quote returns full R.
        for prod, price in ((prod_a, price_a), (prod_b, price_b)):
            st, bd = c1.call("/membership/redeem-quote?points=%d&orderAmount=%s"
                             % (want_points, price))
            q = (bd or {}).get("data") or {}
            p.check("redeem quote allows full request (prod %s)" % prod,
                    st == 200 and int(q.get("points", -1)) == want_points,
                    {"http": st, "quote": q})
        if p.failed:
            return 1

        balance_pre = int(db.rows("SELECT points FROM t_member_card WHERE user_id = %s" % uid)[0][0])
        db_now = db.run("SELECT DATE_SUB(NOW(), INTERVAL 2 SECOND)")
        barrier = threading.Barrier(2, timeout=60)

        def worker(i, client, cart_id, key):
            try:
                barrier.wait()
            except threading.BrokenBarrierError:
                race_results[i] = {"error": "barrier broken"}
                return
            t0 = time.time()
            body = {"cartIds": [int(cart_id)], "receiver": "Race Probe",
                    "phone": args.phone, "address": "points-race-probe (auto cleanup)",
                    "remark": REMARK, "payType": "corporate",
                    "usePoints": want_points, "idempotentKey": key}
            status, resp = client.write("/order/checkout", body)
            race_results[i] = {"http": status, "code": (resp or {}).get("code"),
                               "msg": (resp or {}).get("msg"),
                               "orderNo": ((resp or {}).get("data") or {}).get("orderNo"),
                               "start": t0, "end": time.time(), "request": body}

        threads = [threading.Thread(target=worker, args=(i, c, cid, k))
                   for i, (c, cid, k) in enumerate(zip((c1, c2), probe_cart_ids, keys))]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=120)
        race_ok = True
        p.note("race responses", race_results)
        if all(r and r.get("start") is not None for r in race_results):
            overlap = (min(r["end"] for r in race_results)
                       - max(r["start"] for r in race_results))
            p.note("request overlap seconds", round(overlap, 3))

        # Discover created orders: idempotent tokens bound to this run's keys are
        # exact; remark+time window is a fallback for lost HTTP responses.
        order_nos = [r[0] for r in db.rows(
            "SELECT result_ref FROM t_idempotent_token WHERE user_id = %s "
            "AND biz_type = 'checkout' AND biz_key IN (%s) AND result_ref IS NOT NULL"
            % (uid, sql_in(keys)))]
        fallback = [r[0] for r in db.rows(
            "SELECT order_no FROM t_order WHERE user_id = %s AND remark = '%s' "
            "AND create_time >= '%s'" % (uid, REMARK, db_now))]
        order_nos = sorted(set(order_nos) | set(fallback))
        p.note("probe orders discovered", order_nos)

        p.check("both concurrent checkouts created an order",
                len(order_nos) == 2 and all(r and r.get("code") == 200 for r in race_results),
                {"orders": order_nos,
                 "http_codes": [r and r.get("code") for r in race_results]})

        if order_nos:
            o_rows = db.rows(
                "SELECT order_no, point_deduct_points, point_deduct_amount, order_status, pay_status "
                "FROM t_order WHERE order_no IN (%s)" % sql_in(order_nos))
            deducts = {r[0]: int(r[1]) for r in o_rows}
            total_deduct = sum(deducts.values())
            p.note("orders after race", [dict(zip(
                ("order_no", "point_deduct_points", "point_deduct_amount",
                 "order_status", "pay_status"), r)) for r in o_rows])

            # (a) no double spend: total <= original balance; not both full R.
            p.check("a) total deducted <= original balance",
                    total_deduct <= balance_pre,
                    {"balance_pre": balance_pre, "deducts": deducts, "total": total_deduct})
            p.check("a) both requests did NOT each deduct the full request",
                    not (len(deducts) == 2 and all(v == want_points for v in deducts.values())
                         and 2 * want_points > balance_pre),
                    {"deducts": deducts, "requested_each": want_points})

            # (b) point logs 1:1 with successful deductions, exact values.
            l_rows = db.rows(
                "SELECT ref_order_no, change_type, change_val FROM t_member_point_log "
                "WHERE user_id = %s AND ref_order_no IN (%s)" % (uid, sql_in(order_nos)))
            redeems = {r[0]: int(r[2]) for r in l_rows if r[1] == "redeem_order"}
            winners = {k: v for k, v in deducts.items() if v > 0}
            p.check("b) redeem log count == orders with deduction",
                    len(redeems) == len(winners),
                    {"redeem_logs": redeems, "orders_with_deduction": winners})
            p.check("b) each redeem log value matches its order",
                    all(redeems.get(k) == -v for k, v in winners.items()),
                    {"redeem_logs": redeems, "expected": {k: -v for k, v in winners.items()}})
            p.check("b) no duplicate redeem log per order (unique key held)",
                    len([r for r in l_rows if r[1] == "redeem_order"]) == len(redeems),
                    l_rows)

            # (c) balance == original - actually deducted (no payment performed).
            balance_post = int(db.rows(
                "SELECT points FROM t_member_card WHERE user_id = %s" % uid)[0][0])
            p.check("c) balance after race == original - total deducted",
                    balance_post == balance_pre - total_deduct,
                    {"original": balance_pre, "total_deducted": total_deduct,
                     "expected": balance_pre - total_deduct, "actual": balance_post})
            p.note("race outcome", {"balance_pre": balance_pre, "balance_post": balance_post,
                                    "total_deducted": total_deduct, "deducts": deducts,
                                    "redeem_logs": len(redeems)})
    finally:
        if not args.keep:
            # 1) Business-level close: cancel refunds points + restores stock.
            for ono in order_nos:
                st_row = db.rows("SELECT order_status FROM t_order WHERE order_no = '%s'" % ono)
                if st_row and st_row[0][0] == "3":
                    continue
                st, bd = c1.write("/order/cancel/%s" % ono)
                p.note("cancel %s" % ono, {"http": st, "code": (bd or {}).get("code"),
                                           "msg": (bd or {}).get("msg")})
            # 2) Remove ONLY rows produced by this run.
            if order_nos:
                ins = sql_in(order_nos)
                db.run("DELETE FROM t_order_item WHERE order_no IN (%s)" % ins)
                db.run("DELETE FROM t_order WHERE user_id = %s AND order_no IN (%s)" % (uid, ins))
                db.run("DELETE FROM t_member_point_log WHERE user_id = %s AND ref_order_no IN (%s)"
                       % (uid, ins))
                db.run("DELETE FROM t_message WHERE user_id = %s AND related_no IN (%s)" % (uid, ins))
                db.run("DELETE FROM t_user_risk_event WHERE user_id = %s AND ref_type = 'order' "
                       "AND ref_id IN (%s)" % (uid, ins))
            db.run("DELETE FROM t_idempotent_token WHERE user_id = %s AND biz_type = 'checkout' "
                   "AND biz_key IN (%s)" % (uid, sql_in(keys)))
            # 3) Leftover probe cart lines (checkout deletes them on success only).
            for cid in probe_cart_ids:
                left = db.rows("SELECT cart_id FROM t_cart WHERE cart_id = %s AND user_id = %s"
                               % (cid, uid))
                if left:
                    st, bd = c1.delete("/cart/%s" % cid)
                    if st != 200 or (bd or {}).get("code") != 200:
                        db.run("DELETE FROM t_cart WHERE cart_id = %s AND user_id = %s" % (cid, uid))
            # 4) Balance baseline: cancel should have refunded everything; if a
            # partial failure left a delta, restore ONLY this user's card.
            bal_now = int(db.rows("SELECT points FROM t_member_card WHERE user_id = %s" % uid)[0][0])
            if bal_now != balance:
                db.run("UPDATE t_member_card SET points = %d WHERE user_id = %s" % (balance, uid))
                p.note("balance manual restore", {"was": bal_now, "restored_to": balance})
            # 5) Verify baseline.
            bal_final = int(db.rows("SELECT points FROM t_member_card WHERE user_id = %s" % uid)[0][0])
            p.check("cleanup: balance restored to baseline", bal_final == balance,
                    {"baseline": balance, "final": bal_final})
            stock_now = {r[0]: int(r[1]) for r in db.rows(
                "SELECT prod_id, stock FROM t_product WHERE prod_id IN (%s,%s)" % (prod_a, prod_b))}
            p.check("cleanup: product stock restored",
                    stock_now.get(prod_a) == stock_a and stock_now.get(prod_b) == stock_b,
                    {"baseline": {prod_a: stock_a, prod_b: stock_b}, "final": stock_now})
            cart_final = [r[0] for r in db.rows(
                "SELECT cart_id FROM t_cart WHERE user_id = %s ORDER BY cart_id" % uid)]
            p.check("cleanup: cart restored to baseline", cart_final == base_cart,
                    {"baseline": base_cart, "final": cart_final})
            left_orders = int(db.rows("SELECT COUNT(*) FROM t_order WHERE user_id = %s" % uid)[0][0])
            left_logs = int(db.rows("SELECT COUNT(*) FROM t_member_point_log WHERE user_id = %s" % uid)[0][0])
            p.check("cleanup: order/point-log counts restored",
                    left_orders == base_orders and left_logs == base_logs,
                    {"baseline": [base_orders, base_logs], "final": [left_orders, left_logs]})
            if order_nos:
                ins = sql_in(order_nos)
                residue = db.rows(
                    "SELECT (SELECT COUNT(*) FROM t_order WHERE order_no IN (%s)) + "
                    "(SELECT COUNT(*) FROM t_order_item WHERE order_no IN (%s)) + "
                    "(SELECT COUNT(*) FROM t_member_point_log WHERE user_id = %s AND ref_order_no IN (%s)) + "
                    "(SELECT COUNT(*) FROM t_message WHERE user_id = %s AND related_no IN (%s))"
                    % (ins, ins, uid, ins, uid, ins))
                p.check("cleanup: no probe residue", residue and residue[0][0] == "0", residue)
        else:
            p.note("cleanup", "skipped (--keep); probe orders left in DB: %s" % order_nos)

    print("\n%d checks, %d failed, evidence: %s"
          % (len([r for r in p.results if "ok" in r]), len(p.failed), args.out))
    return 1 if p.failed else 0


if __name__ == "__main__":
    sys.exit(main())