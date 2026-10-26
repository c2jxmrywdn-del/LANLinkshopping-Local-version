package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LiveRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LiveRoomMapper extends BaseMapper<LiveRoom> {

    @Update("UPDATE t_live_room SET like_count = like_count + #{count} WHERE room_id = #{roomId}")
    int incrementLikes(@Param("roomId") Long roomId, @Param("count") int count);

    @Update("UPDATE t_live_room SET viewer_count = GREATEST(1, viewer_count + #{delta}) WHERE room_id = #{roomId}")
    int adjustViewers(@Param("roomId") Long roomId, @Param("delta") int delta);

    @Update("UPDATE t_live_room SET pinned_prod_id = #{prodId} WHERE room_id = #{roomId}")
    int updatePinnedProduct(@Param("roomId") Long roomId, @Param("prodId") Long prodId);

    @Update("UPDATE t_live_room SET status = #{status}, update_time = NOW() WHERE room_id = #{roomId}")
    int updateRoomStatus(@Param("roomId") Long roomId, @Param("status") Integer status);
}
