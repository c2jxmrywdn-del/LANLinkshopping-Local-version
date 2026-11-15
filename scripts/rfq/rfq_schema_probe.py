#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RFQ S1 schema acceptance probe (read-only, repeatable).

Asserts the idempotent DDL landed correctly in the container DB:
  1. t_rfq / t_rfq_quote / t_rfq_event exist;
  2. t_order.rfq_no and t_order.rfq_quote_id each exist EXACTLY once
     (the "restart twice -> column count = 1" acceptance criterion);
  3. t_rfq_quote has UNIQUE(rfq_id, mer_id) (one quote per merchant) and
     prod_id is NOT NULL (catalog-anchoring hard constraint);
  4. t_rfq.rfq_no is UNIQUE; t_rfq has the status/deadline scan index.

Run BEFORE implementing to watch it fail (RED), then after deploying the
RfqSchemaInitializer + schema.sql and restarting the backend twice (GREEN).
DB access goes through `docker exec` with the container's own env vars, so no
password appears in this script, the command line, or the output.

Usage:
  python scripts/rfq/rfq_schema_probe.py [--mysql-container shopping-mysql-1] \
      [--out reports/rfq-schema-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure, 3 docker/DB unreachable.
Console output is ASCII on purpose (Windows GBK consoles mangle CJK).
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys


class Db:
    def __init__(self, container: str):
        self.container = container

    def run(self, sql: str) -> str:
        inner = ('mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" '
                 '"$MYSQL_DATABASE" -N -B -e ' + shlex.quote(sql))
        cmd = ["docker", "exec", self.container, "sh", "-c", inner]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise SystemExit("docker/db unreachable: " + proc.stderr.strip()[:200])
        return proc.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--out", default="reports/rfq-schema-evidence.json")
    args = ap.parse_args()
    db = Db(args.mysql_container)

    results = []

    def check(name, ok, detail):
        results.append({"name": name, "ok": bool(ok), "detail": detail})
        print(("PASS" if ok else "FAIL") + " | " + name)

    def table_exists(name):
        return db.run("SELECT COUNT(*) FROM information_schema.TABLES "
                      "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='%s'" % name) == "1"

    def column_count(table, column):
        return int(db.run("SELECT COUNT(*) FROM information_schema.COLUMNS "
                          "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='%s' "
                          "AND COLUMN_NAME='%s'" % (table, column)) or "0")

    # 1. three tables exist
    for t in ("t_rfq", "t_rfq_quote", "t_rfq_event"):
        check("table exists: " + t, table_exists(t), {"table": t})

    # 2. t_order guarded columns exist exactly once (idempotency)
    for col in ("rfq_no", "rfq_quote_id"):
        n = column_count("t_order", col)
        check("t_order.%s count == 1" % col, n == 1, {"count": n})

    # 3. quote unique (rfq_id, mer_id) and prod_id NOT NULL
    if table_exists("t_rfq_quote"):
        uk = db.run("SELECT INDEX_NAME FROM information_schema.STATISTICS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq_quote' "
                    "AND NON_UNIQUE=0 AND COLUMN_NAME='mer_id' "
                    "AND INDEX_NAME<>'PRIMARY'")
        check("t_rfq_quote UNIQUE covers mer_id (one quote per merchant)",
              uk != "", {"index": uk})
        # 必须恰好是两列 (rfq_id, mer_id)：三列 UNIQUE 会放过同商同询价多商品
        if uk:
            cols = db.run("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                          "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq_quote' "
                          "AND INDEX_NAME='%s' ORDER BY SEQ_IN_INDEX" % uk.splitlines()[0])
            check("quote unique index is exactly (rfq_id, mer_id)",
                  cols.splitlines() == ["rfq_id", "mer_id"], {"columns": cols.splitlines()})
        nullable = db.run("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                          "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq_quote' "
                          "AND COLUMN_NAME='prod_id'")
        check("t_rfq_quote.prod_id NOT NULL (catalog anchor)", nullable == "NO",
              {"is_nullable": nullable})
    else:
        check("t_rfq_quote UNIQUE covers mer_id (one quote per merchant)", False, {"skip": "table missing"})
        check("quote unique index is exactly (rfq_id, mer_id)", False, {"skip": "table missing"})
        check("t_rfq_quote.prod_id NOT NULL (catalog anchor)", False, {"skip": "table missing"})

    # 4. rfq_no single-column unique + (status, quote_deadline) scan index
    if table_exists("t_rfq"):
        uk = db.run("SELECT INDEX_NAME FROM information_schema.STATISTICS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq' "
                    "AND NON_UNIQUE=0 AND COLUMN_NAME='rfq_no' AND INDEX_NAME<>'PRIMARY'")
        check("t_rfq.rfq_no UNIQUE", uk != "", {"index": uk})
        if uk:
            cols = db.run("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                          "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq' "
                          "AND INDEX_NAME='%s' ORDER BY SEQ_IN_INDEX" % uk.splitlines()[0])
            check("t_rfq.rfq_no unique index is single-column",
                  cols.splitlines() == ["rfq_no"], {"columns": cols.splitlines()})
        else:
            check("t_rfq.rfq_no unique index is single-column", False, {"skip": "no unique index"})
        # 过期扫表按 WHERE status=... AND quote_deadline<?，索引须 status 前导、deadline 次列
        scan = db.run("SELECT INDEX_NAME FROM information_schema.STATISTICS "
                      "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq' "
                      "AND COLUMN_NAME='status' AND SEQ_IN_INDEX=1 AND INDEX_NAME<>'PRIMARY' "
                      "AND INDEX_NAME IN (SELECT INDEX_NAME FROM information_schema.STATISTICS "
                      "  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_rfq' "
                      "  AND COLUMN_NAME='quote_deadline' AND SEQ_IN_INDEX=2)")
        check("t_rfq has (status, quote_deadline) expiry-sweep index", scan != "",
              {"index": scan})
    else:
        check("t_rfq.rfq_no UNIQUE", False, {"skip": "table missing"})
        check("t_rfq.rfq_no unique index is single-column", False, {"skip": "table missing"})
        check("t_rfq has (status, quote_deadline) expiry-sweep index", False, {"skip": "table missing"})

    passed = sum(1 for r in results if r["ok"])
    total = len(results)
    print("TOTAL: %d / %d PASS" % (passed, total))
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"passed": passed, "total": total, "results": results}, f,
                  ensure_ascii=False, indent=2)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())