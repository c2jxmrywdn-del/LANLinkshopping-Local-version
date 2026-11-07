package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.OrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 原子增加已发货数量并更新发货状态（行锁防并发超发）。
     * 只有当 (quantity - COALESCE(shipped_quantity, 0)) >= qty 时才成功更新，返回 1；
     * 否则返回 0 表示可发数量不足，由上层抛出超发异常并回滚。
     */
    @Update("UPDATE t_order_item " +
            "SET shipped_quantity = COALESCE(shipped_quantity, 0) + #{qty}, " +
            "    shipping_status = CASE WHEN COALESCE(shipped_quantity, 0) + #{qty} >= quantity THEN 'shipped' ELSE 'partial' END " +
            "WHERE item_id = #{itemId} AND (quantity - COALESCE(shipped_quantity, 0)) >= #{qty}")
    int increaseShippedQuantityAtomic(@Param("itemId") Long itemId, @Param("qty") int qty);
}

