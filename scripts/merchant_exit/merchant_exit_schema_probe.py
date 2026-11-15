import subprocess, shlex, sys

CHECKS = [
  ("t_merchant.exit_status", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='exit_status'", "1"),
  ("t_merchant.exit_request_time", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='exit_request_time'", "1"),
  ("t_merchant.notice_end_time", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='notice_end_time'", "1"),
  ("t_merchant.close_time", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='close_time'", "1"),
  ("t_merchant.exit_reason", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='exit_reason'", "1"),
  ("t_merchant.exit_reject_reason", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant' AND COLUMN_NAME='exit_reject_reason'", "1"),
  ("t_order.finish_time", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_order' AND COLUMN_NAME='finish_time'", "1"),
  ("t_merchant_deposit exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_deposit'", "1"),
  ("t_merchant_exit_log exists", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_exit_log'", "1"),
  ("idx_deposit_mer cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_deposit' AND INDEX_NAME='idx_deposit_mer'", "mer_id,deposit_id"),
  ("idx_exitlog_mer cols", "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_exit_log' AND INDEX_NAME='idx_exitlog_mer'", "mer_id,id"),
  ("deposit collation", "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_merchant_deposit'", "utf8mb4_unicode_ci"),
  ("finish_time backfilled", "SELECT COUNT(*) FROM t_order WHERE order_status=2 AND finish_time IS NULL", "0"),
]

def run(stmt, container="shopping-mysql-1"):
    inner = ('mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" '
             '"$MYSQL_DATABASE" -N -B -e ' + shlex.quote(stmt))
    p = subprocess.run(["docker", "exec", container, "sh", "-c", inner],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (p.stdout or "").strip()

ok_all = True
for name, sql, exp in CHECKS:
    got = run(sql); ok = got == exp
    print(("PASS" if ok else "FAIL") + " | " + name + ("" if ok else " | exp=%r got=%r" % (exp, got)))
    ok_all = ok_all and ok
print("TOTAL %d checks" % len(CHECKS))
sys.exit(0 if ok_all else 1)
