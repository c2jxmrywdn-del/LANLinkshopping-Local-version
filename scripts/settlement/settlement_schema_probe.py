import subprocess, shlex, sys

# 只读核验：三表 + 唯一键列序 + 关键列类型 + 排序规则/引擎 + commission_rate 列。
CHECKS = [
  ("t_merchant_earning exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_earning'", "1"),
  ("t_merchant_settlement exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_settlement'", "1"),
  ("t_merchant_settlement_item exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_settlement_item'", "1"),
  ("uk_earning_line cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_earning' AND INDEX_NAME='uk_earning_line'", "order_no,item_id,type,refund_seq"),
  ("uk_settle_no cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_settlement' AND INDEX_NAME='uk_settle_no'", "settle_no"),
  ("uk_settle_item cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_settlement_item' AND INDEX_NAME='uk_settle_item'", "settle_id,earning_id"),
  ("earning net_amount type", "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_earning' AND COLUMN_NAME='net_amount'", "decimal"),
  ("earning gross/commission precision", "SELECT CONCAT(COLUMN_NAME,':',NUMERIC_PRECISION,',',NUMERIC_SCALE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_earning' AND COLUMN_NAME IN ('gross_amount','commission_amount','net_amount') ORDER BY COLUMN_NAME", "commission_amount:14,2\ngross_amount:14,2\nnet_amount:14,2"),
  ("earning commission_rate precision", "SELECT CONCAT(NUMERIC_PRECISION,',',NUMERIC_SCALE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_earning' AND COLUMN_NAME='commission_rate'", "5,2"),
  ("collation+engine (all 3)", "SELECT CONCAT(TABLE_COLLATION,'/',ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('t_merchant_earning','t_merchant_settlement','t_merchant_settlement_item')", "utf8mb4_unicode_ci/InnoDB\nutf8mb4_unicode_ci/InnoDB\nutf8mb4_unicode_ci/InnoDB"),
  ("merchant commission_rate col", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='commission_rate'", "1"),
]

def run(stmt, container="shopping-mysql-1"):
    inner = ('mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" '
             '"$MYSQL_DATABASE" -N -B -e ' + shlex.quote(stmt))
    p = subprocess.run(["docker", "exec", container, "sh", "-c", inner],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        # 基础设施失败（容器不可达/鉴权失败）≠ schema 缺失，单独退出码 3
        print("INFRA-FAIL | docker/mysql rc=%d | %s" % (p.returncode, (p.stderr or "")[:300]))
        sys.exit(3)
    return (p.stdout or "").strip()

ok_all = True
for name, sql, exp in CHECKS:
    got = run(sql); ok = got == exp
    print(("PASS" if ok else "FAIL") + " | " + name + ("" if ok else " | exp=%r got=%r" % (exp, got)))
    ok_all = ok_all and ok
print("TOTAL %d checks" % len(CHECKS))
sys.exit(0 if ok_all else 1)
