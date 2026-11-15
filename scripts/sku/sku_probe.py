#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SKU multi-spec foundation acceptance probe (one-shot, self-cleaning, live stack).

Proves the SKU foundation slice end to end on the RUNNING API and DB, and that
its guard rails fire without leaving residue:

  1. PRECOND  demo merchant login; resolve mer_id from the phone via the DB; pick
              one on-sale product (status=1, deleted=0) of that merchant that has
              NO existing spec/value/sku rows, and record baseline row counts of
              t_product_spec / t_product_spec_value / t_product_sku for it.
  2. SAVE     2 dimensions x 2 values = 4 SKUs (colour red/blue x size S/L,
              price 9.90, stock 10, and 4 DISTINCT skuCode values
              SKU-R-S / SKU-R-L / SKU-B-S / SKU-B-L) via
              POST /product/my/sku/{prodId}/save with the session CSRF header ->
              body code 200; the response tree carries 2 specs and 4 skus.
  3. DB       t_product_spec +2 / t_product_spec_value +4 / t_product_sku +4 for
              that prodId; the 4 spec_value_ids sets are mutually distinct; every
              row is price 9.90 stock 10; the 4 sku_code values round-trip.
  4. RELOAD   GET /product/my/sku/{prodId} -> 2 specs, 4 skus, every specText
              is "dimensionName:value" pairs that resolve inside the returned tree
              (covers the Task 2 carry-forward: load -> buildView -> specText), and
              the 4 sku_code values come back one per SKU.
  5. GUARDS   five constraint POSTs (dims > 3, duplicate combination, price 0,
              stock -1, duplicate skuCode) plus one compliance POST whose spec
              value carries a real FORBIDDEN_CLAIMS token read out of
              TechComplianceGuard.java. Each must answer body code 400 and leave
              the three tables untouched.
  6. OWNERSHIP save to a product owned by ANOTHER merchant -> body 403;
              save to a non-existent prodId -> body 404.
  7. GATE      anonymous GET -> body 401; buyer session save -> body 403
              (BUYER lacks product:publish). The buyer leg is skipped with a NOTE
              when no non-merchant demo account is available.
  8. EMPTY     save {specs:[], skus:[]} -> body 200 and the three tables are back
              to baseline for that prodId (cleared).
  9. CLEANUP   delete ONLY this run's rows for the target prodId (sku -> value ->
              spec) and verify the three counts are back to baseline.

Carry-forward evidence (Task 1) is recorded as checks/notes:
  - SkuSchemaInitializer ran at boot: the container log shows a clean "Started
    LanlinkShoppingApplication" with no bean-creation failure, and the three
    tables exist. The backend is NOT restarted to re-prove it.
  - @MapperScan covers module.sku.mapper: the successful save + reload DB round
    trip IS the proof (the three mappers really ran).
  - sql/schema.sql still carries the SKU region (parallel-actor clobber guard).

Rate limits: /product/my/sku/* is throttled at 20 req / 60 s per client IP and
path (AuthRateLimitInterceptor). This probe issues ~10 writes, and every write
goes through a 429-tolerant helper that waits out the current minute window.

Credentials are arguments and are never printed. DB access runs through
`docker exec` using the container's own environment variables, so no password
appears in this script, on the command line, or in the output.

Usage:
  python scripts/sku/sku_probe.py \\
      --backend http://127.0.0.1:8080/api \\
      --merchant-phone 13700000002 --merchant-password 'xxx' \\
      [--buyer-phone ... --buyer-password ...] \\
      [--mysql-container shopping-mysql-1] [--backend-container shopping-backend-1] \\
      [--keep] [--out reports/sku-probe-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure,
2 precondition missing (account/merchant/product), 3 docker or DB unreachable.

Console output is ASCII on purpose (Windows GBK consoles mangle CJK); the UTF-8
evidence trail goes to --out.
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

PRICE = Decimal("9.90")
STOCK = 10
DIM_A = "颜色"
DIM_B = "尺寸"
VAL_A = ["红", "蓝"]
VAL_B = ["S", "L"]
NONEXISTENT_PROD = 999999999
SKU_BASE = "/product/my/sku/%s"
SKU_SAVE = "/product/my/sku/%s/save"
TABLES = ("t_product_spec", "t_product_spec_value", "t_product_sku")
COMPLIANCE_SRC = os.path.join(
    "backend", "src", "main", "java", "com", "lanlink", "shopping",
    "module", "techservice", "service", "TechComplianceGuard.java")
SCHEMA_SQL = os.path.join("backend", "src", "main", "resources", "sql", "schema.sql")


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
            "probe": "sku-foundation",
            "purpose": "SKU multi-spec foundation: merchant saves a 2x2 spec tree, "
                       "the three tables and the reloaded view agree, every guard "
                       "rail (validation, ownership, permission) fires, the empty "
                       "tree clears, and cleanup restores the baseline -- live stack",
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


# ---------------------------------------------------------------- payloads --

def ref(spec_index: int, value_index: int) -> dict:
    return {"specIndex": spec_index, "valueIndex": value_index}


def tree(dims, price=PRICE, stock=STOCK, sku_code_prefix=None, sku_codes=None):
    """Build a SkuTreeSaveRequest for a list of (name, [values]) dimensions.

    sku_code_prefix assigns "<prefix>-<n>" (n from 1) per combination; sku_codes
    assigns an explicit list positionally (both cover the skuCode round trip).
    """
    specs = [{"name": name, "values": list(values)} for name, values in dims]
    combos = [[]]
    for si, (_name, values) in enumerate(dims):
        combos = [combo + [ref(si, vi)] for combo in combos
                  for vi in range(len(values))]
    skus = []
    for i, combo in enumerate(combos):
        item = {"values": combo, "price": str(price), "stock": stock}
        if sku_codes is not None and i < len(sku_codes):
            item["skuCode"] = sku_codes[i]
        elif sku_code_prefix:
            item["skuCode"] = "%s-%d" % (sku_code_prefix, i + 1)
        skus.append(item)
    return {"specs": specs, "skus": skus}


# 笛卡尔积顺序：(红,S) (红,L) (蓝,S) (蓝,L) —— 编码与组合一一对应
MAIN_SKU_CODES = ["SKU-R-S", "SKU-R-L", "SKU-B-S", "SKU-B-L"]
MAIN_TREE = tree([(DIM_A, VAL_A), (DIM_B, VAL_B)], sku_codes=MAIN_SKU_CODES)


def forbidden_claim() -> str:
    """Read the real trigger token out of TechComplianceGuard.FORBIDDEN_CLAIMS.

    Never guess: if the constant cannot be located or parsed, the caller records
    a NOTE and skips the compliance leg instead of inventing a trigger word.
    """
    try:
        with open(COMPLIANCE_SRC, "r", encoding="utf-8") as fh:
            src = fh.read()
    except OSError:
        return ""
    match = re.search(r"FORBIDDEN_CLAIMS\s*=\s*List\.of\((.*?)\);", src, re.S)
    if not match:
        return ""
    # Strip // line comments so a commented-out example never becomes the trigger.
    body = "\n".join(line.split("//")[0] for line in match.group(1).splitlines())
    tokens = re.findall(r'"([^"]+)"', body)
    return tokens[0] if tokens else ""


def counts(db: Db, prod_id) -> dict:
    """Row counts of the three SKU tables for one prodId."""
    pid = q(prod_id)
    return {
        "t_product_spec": int(db.rows(
            "SELECT COUNT(*) FROM t_product_spec WHERE prod_id = %s" % pid)[0][0]),
        "t_product_spec_value": int(db.rows(
            "SELECT COUNT(*) FROM t_product_spec_value WHERE prod_id = %s" % pid)[0][0]),
        "t_product_sku": int(db.rows(
            "SELECT COUNT(*) FROM t_product_sku WHERE prod_id = %s" % pid)[0][0]),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--merchant-phone", required=True)
    ap.add_argument("--merchant-password", required=True)
    ap.add_argument("--buyer-phone", default="")
    ap.add_argument("--buyer-password", default="")
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--backend-container", default="shopping-backend-1")
    ap.add_argument("--keep", action="store_true",
                    help="skip cleanup (leaves the probe spec tree behind)")
    ap.add_argument("--out", default="reports/sku-probe-evidence.json")
    args = ap.parse_args()

    run_id = time.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "backend_container": args.backend_container,
        "merchant_suffix": args.merchant_phone[-4:],
        "buyer_suffix": (args.buyer_phone or "")[-4:],
        "run_id": run_id,
        "price": str(PRICE),
        "stock": STOCK,
        "dimensions": [[DIM_A, VAL_A], [DIM_B, VAL_B]],
        "sku_codes": MAIN_SKU_CODES,
        "nonexistent_prod_id": NONEXISTENT_PROD,
        "rate_limit": "POST /product/my/sku/* -> 20 req / 60 s per client IP",
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
    base = {}
    prod = None
    mer_id = None
    merchant = None
    buyer = None
    stop_code = 0
    roundtrip_ok = False

    try:
        # ---------------- 1) read-only baseline ----------------
        merchant = login_client(args.backend, args.merchant_phone,
                                args.merchant_password, p,
                                "1: merchant login (demo merchant, no 2FA)")

        user_rows = db.rows("SELECT user_id, role_id FROM t_user WHERE phone = %s "
                            "LIMIT 1" % q(args.merchant_phone))
        mer_rows = (db.rows("SELECT mer_id, review_status FROM t_merchant "
                            "WHERE user_id = %s AND deleted = 0 ORDER BY mer_id LIMIT 1"
                            % q(user_rows[0][0])) if user_rows else [])
        if not user_rows or not mer_rows:
            p.note("precondition", {
                "reason": "demo merchant missing from t_user/t_merchant",
                "user_found": bool(user_rows), "merchant_found": bool(mer_rows)})
            print("precondition missing: demo merchant not found")
            raise StopProbe(2)
        mer_id = mer_rows[0][0]
        review_status = mer_rows[0][1]

        prod_rows = db.rows(
            "SELECT p.prod_id, p.title FROM t_product p WHERE p.mer_id = %s "
            "AND p.status = 1 AND p.deleted = 0 "
            "AND NOT EXISTS (SELECT 1 FROM t_product_spec s WHERE s.prod_id = p.prod_id) "
            "AND NOT EXISTS (SELECT 1 FROM t_product_sku k WHERE k.prod_id = p.prod_id) "
            "ORDER BY p.prod_id ASC LIMIT 1" % q(mer_id))
        if not prod_rows:
            p.note("precondition", {
                "reason": "demo merchant has no on-sale product without existing SKU rows",
                "mer_id": mer_id})
            print("precondition missing: no clean on-sale product for the demo merchant")
            raise StopProbe(2)
        prod = prod_rows[0]
        base = counts(db, prod[0])
        baseline_clean = all(v == 0 for v in base.values())

        foreign_rows = db.rows(
            "SELECT prod_id, mer_id FROM t_product WHERE mer_id <> %s "
            "AND deleted = 0 ORDER BY prod_id ASC LIMIT 1" % q(mer_id))
        foreign = foreign_rows[0] if foreign_rows else None
        foreign_base = counts(db, foreign[0]) if foreign else None

        p.note("baseline", {
            "merchant_user_id": user_rows[0][0],
            "merchant_role_id": user_rows[0][1],
            "demo_mer_id": mer_id,
            "merchant_review_status": review_status,
            "probe_product": {"prod_id": prod[0], "title": prod[1]},
            "sku_row_counts": base,
            "baseline_is_clean": baseline_clean,
            "foreign_product": ({"prod_id": foreign[0], "mer_id": foreign[1]}
                                if foreign else None),
            "run_id": run_id,
        })
        p.check("1: demo merchant resolved to an approved mer_id", review_status == "1",
                {"mer_id": mer_id, "review_status": review_status})
        p.check("1: target product has no pre-existing spec/value/sku rows "
                "(baseline all zero, cleanup restores it trivially)",
                baseline_clean, {"prod_id": prod[0], "counts": base})
        p.check("1: a foreign-merchant product exists for the ownership leg",
                foreign is not None, {"foreign_product": foreign})

        # ---------------- 2) save the 2 x 2 tree ----------------
        st, bd, hits = post_retry(p, merchant, SKU_SAVE % prod[0], MAIN_TREE,
                                  "POST save[main]")
        data = (bd or {}).get("data") or {}
        save_specs = data.get("specs") or []
        save_skus = data.get("skus") or []
        ok_save = (st == 200 and (bd or {}).get("code") == 200
                   and len(save_specs) == 2 and len(save_skus) == 4)
        p.note("2: save response tree", {
            "specs": [{"name": s.get("name"),
                       "values": [v.get("value") for v in (s.get("values") or [])]}
                      for s in save_specs],
            "skus": [{"specText": k.get("specText"),
                      "specValueIds": k.get("specValueIds"),
                      "price": k.get("price"), "stock": k.get("stock")}
                     for k in save_skus],
        })
        p.check("2: POST /product/my/sku/{prodId}/save -> body code 200 with "
                "2 specs and 4 skus", ok_save,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message"),
                 "specs": len(save_specs), "skus": len(save_skus),
                 "rate_limit_hits": hits})
        if not ok_save:
            raise StopProbe(1)

        # ---------------- 3) DB verification ----------------
        after_main = counts(db, prod[0])
        expected = {
            "t_product_spec": base["t_product_spec"] + 2,
            "t_product_spec_value": base["t_product_spec_value"] + 4,
            "t_product_sku": base["t_product_sku"] + 4,
        }
        p.check("3: t_product_spec +2 / t_product_spec_value +4 / t_product_sku +4 "
                "for the target prodId", after_main == expected,
                {"baseline": base, "after_save": after_main, "expected": expected})

        combo_rows = db.rows("SELECT spec_value_ids FROM t_product_sku "
                             "WHERE prod_id = %s" % q(prod[0]))
        combos = [row[0] for row in combo_rows]
        distinct = len(set(combos)) == len(combos) == 4
        all_ids = [part for combo in combos for part in combo.split(",")]
        value_rows = db.rows(
            "SELECT value_id FROM t_product_spec_value WHERE prod_id = %s"
            % q(prod[0]))
        known_ids = {row[0] for row in value_rows}
        p.check("3: the 4 spec_value_ids combinations are mutually distinct",
                distinct, {"combos": combos})
        p.check("3: every spec_value_id in the SKUs resolves to a value row of "
                "this product (2 dims x 2 values, each id used twice)",
                bool(all_ids) and set(all_ids) == known_ids
                and len(set(all_ids)) == 4
                and all(all_ids.count(v) == 2 for v in set(all_ids)),
                {"sku_value_ids": all_ids, "product_value_ids": sorted(known_ids)})

        price_rows = db.rows(
            "SELECT price, stock FROM t_product_sku WHERE prod_id = %s" % q(prod[0]))
        p.check("3: all 4 SKU rows carry price 9.90 and stock 10",
                len(price_rows) == 4
                and all(dec(r[0]) == PRICE and dec(r[1]) == STOCK for r in price_rows),
                {"rows": price_rows})

        # sku_code round trip in the DB: 4 distinct codes, exactly the ones sent.
        code_rows = db.rows("SELECT sku_code FROM t_product_sku WHERE prod_id = %s"
                            % q(prod[0]))
        db_codes = sorted(r[0] for r in code_rows if r[0] not in (None, "NULL"))
        p.check("3: the 4 sku_code values round-trip into the DB "
                "(4 distinct, exactly the codes sent)",
                len(db_codes) == 4 and db_codes == sorted(MAIN_SKU_CODES)
                and len(set(db_codes)) == 4,
                {"db_sku_codes": db_codes, "sent": sorted(MAIN_SKU_CODES),
                 "rows": len(code_rows)})

        # ---------------- 4) reload ----------------
        st, bd = merchant.call(SKU_BASE % prod[0])
        data = (bd or {}).get("data") or {}
        specs = data.get("specs") or []
        skus = data.get("skus") or []
        name_to_values = {}
        for s in specs:
            name_to_values[s.get("name")] = {v.get("value")
                                             for v in (s.get("values") or [])}
        texts = [k.get("specText") or "" for k in skus]
        text_ok = True
        text_problems = []
        for text in texts:
            tokens = [t for t in text.split(";") if t]
            if len(tokens) != len(specs):
                text_ok = False
                text_problems.append({"specText": text, "reason": "token count"})
                continue
            for token in tokens:
                if ":" not in token:
                    text_ok = False
                    text_problems.append({"specText": text, "reason": "no colon"})
                    break
                name, value = token.split(":", 1)
                if name not in name_to_values or value not in name_to_values[name]:
                    text_ok = False
                    text_problems.append({"specText": text,
                                          "reason": "name:value not in tree",
                                          "name": name, "value": value})
                    break
        p.note("4: reloaded tree", {"specs": specs, "skus": skus})
        p.check("4: GET /product/my/sku/{prodId} -> 2 specs and 4 skus",
                st == 200 and (bd or {}).get("code") == 200
                and len(specs) == 2 and len(skus) == 4,
                {"http": st, "code": (bd or {}).get("code"),
                 "specs": len(specs), "skus": len(skus)})
        p.check("4: every specText is dimensionName:value pairs that resolve inside "
                "the returned tree (load -> buildView -> specText carry-forward)",
                text_ok and len(texts) == 4, {"specTexts": texts,
                                              "problems": text_problems})
        p.check("4: reloaded SKUs keep price 9.90 and stock 10",
                len(skus) == 4
                and all(dec(k.get("price")) == PRICE and dec(k.get("stock")) == STOCK
                        for k in skus),
                {"skus": [{"price": k.get("price"), "stock": k.get("stock")}
                          for k in skus]})

        # sku_code round trip through GET: the 4 saved codes come back, one per SKU.
        reload_codes = sorted(str(k.get("skuCode")) for k in skus
                              if k.get("skuCode") not in (None, "", "None"))
        p.check("4: reloaded SKUs return the 4 distinct sku_code values "
                "(save -> DB -> buildView -> GET)",
                len(reload_codes) == 4
                and reload_codes == sorted(MAIN_SKU_CODES)
                and len(set(reload_codes)) == 4,
                {"reload_sku_codes": reload_codes, "sent": sorted(MAIN_SKU_CODES),
                 "skus": [{"skuCode": k.get("skuCode"),
                           "specText": k.get("specText")} for k in skus]})

        # Task 1 carry-forward: the three SKU mappers really executed. The save
        # wrote 2+4+4 rows into the DB and the reload read the same tree back
        # through ProductSpecMapper / ProductSpecValueMapper / ProductSkuMapper;
        # that round trip IS the proof that @MapperScan
        # ("com.lanlink.shopping.module.*.mapper") covers module.sku.mapper.
        roundtrip_ok = (ok_save and after_main == expected
                        and len(specs) == 2 and len(skus) == 4 and text_ok)
        p.check("CF (Task 1): @MapperScan covers module.sku.mapper -- the save + "
                "reload round trip really executed the three SKU mappers",
                roundtrip_ok,
                {"rows_written": {"t_product_spec": 2,
                                  "t_product_spec_value": 4,
                                  "t_product_sku": 4},
                 "rows_reloaded": {"specs": len(specs), "skus": len(skus)},
                 "proof": "mappers insert/select against the live DB"})

        # ---------------- 5) guard rails ----------------
        def guard_case(label, payload, why):
            st2, bd2, hits2 = post_retry(p, merchant, SKU_SAVE % prod[0], payload,
                                         "POST save[%s]" % label)
            after = counts(db, prod[0])
            ok = ((bd2 or {}).get("code") == 400 and after == after_main)
            p.check("5: %s -> body code 400 and the three tables unchanged" % why,
                    ok, {"http": st2, "code": (bd2 or {}).get("code"),
                         "message": (bd2 or {}).get("message"),
                         "counts_after": after, "counts_before": after_main,
                         "rate_limit_hits": hits2})
            return bd2

        guard_case("dims>3",
                   tree([("A", ["a"]), ("B", ["b"]), ("C", ["c"]), ("D", ["d"])]),
                   "4 dimensions (dims > 3)")

        dup = {"specs": [{"name": DIM_A, "values": list(VAL_A)}],
               "skus": [{"values": [ref(0, 0)], "price": "9.90", "stock": 10},
                        {"values": [ref(0, 0)], "price": "9.90", "stock": 10}]}
        guard_case("dup-combo", dup, "duplicate SKU combination")

        zero_price = tree([(DIM_A, VAL_A), (DIM_B, VAL_B)], price=Decimal("0"))
        guard_case("price=0", zero_price, "price = 0")

        neg_stock = tree([(DIM_A, VAL_A), (DIM_B, VAL_B)], stock=-1)
        guard_case("stock=-1", neg_stock, "stock = -1")

        # skuCode uniqueness: two SKUs of one product must not share a code.
        # SkuService.validate() rejects it with 400 before any delete/insert, so the
        # three tables must stay exactly as after the main save.
        dup_code_tree = tree([(DIM_A, VAL_A), (DIM_B, VAL_B)],
                             sku_codes=["SKU-DUP", "SKU-DUP", "SKU-OK-1", "SKU-OK-2"])
        bd2 = guard_case("dup-sku-code", dup_code_tree,
                         "two SKUs sharing the same skuCode")
        msg = str((bd2 or {}).get("message") or "")
        p.check("5: the duplicate-skuCode rejection names the code rule",
                "编码" in msg, {"message": msg})

        trigger = forbidden_claim()
        if trigger:
            bad_value = (trigger + "认证钢材")[:40]
            payload = tree([(DIM_A, [bad_value, VAL_A[1]]), (DIM_B, VAL_B)])
            bd2 = guard_case("compliance",
                             payload,
                             "a compliance-forbidden word (%s) in a spec value"
                             % trigger)
            msg = str((bd2 or {}).get("message") or "")
            p.check("5: the compliance rejection names the offending wording",
                    trigger in msg, {"trigger": trigger, "message": msg})
        else:
            p.note("5: compliance leg SKIPPED", {
                "reason": "could not read a FORBIDDEN_CLAIMS token out of "
                          "TechComplianceGuard.java; no safe trigger word is guessed",
                "source": COMPLIANCE_SRC})

        # ---------------- 6) ownership ----------------
        if foreign:
            st2, bd2, hits2 = post_retry(p, merchant, SKU_SAVE % foreign[0],
                                         MAIN_TREE, "POST save[foreign]")
            p.check("6: save to a product owned by another merchant -> body 403",
                    (bd2 or {}).get("code") == 403,
                    {"http": st2, "code": (bd2 or {}).get("code"),
                     "message": (bd2 or {}).get("message"),
                     "foreign_prod_id": foreign[0], "rate_limit_hits": hits2})
            p.check("6: the foreign product's SKU tables stayed untouched",
                    counts(db, foreign[0]) == foreign_base,
                    {"counts": counts(db, foreign[0]),
                     "baseline": foreign_base})

        st2, bd2, hits2 = post_retry(p, merchant, SKU_SAVE % NONEXISTENT_PROD,
                                     MAIN_TREE, "POST save[missing]")
        p.check("6: save to a non-existent prodId -> body 404",
                (bd2 or {}).get("code") == 404,
                {"http": st2, "code": (bd2 or {}).get("code"),
                 "message": (bd2 or {}).get("message"),
                 "prod_id": NONEXISTENT_PROD, "rate_limit_hits": hits2})

        # ---------------- 7) permission gate ----------------
        anon = Client(args.backend)
        st2, bd2 = anon.call(SKU_BASE % prod[0])
        p.check("7: anonymous GET -> body 401", (bd2 or {}).get("code") == 401,
                {"http": st2, "code": (bd2 or {}).get("code"),
                 "message": (bd2 or {}).get("message")})

        anon_save = Client(args.backend)
        st2, bd2 = anon_save.call(SKU_SAVE % prod[0], "POST", MAIN_TREE)
        p.check("7: anonymous save -> rejected with body code 401",
                (bd2 or {}).get("code") == 401,
                {"http": st2, "code": (bd2 or {}).get("code"),
                 "message": (bd2 or {}).get("message")})

        if args.buyer_phone and args.buyer_password:
            try:
                buyer = Client(args.backend)
                buyer.login(args.buyer_phone, args.buyer_password)
                st2, bd2, hits2 = post_retry(p, buyer, SKU_SAVE % prod[0],
                                             MAIN_TREE, "POST save[buyer]")
                p.check("7: buyer session save -> body 403 (no product:publish)",
                        (bd2 or {}).get("code") == 403,
                        {"http": st2, "code": (bd2 or {}).get("code"),
                         "message": (bd2 or {}).get("message"),
                         "rate_limit_hits": hits2})
                p.check("7: the buyer attempt wrote nothing",
                        counts(db, prod[0]) == after_main,
                        {"counts": counts(db, prod[0]),
                         "counts_before": after_main})
            except SystemExit as e:
                buyer = None
                p.note("7: buyer leg SKIPPED", {
                    "reason": "buyer login failed (%s); the product:publish "
                              "denial is already proven by the anonymous legs "
                              "and by PermInterceptor's 403 path" % str(e)[:120]})
        else:
            p.note("7: buyer leg SKIPPED", {
                "reason": "no --buyer-phone/--buyer-password supplied; a demo buyer "
                          "without product:publish was not available to the probe. "
                          "The anonymous 401 legs still prove the gate is wired."})

        # ---------------- 8) empty tree clears ----------------
        st2, bd2, hits2 = post_retry(p, merchant, SKU_SAVE % prod[0],
                                     {"specs": [], "skus": []}, "POST save[empty]")
        data = (bd2 or {}).get("data") or {}
        after_empty = counts(db, prod[0])
        p.check("8: save {specs:[], skus:[]} -> body code 200 with an empty tree",
                st2 == 200 and (bd2 or {}).get("code") == 200
                and not (data.get("specs") or []) and not (data.get("skus") or []),
                {"http": st2, "code": (bd2 or {}).get("code"),
                 "specs": len(data.get("specs") or []),
                 "skus": len(data.get("skus") or []), "rate_limit_hits": hits2})
        p.check("8: the empty tree cleared all three tables back to baseline",
                after_empty == base,
                {"baseline": base, "after_empty": after_empty})

    except StopProbe as e:
        stop_code = e.code
        p.note("probe stopped early", {"exit_code": e.code})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        p.check("probe ran to completion without unexpected error", False,
                {"error": repr(e)[:400]})
    finally:
        cleanup = p.evidence["cleanup"]
        cleanup["target_prod_id"] = prod[0] if prod else None
        cleanup["baselines"] = base
        if args.keep:
            cleanup["mode"] = "kept"
            cleanup["reason"] = "--keep"
        else:
            cleanup["mode"] = "cleanup"
            try:
                if prod is not None and base:
                    pid = q(prod[0])
                    safe_run(p, db, "DELETE FROM t_product_sku WHERE prod_id = %s"
                             % pid, "t_product_sku(target prodId)")
                    safe_run(p, db, "DELETE FROM t_product_spec_value WHERE prod_id = %s"
                             % pid, "t_product_spec_value(target prodId)")
                    safe_run(p, db, "DELETE FROM t_product_spec WHERE prod_id = %s"
                             % pid, "t_product_spec(target prodId)")
                    cleanup["deleted_scope"] = (
                        "t_product_sku / t_product_spec_value / t_product_spec "
                        "WHERE prod_id = this run's target product only")
                    cleanup["other_products_untouched"] = True

                    final = counts(db, prod[0])
                    cleanup["final"] = final
                    cleanup["baseline_restored"] = final == base
                    p.check("9: cleanup restored the three tables to baseline "
                            "(baseline == after)", final == base,
                            {"baseline": base, "final": final})
                    p.check("9: no residue left for the target prodId",
                            all(v == 0 for v in final.values()), {"final": final})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("9: cleanup verification failed", False,
                        {"error": str(e)[:300]})

        # ---------------- carry-forward evidence ----------------
        try:
            boot = subprocess.run(
                ["docker", "logs", args.backend_container],
                capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=60)
            log_text = (boot.stdout or "") + (boot.stderr or "")
            started = "Started LanlinkShoppingApplication" in log_text
            sku_bean_error = bool(re.search(
                r"(Error creating bean|UnsatisfiedDependency).*[Ss]ku", log_text))
            table_rows = db.rows(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (%s) "
                "ORDER BY TABLE_NAME" % sql_in(list(TABLES)))
            present = sorted(row[0] for row in table_rows)
            p.note("carry-forward (Task 1): SkuSchemaInitializer boot evidence", {
                "backend_container": args.backend_container,
                "started_log_present": started,
                "sku_bean_creation_error": sku_bean_error,
                "sku_tables_present": present,
                "note": "backend was already running and is NOT restarted; a clean "
                        "Started line plus the three existing tables is the proof "
                        "that the @Order(21) ApplicationRunner completed",
            })
            p.check("CF (Task 1): SkuSchemaInitializer ran at boot (clean Started line, "
                    "no SKU bean-creation error, all three tables present)",
                    started and not sku_bean_error and present == sorted(TABLES),
                    {"started": started, "bean_error": sku_bean_error,
                     "tables": present})
        except BaseException as e:  # noqa: BLE001
            p.note("carry-forward boot check unavailable", {"error": str(e)[:300]})

        try:
            with open(SCHEMA_SQL, "r", encoding="utf-8") as fh:
                schema_text = fh.read()
            hits = schema_text.count("t_product_sku")
            p.check("CF (Task 1): sql/schema.sql still carries the SKU region "
                    "(t_product_sku occurrences > 0)", hits > 0,
                    {"occurrences": hits, "file": SCHEMA_SQL})
        except BaseException as e:  # noqa: BLE001
            p.check("CF: sql/schema.sql SKU region readable", False,
                    {"error": str(e)[:300]})

        p.note("scope", {
            "containers_restarted": False,
            "backend_untouched": True,
            "secrets_printed": False,
            "java_modified": False,
            "touched_rows": "only the target product's spec / spec_value / sku rows, "
                            "all deleted in cleanup",
            "other_products_untouched": True,
        })

    code = 1 if p.failed else stop_code
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
