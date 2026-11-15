#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RFQ S3 publish/list acceptance probe (self-cleaning, one-shot).

Proves on the running stack what S3 ships: buyers can publish an RFQ, list
their own RFQs, read one by number, and that the guard rails actually fire:

  a) baseline (read-only): t_rfq total row count, buyer user_id, the rfq:<day>
     sequence value (reported only -- sequence state is NEVER rolled back).
  b) one legal publish (description / quantity / unit / future deadline):
     HTTP 200 + body code=200, rfqNo matches ^RFQ\\d{14}$, status=PUBLISHED,
     maxQuotes=10; the DB row exists with the right user_id/title/quantity/
     unit/deadline, and the 8-digit date prefix agrees with createTime.
  c) GET /rfq/my contains that rfqNo exactly once; GET /rfq/{rfqNo} returns
     the same order.
  d) POST /rfq without X-CSRF-TOKEN -> body code=403 (CSRF interceptor live).
  e) anonymous (brand-new cookie-less client) GET /rfq/{rfqNo} -> HTTP 401 or
     body code=401.
  f) merchant session GET /rfq/{rfqNo} -> body code=404 (detail is narrowed by
     user_id, so a merchant cannot read a buyer's RFQ).
  g) compliance: title containing the banned claim -> body code=400; a past
     deadline -> body code=400; neither writes a row.
  h) anti-abuse: 4 publishes with one dedicated title all succeed, the 5th ->
     body code=400 and message contains the repeat-RFQ wording; the DB holds
     exactly 4 rows for that title.
  i) cleanup: SQL-delete ONLY the rfq_no values recorded by this run, then
     verify t_rfq is back to the baseline count and no quote/event rows hang
     off the probe rfq_ids. t_sys_sequence rows are deliberately left alone
     (they are system numbering state, not probe data).
  j) the overdue sweep (expireOverdue, 10-minute schedule) is NOT exercised on
     the live stack -- it is covered by the RfqServiceTest unit tests and the
     scheduling interval is intentionally left untouched.

POST /rfq is throttled at 10 requests / 60 s per client IP (AuthRateLimit
interceptor, Policy(10, 60)). This probe issues exactly 9 POSTs; if any of
them still returns 429 the request is recorded, the probe waits out the
one-minute window and retries, so a throttle does not abort the run.

Credentials are passed as arguments and never printed. DB access goes through
`docker exec` with the container's own environment variables, so no password
appears in this script, the command line, or the output.

Usage:
  python scripts/rfq/rfq_publish_probe.py \\
      --buyer-phone 13900000001 --buyer-password 'xxx' \\
      --merchant-phone 13700000002 --merchant-password 'xxx' \\
      [--backend http://127.0.0.1:8080/api] [--mysql-container shopping-mysql-1] \\
      [--keep] [--out reports/rfq-publish-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure,
2 precondition missing (account missing / 2FA enabled),
3 docker or DB unreachable.

Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the
UTF-8 evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
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

QTY = Decimal("25")
UNIT = "kg"
RFQ_NO_RE = re.compile(r"^RFQ\d{14}$")
RATE_LIMIT_WAIT_S = 62


class Client:
    """Cookie-jar session with CSRF header, same shape as checkout_smoke_probe."""

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
            "probe": "rfq-publish",
            "purpose": "RFQ S3 buyer publish/list/detail + guard rails, live stack",
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
            print("       detail: %s" % json.dumps(detail, ensure_ascii=True)[:800])
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
    """Sleep until the next whole minute plus a small margin (rate-limit windows
    are aligned on floor(now/60s) by the server)."""
    return int(60 - (time.time() % 60)) + 2


def publish(p: Probe, client: Client, payload: dict, csrf: bool = True,
            attempts: int = 4):
    """POST /rfq with 429 tolerance: record the throttle, wait out the window,
    then retry. Returns (http, body, throttle_hits)."""
    hits = []
    st, bd = -1, None
    for attempt in range(1, attempts + 1):
        st, bd = client.write("/rfq", payload, csrf=csrf)
        code = (bd or {}).get("code")
        if st == 429 or code == 429:
            wait = seconds_to_next_window()
            hits.append({"attempt": attempt, "http": st, "code": code, "wait_s": wait})
            p.evidence["rate_limit"].append({"path": "/rfq", "attempt": attempt,
                                             "http": st, "wait_s": wait})
            p.note("429 on POST /rfq, waiting for the next window then retrying",
                   {"attempt": attempt, "wait_s": wait,
                    "retry_after": str(wait) + "s"})
            time.sleep(wait)
            continue
        break
    return st, bd, hits


def rfq_payload(title: str, description: str, deadline: str, quantity=QTY,
                unit=UNIT, prod_id=None) -> dict:
    body = {"title": title, "description": description,
            "quantity": str(quantity), "unit": unit,
            "quoteDeadline": deadline}
    if prod_id is not None:
        body["prodId"] = prod_id
    return body


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--buyer-phone", required=True)
    ap.add_argument("--buyer-password", required=True)
    ap.add_argument("--merchant-phone", required=True)
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true", help="skip cleanup (leaves probe RFQs behind)")
    ap.add_argument("--out", default="reports/rfq-publish-evidence.json")
    args = ap.parse_args()

    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]
    title_main = "RFQ-S3-publish-probe-%s" % run_id
    title_repeat = "RFQ-S3-repeat-probe-%s" % run_id
    title_banned = "RFQ-S3-\u5408\u89c4-probe-\u4fdd\u8bc1\u6210\u4ea4-%s" % run_id
    title_past = "RFQ-S3-past-deadline-probe-%s" % run_id

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "buyer_suffix": args.buyer_phone[-4:],
        "merchant_suffix": args.merchant_phone[-4:],
        "run_id": run_id,
        "qty": str(QTY),
        "unit": UNIT,
    })
    db = Db(args.mysql_container)

    try:
        db.run("SELECT 1")
    except SystemExit as e:
        print(str(e))
        return p.finish(3)

    # ---------------- a) read-only baseline ----------------
    buyer_rows = db.rows("SELECT user_id, role_id FROM t_user WHERE phone = %s LIMIT 1"
                         % q(args.buyer_phone))
    merchant_rows = db.rows("SELECT user_id, role_id FROM t_user WHERE phone = %s LIMIT 1"
                            % q(args.merchant_phone))
    if not buyer_rows or not merchant_rows:
        p.note("precondition", {"reason": "demo account missing",
                                "buyer_found": bool(buyer_rows),
                                "merchant_found": bool(merchant_rows)})
        print("precondition missing: demo buyer/merchant not found in t_user")
        return p.finish(2)
    buyer_uid, buyer_role = buyer_rows[0][0], buyer_rows[0][1]
    merchant_uid, merchant_role = merchant_rows[0][0], merchant_rows[0][1]

    base_total = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
    base_buyer = int(db.rows("SELECT COUNT(*) FROM t_rfq WHERE user_id = %s"
                             % buyer_uid)[0][0])
    seq_key = "rfq:" + time.strftime("%Y%m%d")
    seq_base_rows = db.rows("SELECT current_val FROM t_sys_sequence WHERE seq_key = %s"
                            % q(seq_key))
    seq_base = seq_base_rows[0][0] if seq_base_rows else "<no-row>"

    p.note("baseline", {
        "t_rfq_total": base_total,
        "t_rfq_buyer_rows": base_buyer,
        "buyer_user_id": buyer_uid, "buyer_role_id": buyer_role,
        "merchant_user_id": merchant_uid, "merchant_role_id": merchant_role,
        "sequence_key_host_date": seq_key,
        "sequence_value_host_date": seq_base,
        "run_id": run_id,
        "thresholds": {"max_quotes": 10, "repeat_title_reject_from": 5,
                       "rate_limit_post_rfq_per_min": 10},
    })
    p.check("baseline: demo buyer and merchant are distinct accounts",
            buyer_uid != merchant_uid,
            {"buyer_user_id": buyer_uid, "merchant_user_id": merchant_uid})
    p.check("baseline: no leftover probe rows with this run's titles",
            int(db.rows(
                "SELECT COUNT(*) FROM t_rfq WHERE title IN (%s,%s,%s,%s)"
                % (q(title_main), q(title_repeat), q(title_banned), q(title_past))
            )[0][0]) == 0,
            {"run_id": run_id})

    buyer = Client(args.backend)
    try:
        buyer.login(args.buyer_phone, args.buyer_password)
    except SystemExit as e:
        print(str(e))
        p.check("buyer login", False, {"error": str(e)})
        return p.finish(1)

    deadline = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() + 7 * 86400))
    past_deadline = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() - 3600))

    created_nos = []          # every rfq_no this run caused to exist
    deleted = False
    probe_ids = []
    # The numbering key carries the BACKEND's date, which may differ from the
    # host date across a timezone/midnight boundary; it is derived from the
    # first rfqNo this run receives.
    seq_key_backend = None

    def record(bd):
        no = ((bd or {}).get("data") or {}).get("rfqNo")
        if no and no not in created_nos:
            created_nos.append(no)
        return no

    try:
        # ---------------- b) legal publish ----------------
        payload_main = rfq_payload(title_main,
                                   "rfq-s3-probe-live-publish-%s-desc" % run_id,
                                   deadline)
        st, bd, hits = publish(p, buyer, payload_main)
        no_main = record(bd)
        data = (bd or {}).get("data") or {}
        p.check("b: POST /rfq accepted (HTTP 200 / body code 200)",
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": no_main,
                 "rate_limit_hits": hits})
        if not no_main:
            p.note("b: no rfqNo returned", {"body": bd})
            raise StopProbe(1)

        p.check("b: rfqNo matches ^RFQ\\d{14}$",
                bool(RFQ_NO_RE.match(str(no_main))), {"rfqNo": no_main})
        p.check("b: status == PUBLISHED and maxQuotes == 10",
                data.get("status") == "PUBLISHED" and data.get("maxQuotes") == 10,
                {"status": data.get("status"), "maxQuotes": data.get("maxQuotes")})
        p.check("b: live publish round-trips description/quantity/unit/deadline",
                str(data.get("description") or "") == payload_main["description"]
                and dec(data.get("quantity")) == QTY
                and data.get("unit") == UNIT
                and str(data.get("quoteDeadline") or "").startswith(deadline[:19]),
                {"description_ok": str(data.get("description") or "")
                 == payload_main["description"],
                 "quantity": data.get("quantity"), "unit": data.get("unit"),
                 "quoteDeadline": data.get("quoteDeadline"),
                 "sent_deadline": deadline})
        create_time = str(data.get("createTime") or "")
        p.check("b: rfqNo date prefix agrees with createTime (yyyyMMdd + 6 digits)",
                len(create_time) >= 10
                and str(no_main)[3:11] == create_time[:10].replace("-", ""),
                {"rfqNo": no_main, "createTime": create_time})

        seq_key_backend = "rfq:" + str(no_main)[3:11]
        seq_rows = db.rows("SELECT current_val FROM t_sys_sequence WHERE seq_key = %s"
                           % q(seq_key_backend))
        p.note("numbering (backend date key)", {
            "sequence_key": seq_key_backend,
            "current_val": seq_rows[0][0] if seq_rows else "<no-row>",
            "rfqNo_suffix": str(no_main)[11:],
            "note": "sequence rows are system numbering state and are never deleted",
        })

        row = db.rows(
            "SELECT rfq_id, user_id, title, description, quantity, unit, "
            "DATE_FORMAT(quote_deadline,'%%Y-%%m-%%dT%%H:%%i:%%s'), max_quotes, status "
            "FROM t_rfq WHERE rfq_no = %s" % q(no_main))
        p.note("b: DB row for the legal publish", row[0] if row else None)
        ok_row = len(row) == 1
        if ok_row:
            r = row[0]
            probe_ids.append(r[0])
            ok_row = (r[1] == str(buyer_uid) and r[2] == title_main
                      and dec(r[4]) == QTY and r[5] == UNIT
                      and r[6] == deadline[:19] and r[7] == "10" and r[8] == "PUBLISHED")
        p.check("b: DB row exists with buyer user_id, title, qty/unit/deadline, "
                "max_quotes=10, PUBLISHED",
                ok_row, {"row": row[0] if row else None,
                         "expected_user_id": buyer_uid, "expected_title": title_main,
                         "expected_deadline": deadline})
        p.check("b: t_rfq total == baseline + 1",
                int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0]) == base_total + 1,
                {"baseline": base_total})

        # ---------------- c) my list + own detail ----------------
        st, bd = buyer.call("/rfq/my")
        items = (bd or {}).get("data") or []
        mine = [i for i in items if i.get("rfqNo") == no_main]
        p.check("c: GET /rfq/my contains this rfqNo exactly once",
                st == 200 and (bd or {}).get("code") == 200 and len(mine) == 1,
                {"http": st, "code": (bd or {}).get("code"),
                 "matches": len(mine), "my_count": len(items)})
        p.check("c: /rfq/my item matches the published order",
                bool(mine) and mine[0].get("title") == title_main
                and mine[0].get("status") == "PUBLISHED"
                and mine[0].get("maxQuotes") == 10
                and dec(mine[0].get("quantity")) == QTY,
                {"item": mine[0] if mine else None})

        st, bd = buyer.call("/rfq/%s" % no_main)
        detail = (bd or {}).get("data") or {}
        p.check("c: GET /rfq/{rfqNo} returns the same order",
                st == 200 and (bd or {}).get("code") == 200
                and detail.get("rfqNo") == no_main
                and detail.get("title") == title_main
                and detail.get("status") == "PUBLISHED"
                and detail.get("maxQuotes") == 10,
                {"http": st, "code": (bd or {}).get("code"),
                 "rfqNo": detail.get("rfqNo"), "title": detail.get("title")})

        # ---------------- d) CSRF guard ----------------
        payload_csrf = rfq_payload("RFQ-S3-csrf-probe-%s" % run_id,
                                   "rfq-s3-probe-csrf-%s-desc" % run_id, deadline)
        st, bd, hits = publish(p, buyer, payload_csrf, csrf=False)
        leaked = record(bd)
        p.check("d: POST /rfq without X-CSRF-TOKEN rejected with code 403",
                st == 403 or (bd or {}).get("code") == 403,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": leaked,
                 "rate_limit_hits": hits})
        p.check("d: the CSRF-rejected request created no row",
                leaked is None
                and int(db.rows("SELECT COUNT(*) FROM t_rfq WHERE title = %s"
                                % q("RFQ-S3-csrf-probe-%s" % run_id))[0][0]) == 0,
                {"rfqNo": leaked})

        # ---------------- e) anonymous read ----------------
        anon = Client(args.backend)
        st, bd = anon.call("/rfq/%s" % no_main)
        body_code = (bd or {}).get("code")
        p.check("e: anonymous GET /rfq/{rfqNo} -> HTTP 401 or body code 401",
                st == 401 or body_code == 401,
                {"http": st, "code": body_code, "message": (bd or {}).get("message")})

        # ---------------- f) merchant cannot read the buyer's RFQ ----------------
        merchant = Client(args.backend)
        try:
            merchant.login(args.merchant_phone, args.merchant_password)
        except SystemExit as e:
            p.check("f: merchant login", False, {"error": str(e)})
            merchant = None
        if merchant is not None:
            st, bd = merchant.call("/rfq/%s" % no_main)
            p.check("f: merchant session GET /rfq/{rfqNo} -> body code 404 "
                    "(detail narrowed by user_id)",
                    (bd or {}).get("code") == 404 and (bd or {}).get("data") is None,
                    {"http": st, "code": (bd or {}).get("code"),
                     "message": (bd or {}).get("message"),
                     "data": (bd or {}).get("data")})
            st, bd = merchant.call("/rfq/my")
            merchant_items = (bd or {}).get("data") or []
            p.check("f: merchant /rfq/my does not expose the buyer's rfqNo",
                    (bd or {}).get("code") == 200
                    and all(i.get("rfqNo") != no_main for i in merchant_items),
                    {"http": st, "code": (bd or {}).get("code"),
                     "merchant_item_count": len(merchant_items)})

        # ---------------- g) compliance + deadline guard ----------------
        total_before_g = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
        st, bd, hits = publish(p, buyer, rfq_payload(title_banned,
                                                    "rfq-s3-probe-compliance-%s-desc" % run_id,
                                                    deadline))
        leaked = record(bd)
        p.check("g: banned claim in title (\"baozheng chengjiao\") -> body code 400",
                (bd or {}).get("code") == 400,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": leaked,
                 "rate_limit_hits": hits})
        p.check("g: banned-claim request created no row",
                leaked is None
                and int(db.rows("SELECT COUNT(*) FROM t_rfq WHERE title = %s"
                                % q(title_banned))[0][0]) == 0,
                {"rfqNo": leaked})

        st, bd, hits = publish(p, buyer, rfq_payload(title_past,
                                                    "rfq-s3-probe-past-%s-desc" % run_id,
                                                    past_deadline))
        leaked_past = record(bd)
        p.check("g: past quoteDeadline -> body code 400",
                (bd or {}).get("code") == 400,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"), "rfqNo": leaked_past,
                 "sent_deadline": past_deadline, "rate_limit_hits": hits})
        total_after_g = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
        p.check("g: both rejected publishes left t_rfq unchanged",
                total_after_g == total_before_g == base_total + 1,
                {"before": total_before_g, "after": total_after_g,
                 "baseline": base_total})

        # ---------------- h) anti-abuse: 4 ok, 5th rejected ----------------
        repeat_nos = []
        repeat_codes = []
        for i in range(1, 5):
            payload = rfq_payload(title_repeat,
                                  "rfq-s3-probe-repeat-%s-%d-desc" % (run_id, i),
                                  deadline)
            st, bd, hits = publish(p, buyer, payload)
            no = record(bd)
            repeat_nos.append(no)
            repeat_codes.append({"i": i, "http": st, "code": (bd or {}).get("code"),
                                 "rfqNo": no, "rate_limit_hits": hits})
            p.check("h: repeat publish #%d of 4 succeeded (code 200, rfqNo present)" % i,
                    st == 200 and (bd or {}).get("code") == 200 and bool(no),
                    repeat_codes[-1])
            if not no:
                break

        st, bd, hits = publish(p, buyer, rfq_payload(
            title_repeat, "rfq-s3-probe-repeat-%s-5-desc" % run_id, deadline))
        leaked_5 = record(bd)
        fifth_code = (bd or {}).get("code")
        fifth_msg = str((bd or {}).get("message") or "")
        p.check("h: 5th publish with the same title -> body code 400",
                fifth_code == 400,
                {"http": st, "code": fifth_code, "message": fifth_msg,
                 "rfqNo": leaked_5, "rate_limit_hits": hits})
        p.check("h: 5th publish message contains the repeat-RFQ wording",
                "重复询价" in fifth_msg, {"message": fifth_msg})
        p.check("h: 5th publish created no row", leaked_5 is None,
                {"rfqNo": leaked_5})

        db_count = int(db.rows(
            "SELECT COUNT(*) FROM t_rfq WHERE user_id = %s AND title = %s "
            "AND status <> 'CANCELLED'" % (buyer_uid, q(title_repeat)))[0][0])
        p.check("h: DB holds exactly 4 rows for the dedicated repeat title",
                db_count == 4,
                {"db_count": db_count, "rfqNos": repeat_nos, "codes": repeat_codes})
        p.check("h: exactly 4 distinct rfqNos came back for the repeat title",
                len([n for n in repeat_nos if n]) == 4 and len(set(repeat_nos)) == 4,
                {"rfqNos": repeat_nos})
        p.check("h: t_rfq total == baseline + 5 (1 legal + 4 repeat-threshold)",
                int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0]) == base_total + 5,
                {"baseline": base_total})

        p.note("threshold observed", {
            "policy": "reject when existing same-title rows + 1 >= 5 (Mapper countSameTitle, status<>CANCELLED)",
            "accepted": 4,
            "fifth": {"code": fifth_code, "message": fifth_msg},
            "db_rows_for_title": db_count,
        })
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
            cleanup["rfq_nos_left_behind"] = created_nos
        else:
            cleanup["mode"] = "cleanup"
            cleanup["recorded_rfq_nos"] = list(created_nos)
            cleanup["t_rfq_baseline"] = base_total
            try:
                if created_nos:
                    # Delete ONLY this run's rows (rfq_no + buyer user_id).
                    ids = db.rows(
                        "SELECT rfq_id, rfq_no FROM t_rfq WHERE user_id = %s "
                        "AND rfq_no IN (%s)" % (buyer_uid, sql_in(created_nos)))
                    cleanup["matched_rows"] = ids
                    probe_ids = sorted({r[0] for r in ids} | set(probe_ids))
                    safe_run(p, db, "DELETE FROM t_rfq WHERE user_id = %s "
                             "AND rfq_no IN (%s)"
                             % (buyer_uid, sql_in(created_nos)), "t_rfq(probe rfq_nos)")
                    deleted = True
                    cleanup["deleted_scope"] = [
                        "t_rfq WHERE user_id=<buyer> AND rfq_no IN (this run's rfqNos)",
                    ]
                    cleanup["sequence_untouched"] = (
                        "t_sys_sequence rfq:<day> deliberately NOT deleted "
                        "(system numbering state, not probe data)")
                    left = db.rows("SELECT rfq_no FROM t_rfq WHERE user_id = %s "
                                   "AND rfq_no IN (%s)"
                                   % (buyer_uid, sql_in(created_nos)))
                    p.check("cleanup: every recorded probe rfqNo is gone",
                            not left, {"left": left})
                else:
                    cleanup["reason"] = "no probe RFQ was created"

                final_total = int(db.rows("SELECT COUNT(*) FROM t_rfq")[0][0])
                final_buyer = int(db.rows(
                    "SELECT COUNT(*) FROM t_rfq WHERE user_id = %s" % buyer_uid)[0][0])
                key_final = seq_key_backend or seq_key
                seq_rows = db.rows("SELECT current_val FROM t_sys_sequence "
                                   "WHERE seq_key = %s" % q(key_final))
                cleanup["final"] = {
                    "t_rfq_total": final_total, "t_rfq_baseline": base_total,
                    "buyer_rows": final_buyer, "buyer_rows_baseline": base_buyer,
                    "sequence_key": key_final,
                    "sequence_final_value": seq_rows[0][0] if seq_rows else "<no-row>",
                    "sequence_note": "t_sys_sequence row kept on purpose "
                                     "(system numbering state; never rolled back)",
                }
                p.check("cleanup: t_rfq total back to baseline",
                        final_total == base_total,
                        {"baseline": base_total, "final": final_total})
                p.check("cleanup: buyer's own row count back to baseline",
                        final_buyer == base_buyer,
                        {"baseline": base_buyer, "final": final_buyer})

                if probe_ids:
                    orphan = db.rows(
                        "SELECT (SELECT COUNT(*) FROM t_rfq_quote WHERE rfq_id IN (%s)) + "
                        "(SELECT COUNT(*) FROM t_rfq_event WHERE rfq_id IN (%s))"
                        % (sql_in(probe_ids), sql_in(probe_ids)))
                    p.check("cleanup: no orphan quote/event rows for the probe rfq_ids",
                            bool(orphan) and orphan[0][0] == "0",
                            {"orphan_count": orphan[0][0] if orphan else None,
                             "probe_rfq_ids": probe_ids})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("cleanup: final state verification failed", False,
                        {"error": str(e)[:300], "deleted": deleted})

        # j) explicitly out of live scope
        p.note("expiry sweep scope", {
            "endpoint": "RfqService.expireOverdue / scheduledExpire",
            "live_verified": False,
            "reason": "10-minute scheduled window; covered by RfqServiceTest unit tests "
                      "(expireOverdue). Scheduling interval intentionally untouched.",
        })
        p.note("rfqNo samples", {"rfqNos": created_nos[:6],
                                 "pattern": "^RFQ\\d{14}$"})

    code = 1 if p.failed else 0
    return p.finish(code)


class StopProbe(Exception):
    """Abort the main flow but still run the cleanup block and write evidence."""

    def __init__(self, code: int):
        super().__init__("probe stopped")
        self.code = code


if __name__ == "__main__":
    sys.exit(main())
