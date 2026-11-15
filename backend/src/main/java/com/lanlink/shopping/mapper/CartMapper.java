package com.lanlink.shopping.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.Cart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface CartMapper extends BaseMapper<Cart> {

    /**
     * 加购的原子累加（并发安全）。
     *
     * <p>原实现是「selectOne → setQuantity(exist+qty) → updateById」：同一用户并发加购
     * 同一商品时两个请求读到同一个旧数量，后写的覆盖先写的，购物车数量少于实际点击次数
     * （典型丢失更新）。这里改成单条 INSERT ... ON DUPLICATE KEY UPDATE 让数据库做累加，
     * 依赖唯一键 {@code uk_cart_user_prod(user_id, prod_id)}。
     *
     * @return 1 = 新增，2 = 命中唯一键并累加（MySQL 语义），调用方不依赖具体值
     */
    @Insert("INSERT INTO t_cart (user_id, prod_id, quantity, checked, create_time)"
            + " VALUES (#{userId}, #{prodId}, #{quantity}, 1, NOW())"
            + " ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)")
    int upsertAdd(@Param("userId") Long userId,
                  @Param("prodId") Long prodId,
                  @Param("quantity") Integer quantity);
}
