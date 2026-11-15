#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Buyer-review MVP live probe (self-cleaning).

Proves, against the running stack:
  1. t_product_review / t_product_rating_stat exist in the container DB;
  2. the two read endpoints are reachable WITHOUT a session (public whitelist);
  3. submitting a review on the buyer's own COMPLETED order returns 200 and
     bumps the aggregate (count / rating_sum / good_count / average / goodRate);
  4. the new review shows up in the paginated list;
  5. a second review on the same order item is rejected with body code 409;
  6. out-of-range rating is rejected (400), an item from another order is
     rejected (404), an unfinished order is rejected (400);
  7. optional: a second buyer cannot review the first buyer's order (404);
  8. cleanup removes the probe review and restores the aggregate to baseline.

Credentials are passed as arguments and never printed. DB access goes through
`docker exec` with the container's own environment variables, so no password
appears in this script, in the command line, or in the output.

Usage:
  python scripts/review/review_probe.py --phone 13800000001 --password 'xxx' \
      [--backend http://127.0.0.1:8080/api] [--mysql-container shopping-mysql-1] \
      [--other-phone ... --other-password ...] [--keep] [--out evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure, 2 precondition missing
(no completed reviewable order for this buyer), 3 docker/DB unreachable.
Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the full
UTF-8 evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar


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

    def skip(self, name: str, reason: str):
        self.results.append({"name": name, "ok": None, "skipped": reason})
        print("[SKIP] %s (%s)" % (name, reason))
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


def summary_of(client: Client, prod_id: str):
    status, body = client.call("/product/detail/%s/reviews/summary" % prod_id)
    return status, (body or {}).get("code"), (body or {}).get("data") or {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--phone", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--other-phone")
    ap.add_argument("--other-password")
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--prod-id", help="force a product (default: discovered from a completed order)")
    ap.add_argument("--order-no", help="force an order (must be completed and owned by --phone)")
    ap.add_argument("--keep", action="store_true", help="skip cleanup (leaves probe data behind)")
    ap.add_argument("--out", default="reports/review-probe-evidence.json")
    args = ap.parse_args()

    p = Probe(args.out)
    db = Db(args.mysql_container)

    try:
        tables = db.run("SHOW TABLES LIKE 't_product_r%'")
    except SystemExit as e:
        print(str(e))
        return 3
    p.check("review tables exist", "t_product_review" in tables and "t_product_rating_stat" in tables,
            tables.splitlines())
    if p.failed:
        return 1

    buyer = Client(args.backend)
    buyer.login(args.phone, args.password)
    p.note("buyer login", {"phone_suffix": args.phone[-4:], "csrf_acquired": bool(buyer.csrf)})

    uid_rows = db.rows("SELECT user_id FROM t_user WHERE phone = '%s' LIMIT 1" % args.phone)
    if not uid_rows:
        print("buyer phone not found in t_user")
        return 2
    uid = uid_rows[0][0]

    # A reviewable line: own order, completed, inside the 30-day window, not yet reviewed.
    cond = ("o.user_id = %s AND o.order_status = 2 "
            "AND COALESCE(o.update_time, o.create_time) >= NOW() - INTERVAL 30 DAY "
            "AND NOT EXISTS (SELECT 1 FROM t_product_review r WHERE r.item_id = i.item_id)"
            % uid)
    if args.order_no:
        cond += " AND o.order_no = '%s'" % args.order_no
    if args.prod_id:
        cond += " AND i.prod_id = %s" % args.prod_id
    cand = db.rows("SELECT o.order_no, i.item_id, i.prod_id FROM t_order o "
                   "JOIN t_order_item i ON i.order_no = o.order_no WHERE " + cond +
                   " ORDER BY o.update_time DESC LIMIT 1")
    if not cand:
        print("no completed, unreviewed, in-window order line for this buyer")
        return 2
    order_no, item_id, prod_id = cand[0]
    p.note("probe target", {"order_no": order_no, "item_id": item_id, "prod_id": prod_id})

    stat_before = db.rows("SELECT review_count, rating_sum, good_count FROM t_product_rating_stat "
                          "WHERE prod_id = %s" % prod_id)
    base_count = int(stat_before[0][0]) if stat_before else 0
    base_sum = int(stat_before[0][1]) if stat_before else 0
    base_good = int(stat_before[0][2]) if stat_before else 0
    anon = Client(args.backend)
    a_status, a_code, a_sum = summary_of(anon, prod_id)
    p.check("summary readable without login", a_status == 200 and a_code == 200,
            {"http": a_status, "code": a_code, "data": a_sum})
    l_status, l_body = anon.call("/product/detail/%s/reviews?page=1&size=5" % prod_id)
    p.check("list readable without login",
            l_status == 200 and (l_body or {}).get("code") == 200,
            {"http": l_status, "code": (l_body or {}).get("code")})

    rating = 5
    content = "probe-review-%s" % order_no[-8:]
    status, body = buyer.write("/order/%s/review" % order_no,
                               {"itemId": int(item_id), "rating": rating,
                                "content": content, "anonymous": 1})
    code = (body or {}).get("code")
    review_id = (body or {}).get("data")
    p.check("submit review on own completed order", status == 200 and code == 200 and review_id,
            {"http": status, "code": code, "msg": (body or {}).get("msg"), "data": review_id})
    if p.failed:
        return 1

    try:
        _, _, s_after = summary_of(buyer, prod_id)
        stat_after = db.rows("SELECT review_count, rating_sum, good_count "
                             "FROM t_product_rating_stat WHERE prod_id = %s" % prod_id)
        got = [int(x) for x in stat_after[0]] if stat_after else []
        p.check("aggregate bumped by exactly one",
                got == [base_count + 1, base_sum + rating, base_good + 1],
                {"before": [base_count, base_sum, base_good], "after": got})
        # The service rounds the average to one decimal, so compare against the same rounding.
        expected_avg = round((base_sum + rating) * 10.0 / (base_count + 1)) / 10.0
        p.check("summary average matches aggregate",
                abs(float(s_after.get("average") or 0) - expected_avg) < 0.001,
                {"summary": s_after, "expected_avg": expected_avg})
        p.check("summary count matches aggregate",
                int(s_after.get("count") or 0) == base_count + 1, s_after)

        _, list_body = buyer.call("/product/detail/%s/reviews?page=1&size=50" % prod_id)
        records = ((list_body or {}).get("data") or {}).get("records") or []
        mine = [r for r in records if str(r.get("reviewId")) == str(review_id)]
        p.check("new review appears in list", bool(mine),
                {"records": len(records), "matched": mine[:1]})
        if mine:
            row = mine[0]
            p.check("anonymous review masks nickname",
                    not row.get("nickname") or "*" in str(row.get("nickname"))
                    or str(row.get("nickname")).lower().startswith("anonymous")
                    or "匿名" in str(row.get("nickname")),
                    {"nickname": row.get("nickname")})
            p.check("list does not leak object keys or user ids",
                    "userId" not in row and "objectKey" not in row, sorted(row.keys()))

        status, body = buyer.write("/order/%s/review" % order_no,
                                   {"itemId": int(item_id), "rating": 4, "content": "dup"})
        p.check("duplicate review rejected 409", (body or {}).get("code") == 409,
                {"http": status, "code": (body or {}).get("code"), "msg": (body or {}).get("msg")})

        status, body = buyer.write("/order/%s/review" % order_no,
                                   {"itemId": int(item_id), "rating": 6, "content": "bad"})
        p.check("rating out of range rejected 400", (body or {}).get("code") == 400,
                {"http": status, "code": (body or {}).get("code")})

        foreign = db.rows("SELECT item_id FROM t_order_item WHERE order_no <> '%s' LIMIT 1" % order_no)
        if foreign:
            status, body = buyer.write("/order/%s/review" % order_no,
                                       {"itemId": int(foreign[0][0]), "rating": 5})
            p.check("item from another order rejected 404", (body or {}).get("code") == 404,
                    {"http": status, "code": (body or {}).get("code"), "msg": (body or {}).get("msg")})
        else:
            p.skip("item from another order rejected 404", "no other order item in DB")

        unfinished = db.rows("SELECT o.order_no, i.item_id FROM t_order o "
                             "JOIN t_order_item i ON i.order_no = o.order_no "
                             "WHERE o.user_id = %s AND o.order_status <> 2 LIMIT 1" % uid)
        if unfinished:
            status, body = buyer.write("/order/%s/review" % unfinished[0][0],
                                       {"itemId": int(unfinished[0][1]), "rating": 5})
            p.check("unfinished order rejected 400", (body or {}).get("code") == 400,
                    {"http": status, "code": (body or {}).get("code"), "msg": (body or {}).get("msg")})
        else:
            p.skip("unfinished order rejected 400", "buyer has no unfinished order")

        if args.other_phone and args.other_password:
            other = Client(args.backend)
            other.login(args.other_phone, args.other_password)
            status, body = other.write("/order/%s/review" % order_no,
                                       {"itemId": int(item_id), "rating": 1, "content": "cross"})
            p.check("another buyer cannot review this order", (body or {}).get("code") in (404, 403),
                    {"http": status, "code": (body or {}).get("code"), "msg": (body or {}).get("msg")})
        else:
            p.skip("another buyer cannot review this order", "--other-phone/--other-password not given")
    finally:
        if not args.keep:
            db.run("DELETE FROM t_product_review WHERE review_id = %s" % review_id)
            if stat_before:
                db.run("UPDATE t_product_rating_stat SET review_count = %d, rating_sum = %d, "
                       "good_count = %d WHERE prod_id = %s"
                       % (base_count, base_sum, base_good, prod_id))
            else:
                db.run("DELETE FROM t_product_rating_stat WHERE prod_id = %s" % prod_id)
            left = db.rows("SELECT COUNT(*) FROM t_product_review WHERE order_no = '%s'" % order_no)
            _, _, s_final = summary_of(buyer, prod_id)
            p.check("cleanup removed probe review", left and left[0][0] == "0", left)
            p.check("summary restored to baseline",
                    int(s_final.get("count") or 0) == base_count,
                    {"baseline": base_count, "final": s_final.get("count")})
        else:
            p.note("cleanup", "skipped (--keep); review_id=%s left in DB" % review_id)

    print("\n%d checks, %d failed, evidence: %s"
          % (len([r for r in p.results if "ok" in r]), len(p.failed), args.out))
    return 1 if p.failed else 0


if __name__ == "__main__":
    sys.exit(main())