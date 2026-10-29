package com.lanlink.shopping.module.crm.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/**
 * RFM 用户分层（固定阈值版：当前 6 用户 9 单，五分位无统计意义；数据积累后可切 NTILE）。
 * R=最近支付距今天数，F=支付订单数，M=支付总金额；八层经典矩阵（重要价值/重要保持/重要发展/重要挽留/
 * 一般价值/一般保持/一般发展/一般挽留）。
 */
@Mapper
public interface UserRfmMapper {

    @Delete("DELETE FROM t_user_rfm")
    int clear();

    /** 全量重算：仅已支付订单（pay_status=1），评分 1-5 固定阈值 */
    @Insert("INSERT INTO t_user_rfm(user_id, r_score, f_score, m_score, r_days, f_cnt, m_amount, layer, updated_at) " +
            "SELECT u.user_id, " +
            "  CASE WHEN DATEDIFF(NOW(), MAX(o.pay_time))<=7 THEN 5 " +
            "       WHEN DATEDIFF(NOW(), MAX(o.pay_time))<=30 THEN 4 " +
            "       WHEN DATEDIFF(NOW(), MAX(o.pay_time))<=60 THEN 3 " +
            "       WHEN DATEDIFF(NOW(), MAX(o.pay_time))<=90 THEN 2 ELSE 1 END, " +
            "  0, 0, " +
            "  COALESCE(DATEDIFF(NOW(), MAX(o.pay_time)),999), COUNT(*), COALESCE(SUM(o.total_amount),0), '', NOW() " +
            "FROM t_user u JOIN t_order o ON o.user_id=u.user_id AND o.pay_status=1 " +
            "GROUP BY u.user_id")
    int loadBase();

    @Update("UPDATE t_user_rfm SET f_score = CASE WHEN f_cnt>=10 THEN 5 WHEN f_cnt>=5 THEN 4 WHEN f_cnt>=3 THEN 3 WHEN f_cnt>=2 THEN 2 ELSE 1 END")
    int scoreF();

    @Update("UPDATE t_user_rfm SET m_score = CASE WHEN m_amount>=5000 THEN 5 WHEN m_amount>=2000 THEN 4 WHEN m_amount>=800 THEN 3 WHEN m_amount>=200 THEN 2 ELSE 1 END")
    int scoreM();

    /** 八层矩阵：R/F/M 高低（>=4 为高）组合命名 */
    @Update("UPDATE t_user_rfm SET layer = CASE " +
            " WHEN r_score>=4 AND f_score>=4 AND m_score>=4 THEN '重要价值' " +
            " WHEN r_score<4  AND f_score>=4 AND m_score>=4 THEN '重要保持' " +
            " WHEN r_score>=4 AND f_score<4  AND m_score>=4 THEN '重要发展' " +
            " WHEN r_score<4  AND f_score<4  AND m_score>=4 THEN '重要挽留' " +
            " WHEN r_score>=4 AND f_score>=4 AND m_score<4  THEN '一般价值' " +
            " WHEN r_score<4  AND f_score>=4 AND m_score<4  THEN '一般保持' " +
            " WHEN r_score>=4 AND f_score<4  AND m_score<4  THEN '一般发展' " +
            " ELSE '一般挽留' END")
    int labelLayers();

    @Select("SELECT layer layer, COUNT(*) users, COALESCE(SUM(m_amount),0) amount FROM t_user_rfm GROUP BY layer ORDER BY amount DESC")
    List<Map<String, Object>> layerSummary();
}
