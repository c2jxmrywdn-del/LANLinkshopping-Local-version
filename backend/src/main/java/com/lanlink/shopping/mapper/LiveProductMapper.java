package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LiveProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LiveProductMapper extends BaseMapper<LiveProduct> {

    @Select("SELECT * FROM t_live_product WHERE room_id = #{roomId} ORDER BY sort_order ASC, id ASC")
    List<LiveProduct> selectByRoomId(@Param("roomId") Long roomId);
}
