#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SKU multi-spec schema acceptance probe (read-only, repeatable).

Asserts the idempotent SKU DDL landed correctly in the container DB:
  1. t_product_spec / t_product_spec_value / t_product_sku exist;
  2. t_product_sku carries UNIQUE uk_sku_combo(prod_id, spec_value_ids) and
     UNIQUE uk_sku_code(prod_id, sku_code) -- one SKU per spec combination and
     one merchant code per product;
  3. spec_value_ids / price / stock exist with the right types (varchar(255),
     decimal(14,2), int) and t_product_spec_value.prod_id exists (the redundant
     product anchor used by the merchant-ownership guard);
  4. VARCHAR lengths, NOT NULL / DEFAULT guards, InnoDB + utf8mb4 + table COMMENT
     match support/SkuSchemaInitializer.java and sql/schema.sql (dual write).

Run BEFORE creating the tables to watch it fail (RED), then after applying the
initializer DDL to the running DB (GREEN). Re-running is safe: every statement
is a read-only information_schema SELECT.

DB access goes through `docker exec` with the container's own env vars
(MYSQL_ROOT_PASSWORD / MYSQL_DATABASE), so no credential appears in this script,
the command line, or the output. Same Db pattern as scripts/rfq/rfq_schema_probe.py.

Usage:
  python scripts/sku/sku_schema_probe.py [--mysql-container shopping-mysql-1] \
      [--out reports/sku-schema-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure or docker/DB unreachable.
Console output is ASCII on purpose (Windows GBK consoles mangle CJK); table
comments are asserted by CHAR_LENGTH(...) > 0 rather than echoed.
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


# (assertion name, read-only SQL, expected single-value output)
# The first five entries are the plan's verbatim key assertions.
CHECKS = [
    ("t_product_spec exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec'", "1"),
    ("t_product_spec_value exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value'", "1"),
    ("t_product_sku exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku'", "1"),
    ("uk_sku_combo cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND INDEX_NAME='uk_sku_combo'", "prod_id,spec_value_ids"),
    ("price type", "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='price'", "decimal"),

    # --- unique keys: column order AND uniqueness flag ---
    ("uk_sku_code cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND INDEX_NAME='uk_sku_code'", "prod_id,sku_code"),
    ("uk_sku_combo is UNIQUE", "SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND INDEX_NAME='uk_sku_combo' AND SEQ_IN_INDEX=1", "0"),
    ("uk_sku_code is UNIQUE", "SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND INDEX_NAME='uk_sku_code' AND SEQ_IN_INDEX=1", "0"),
    ("idx_sku_prod exists", "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND INDEX_NAME='idx_sku_prod'", "1"),

    # --- t_product_sku column types / lengths / guards ---
    ("spec_value_ids type", "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='spec_value_ids'", "varchar"),
    ("spec_value_ids length 255", "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='spec_value_ids'", "255"),
    ("spec_value_ids NOT NULL", "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='spec_value_ids'", "NO"),
    ("stock type", "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='stock'", "int"),
    ("stock NOT NULL default 0", "SELECT CONCAT(IS_NULLABLE, '|', COLUMN_DEFAULT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='stock'", "NO|0"),
    ("price precision/scale 14,2", "SELECT CONCAT(NUMERIC_PRECISION, ',', NUMERIC_SCALE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='price'", "14,2"),
    ("price NOT NULL", "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='price'", "NO"),
    ("sku_code length 64 nullable", "SELECT CONCAT(CHARACTER_MAXIMUM_LENGTH, '|', IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='sku_code'", "64|YES"),
    ("status tinyint default 1", "SELECT CONCAT(DATA_TYPE, '|', IS_NULLABLE, '|', COLUMN_DEFAULT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='status'", "tinyint|NO|1"),
    # MySQL 8 renders EXTRA as "DEFAULT_GENERATED on update CURRENT_TIMESTAMP",
    # so match the ON UPDATE clause instead of the whole EXTRA string.
    ("sku update_time auto refresh", "SELECT EXTRA LIKE '%on update CURRENT_TIMESTAMP%' FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_sku' AND COLUMN_NAME='update_time'", "1"),

    # --- spec dimension / spec value tables ---
    ("t_product_spec_value.prod_id exists", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value' AND COLUMN_NAME='prod_id'", "1"),
    ("t_product_spec_value.prod_id NOT NULL", "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value' AND COLUMN_NAME='prod_id'", "NO"),
    ("spec_name length 20", "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec' AND COLUMN_NAME='spec_name'", "20"),
    ("spec_value length 40", "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value' AND COLUMN_NAME='spec_value'", "40"),
    ("idx_value_spec exists", "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value' AND INDEX_NAME='idx_value_spec'", "1"),
    ("idx_value_prod exists", "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec_value' AND INDEX_NAME='idx_value_prod'", "1"),
    ("idx_spec_prod exists", "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product_spec' AND INDEX_NAME='idx_spec_prod'", "1"),

    # --- engine / charset / comment (dual-write parity with schema.sql) ---
    ("all three tables InnoDB", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('t_product_spec','t_product_spec_value','t_product_sku') AND ENGINE='InnoDB'", "3"),
    ("all three tables utf8mb4", "SELECT COUNT(*) FROM information_schema.TABLES T JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY C ON T.TABLE_COLLATION=C.COLLATION_NAME WHERE T.TABLE_SCHEMA=DATABASE() AND T.TABLE_NAME IN ('t_product_spec','t_product_spec_value','t_product_sku') AND C.CHARACTER_SET_NAME='utf8mb4'", "3"),
    ("all three tables have COMMENT", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('t_product_spec','t_product_spec_value','t_product_sku') AND CHAR_LENGTH(TABLE_COMMENT)>0", "3"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--out", default="reports/sku-schema-probe.json")
    args = ap.parse_args()
    db = Db(args.mysql_container)

    results = []
    for name, sql, expected in CHECKS:
        got = db.run(sql)
        ok = got == expected
        results.append({"name": name, "ok": ok, "expected": expected, "got": got, "sql": sql})
        if ok:
            print("PASS | " + name)
        else:
            print("FAIL | %s | expected=%r got=%r" % (name, expected, got))

    passed = sum(1 for r in results if r["ok"])
    total = len(results)
    print("TOTAL: %d / %d PASS" % (passed, total))
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"passed": passed, "total": total, "results": results}, f,
                  ensure_ascii=False, indent=2)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())