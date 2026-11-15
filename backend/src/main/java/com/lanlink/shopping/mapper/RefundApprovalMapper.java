package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.RefundApproval;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 退款审批留痕 Mapper（P0-C 退款出款收口）。
 *
 * <p>出款侧只依赖两个动作：
 * <ul>
 *   <li>{@link #findLatestPlatform(String)}：取该订单最新一条 PLATFORM 阶段审批行，判断是否
 *       {@code APPROVE} 且未消费（最新为 REJECT 则禁止出款）；</li>
 *   <li>{@link #consume(Long)}：把一次平台复核凭证原子消费掉（条件更新，返回 0 = 已被并发出款吃掉），
 *       保证「一次平台复核最多出一次款」，重复复核/重复点击不会重复打款。</li>
 * </ul>
 */
public interface RefundApprovalMapper extends BaseMapper<RefundApproval> {

    /** 取订单最新一条平台阶段审批行（APPROVE/REJECT/REOPEN），出款前判断审批链终态。 */
    @Select("SELECT * FROM t_refund_approval WHERE order_no = #{orderNo} AND stage = 'PLATFORM' "
            + "ORDER BY id DESC LIMIT 1")
    RefundApproval findLatestPlatform(@Param("orderNo") String orderNo);

    /** 平台复核凭证原子消费：只有 consumed=0 的行能被消费，返回 1 表示本路拿到出款权。 */
    @Update("UPDATE t_refund_approval SET consumed = 1 WHERE id = #{id} AND consumed = 0")
    int consume(@Param("id") Long id);

    /** 订单审批历史（双人留痕查询，按时间正序）。 */
    @Select("SELECT * FROM t_refund_approval WHERE order_no = #{orderNo} ORDER BY id ASC")
    List<RefundApproval> listByOrder(@Param("orderNo") String orderNo);
}
