package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LiveProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LiveProductMapper extends BaseMapper<LiveProduct> {

    @Select("SELECT * FROM t_live_product WHERE room_id = #{roomId} ORDER BY sort_order ASC, id ASC")
    List<LiveProduct> selectByRoomId(@Param("roomId") Long roomId);

    /** 讲解商品点击计数（归因漏斗，原子自增） */
    @Update("UPDATE t_live_product SET click_count = click_count + 1 WHERE id = #{id}")
    int incrementClick(@Param("id") Long id);

    /** 直播间内点播商品行（归因：商品详情 → live_product.id） */
    @Select("SELECT * FROM t_live_product WHERE room_id = #{roomId} AND prod_id = #{prodId} LIMIT 1")
    LiveProduct selectByRoomAndProduct(@Param("roomId") Long roomId, @Param("prodId") Long prodId);
}
