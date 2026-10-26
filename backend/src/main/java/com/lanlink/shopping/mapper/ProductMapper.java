package com.lanlink.shopping.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ProductMapper extends BaseMapper<Product> {

    /** 当前有在售商品的行业 ID（行业基准聚合用，避免在服务层构造 Lambda 查询）。 */
    @Select("SELECT DISTINCT ind_id FROM t_product " +
            "WHERE status = 1 AND review_status = 1 AND ind_id IS NOT NULL")
    List<Long> distinctIndustryIds();

    /**
     * 库存原子扣减（防超卖/防并发篡改，需求 2）。
     *
     * 使用条件更新（WHERE stock >= quantity）实现数据库层面乐观锁：
     * 仅当剩余库存足够时扣减并加销量，返回受影响行数 1 表示成功、0 表示库存不足。
     * 并发场景下避免「读-改-写」竞态导致的超卖或负库存。
     */
    @Update("UPDATE t_product SET stock = stock - #{quantity}, sales = sales + #{quantity}, update_time = NOW() " +
            "WHERE prod_id = #{prodId} AND stock >= #{quantity} AND deleted = 0")
    int deductStockAtomic(@Param("prodId") Long prodId, @Param("quantity") Integer quantity);

    /**
     * 库存原子回滚（取消订单/退款恢复库存，需求 2）。
     * 销量用 GREATEST(0, sales - quantity) 钳位，避免回退为负数。
     */
    @Update("UPDATE t_product SET stock = stock + #{quantity}, " +
            "sales = GREATEST(0, sales - #{quantity}), update_time = NOW() " +
            "WHERE prod_id = #{prodId} AND deleted = 0")
    int restoreStockAtomic(@Param("prodId") Long prodId, @Param("quantity") Integer quantity);
}
