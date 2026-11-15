package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.StockFlow;
import org.apache.ibatis.annotations.Insert;

/**
 * 库存流水 Mapper（P1-3 幂等基础设施）。
 *
 * <p>包路径命中 {@code LanlinkShoppingApplication} 的 {@code @MapperScan("com.lanlink.shopping.mapper")}，
 * 无需额外注册。
 */
public interface StockFlowMapper extends BaseMapper<StockFlow> {

    /**
     * 抢占幂等键：唯一键 {@code (biz_type, biz_no, prod_id, order_item_id)} 冲突时静默忽略。
     *
     * @return 1 = 本次抢到（首次回补，可继续执行库存 UPDATE）；0 = 已回补过（调用方须跳过 UPDATE）
     */
    @Insert("INSERT IGNORE INTO t_stock_flow " +
            "(biz_type, biz_no, prod_id, order_item_id, quantity, order_no, operator_id, remark) " +
            "VALUES (#{bizType}, #{bizNo}, #{prodId}, #{orderItemId}, #{quantity}, #{orderNo}, #{operatorId}, #{remark})")
    int insertIgnore(StockFlow flow);
}
