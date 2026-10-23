-- ============================================================================
-- 会员等级政策调整 · 存量数据迁移脚本（B3 高影响力变更）
-- ----------------------------------------------------------------------------
-- 背景：本次把 t_member_level.min_growth 门槛整体上调约 100 倍（10万…2000万），
--       并把升级规则由「成长值单条件」改为「有效订阅 + 成长值」双条件
--       （MembershipService.levelForGrowth 无有效订阅即回落为等级 1 普通会员）。
-- 风险：存量库中此前已达标的会员（尤其无订阅者）会在政策生效后被整体降级。
--
-- 本脚本做两件事（幂等，可重复执行）：
--   1) 备份受影响用户变更前后的等级，便于回滚与审计；
--   2) 给「原本已是高等级、但当前无有效订阅」的存量会员发放 90 天过渡订阅，
--      使其不因新门槛+订阅闸门被瞬间清零，给运营留出通知/转化窗口；
--   3) 依据新规则重算 t_member_card.level_id，使落库等级与实际展示一致。
--
-- 执行时机：务必在 schema.sql 的 t_member_level 新门槛种子【之后】执行。
-- 执行方式（容器）：
--   docker exec -i shopping-mysql-1 mysql --default-character-set=utf8mb4 \
--     -uroot -p"$MYSQL_ROOT_PASSWORD" lanlink_shopping < 本文件
-- 说明：本文件【不】放在 backend/src/main/resources/sql/ 下，避免被 docker
--       initdb.d 在 schema 种子之前自动执行导致顺序错乱。
-- ============================================================================

-- 1) 备份表：仅首次快照变更前的 level_id（NOT EXISTS 保证重复执行不覆盖原始快照）
CREATE TABLE IF NOT EXISTS t_member_card_policy_backup (
  card_id     BIGINT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  old_level_id BIGINT COMMENT '政策变更前等级',
  growth      INT COMMENT '备份时成长值',
  backed_up_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_backup_user (user_id)
) ENGINE=InnoDB COMMENT='会员等级政策变更前备份';

INSERT INTO t_member_card_policy_backup (card_id, user_id, old_level_id, growth)
SELECT c.card_id, c.user_id, c.level_id, c.growth
FROM t_member_card c
WHERE NOT EXISTS (
  SELECT 1 FROM t_member_card_policy_backup b WHERE b.card_id = c.card_id
);

-- 2) 过渡订阅：原等级 >= 2（白银及以上）且当前无有效订阅者，授予 90 天 active 订阅。
--    以 yearly 方案为模板（仅取 plan_id 作为来源标记），auto_renew=0 单次不自动续费。
--    NOT EXISTS(active 且未过期) 保证幂等：再次执行时这些人已有有效订阅，不再重复发放。
INSERT INTO t_user_subscription (user_id, plan_id, start_time, end_time, status, auto_renew)
SELECT c.user_id,
       (SELECT plan_id FROM t_subscription_plan WHERE plan_code = 'yearly' LIMIT 1),
       NOW(),
       DATE_ADD(NOW(), INTERVAL 90 DAY),
       'active',
       0
FROM t_member_card c
WHERE c.level_id >= 2
  AND NOT EXISTS (
    SELECT 1 FROM t_user_subscription s
    WHERE s.user_id = c.user_id AND s.status = 'active' AND s.end_time > NOW()
  );

-- 3) 依据新规则重算落库等级：
--    有有效订阅 → 取 growth 达标的最高等级；无有效订阅 → 回落为 1（普通会员）。
--    与 MembershipService.levelForGrowth(growth, userId) 口径保持一致。
UPDATE t_member_card c
LEFT JOIN (
  SELECT DISTINCT user_id
  FROM t_user_subscription
  WHERE status = 'active' AND end_time > NOW()
) s ON s.user_id = c.user_id
SET c.level_id = COALESCE((
  SELECT ml.level_id
  FROM t_member_level ml
  WHERE s.user_id IS NOT NULL AND c.growth >= ml.min_growth
  ORDER BY ml.min_growth DESC
  LIMIT 1
), 1);

-- 迁移影响预览（可选，取消注释执行）：查看被降级/被过渡豁免的用户
-- SELECT b.user_id, b.old_level_id AS 变更前, c.level_id AS 变更后, c.growth
-- FROM t_member_card_policy_backup b
-- JOIN t_member_card c ON c.card_id = b.card_id
-- WHERE b.old_level_id <> c.level_id
-- ORDER BY b.old_level_id DESC;
