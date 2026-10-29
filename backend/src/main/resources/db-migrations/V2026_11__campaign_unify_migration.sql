-- ============================================================================
-- 营销中台统一 · 存量数据迁移脚本（B3 高风险变更）
-- ----------------------------------------------------------------------------
-- 背景：原 t_activity（注册/消费有礼）与 t_promotion（满减/折扣）已统一并入
--       t_campaign；原 t_activity_participant 改名为 t_campaign_participant。
--       全新库由 schema.sql 直接建新表，本脚本只用于「旧库升级」。
--
-- 映射口径：
--   t_activity.type register/purchase → t_campaign.type 同名
--   t_activity.status：2(已结束) → 0(下架)，0/1 → 1(上架，是否生效仍由时间窗判定)
--   旧活动参与奖励固定 100 积分 → t_campaign.reward_points = 100
--   t_promotion.type full_reduce/discount → t_campaign.type 同名；status 原样
--   t_activity_participant.activity_id → t_campaign_participant.campaign_id
--   旧表 t_activity / t_promotion 保留为归档（不再有业务读写），不做删除
--
-- 执行方式（容器）：
--   docker exec -i shopping-mysql-1 mysql --default-character-set=utf8mb4 \
--     -uroot -p"$MYSQL_ROOT_PASSWORD" lanlink_shopping < 本文件
-- 执行时机：务必在 schema.sql 建好 t_campaign / t_campaign_participant 之后。
-- 幂等性：通过 t_data_migration 标记 + NOT EXISTS 去重，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS t_data_migration (
  name       VARCHAR(64) PRIMARY KEY COMMENT '迁移标识',
  applied_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间'
) ENGINE=InnoDB COMMENT='一次性数据迁移记录';

DROP PROCEDURE IF EXISTS sp_migrate_campaign_unify;

DELIMITER $$

CREATE PROCEDURE sp_migrate_campaign_unify()
BEGIN
  DECLARE v_applied INT DEFAULT 0;
  DECLARE v_has_activity INT DEFAULT 0;
  DECLARE v_has_promotion INT DEFAULT 0;
  DECLARE v_has_old_participant INT DEFAULT 0;
  DECLARE v_has_new_participant INT DEFAULT 0;

  SELECT COUNT(*) INTO v_applied FROM t_data_migration WHERE name = 'campaign_unify_v1';
  IF v_applied > 0 THEN
    SELECT 'campaign_unify_v1 已执行过，跳过' AS result;
  ELSE
    SELECT COUNT(*) INTO v_has_activity FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_activity';
    SELECT COUNT(*) INTO v_has_promotion FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_promotion';
    SELECT COUNT(*) INTO v_has_old_participant FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_activity_participant';
    SELECT COUNT(*) INTO v_has_new_participant FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_campaign_participant';

    -- 1) 活动 → 统一活动表（按 标题+类型+开始时间 去重，避免重复执行产生脏数据）
    IF v_has_activity > 0 THEN
      INSERT INTO t_campaign
        (title, type, description, accent, start_time, end_time, status,
         reward_points, create_time, update_time)
      SELECT a.title,
             CASE WHEN a.type IN ('register', 'purchase') THEN a.type ELSE 'register' END,
             a.description,
             'blue',
             a.start_time,
             a.end_time,
             CASE WHEN a.status = 2 THEN 0 ELSE 1 END,
             100,
             a.create_time,
             a.update_time
      FROM t_activity a
      WHERE NOT EXISTS (
        SELECT 1 FROM t_campaign c
        WHERE c.title = a.title
          AND c.type = CASE WHEN a.type IN ('register', 'purchase') THEN a.type ELSE 'register' END
          AND c.start_time <=> a.start_time
      );
    END IF;

    -- 2) 促销 → 统一活动表
    IF v_has_promotion > 0 THEN
      INSERT INTO t_campaign
        (title, type, threshold, benefit_amount, discount_rate, scope, ind_id,
         start_time, end_time, status, accent, create_time, update_time)
      SELECT p.title,
             p.type,
             p.threshold,
             p.benefit_amount,
             p.discount_rate,
             COALESCE(p.scope, 'all'),
             p.ind_id,
             p.start_time,
             p.end_time,
             CASE WHEN p.status = 0 THEN 0 ELSE 1 END,
             'blue',
             p.create_time,
             p.update_time
      FROM t_promotion p
      WHERE NOT EXISTS (
        SELECT 1 FROM t_campaign c
        WHERE c.title = p.title AND c.type = p.type AND c.start_time <=> p.start_time
      );
    END IF;

    -- 3) 参与记录：旧表直接改名（列 activity_id → campaign_id），保留原唯一键；
    --    若新表已存在（例如 schema.sql 已建），则补录缺失行后归档旧表。
    IF v_has_old_participant > 0 THEN
      IF v_has_new_participant = 0 THEN
        RENAME TABLE t_activity_participant TO t_campaign_participant;
        ALTER TABLE t_campaign_participant
          CHANGE COLUMN activity_id campaign_id BIGINT NOT NULL COMMENT '活动ID';
      ELSE
        INSERT INTO t_campaign_participant (campaign_id, user_id, bonus_points, join_time)
        SELECT op.activity_id, op.user_id, op.bonus_points, op.join_time
        FROM t_activity_participant op
        WHERE NOT EXISTS (
          SELECT 1 FROM t_campaign_participant np
          WHERE np.campaign_id = op.activity_id AND np.user_id = op.user_id
        );
      END IF;
    END IF;

    INSERT INTO t_data_migration (name) VALUES ('campaign_unify_v1');
    SELECT 'campaign_unify_v1 执行完成' AS result;
  END IF;
END$$

DELIMITER ;

CALL sp_migrate_campaign_unify();
DROP PROCEDURE sp_migrate_campaign_unify;

-- 执行后自检：
--   SELECT type, COUNT(*) FROM t_campaign GROUP BY type;
--   SELECT COUNT(*) FROM t_campaign_participant;
