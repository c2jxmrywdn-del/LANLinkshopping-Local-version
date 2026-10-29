package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LiveRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface LiveRoomMapper extends BaseMapper<LiveRoom> {

    @Update("UPDATE t_live_room SET like_count = like_count + #{count} WHERE room_id = #{roomId}")
    int incrementLikes(@Param("roomId") Long roomId, @Param("count") int count);

    /**
     * 观众进出场原子维护：join +1 / leave -1（下限 0），
     * 同语句把峰值抬到当前在线数（GREATEST 保持只增不减）。
     */
    @Update("UPDATE t_live_room " +
            "SET viewer_count = GREATEST(0, viewer_count + #{delta}), " +
            "    peak_viewer_count = GREATEST(peak_viewer_count, GREATEST(0, viewer_count + #{delta})) " +
            "WHERE room_id = #{roomId}")
    int adjustViewers(@Param("roomId") Long roomId, @Param("delta") int delta);

    @Update("UPDATE t_live_room SET pinned_prod_id = #{prodId} WHERE room_id = #{roomId}")
    int updatePinnedProduct(@Param("roomId") Long roomId, @Param("prodId") Long prodId);

    /** 开播（0→1 写 start_time）/关播（→2 写 end_time），状态机由服务层校验 */
    @Update("UPDATE t_live_room SET status = #{status}, update_time = NOW(), " +
            "start_time = CASE WHEN #{status} = 1 AND start_time IS NULL THEN NOW() ELSE start_time END, " +
            "end_time = CASE WHEN #{status} = 2 THEN NOW() ELSE end_time END " +
            "WHERE room_id = #{roomId}")
    int updateRoomStatus(@Param("roomId") Long roomId, @Param("status") Integer status);

    /** 开播条件更新：仅审核通过（review_status=1）可开播——闭筑 TOCTOU 与强关后复播 */
    @Update("UPDATE t_live_room SET status = 1, update_time = NOW(), " +
            "start_time = COALESCE(start_time, NOW()) " +
            "WHERE room_id = #{roomId} AND review_status = 1")
    int startIfApproved(@Param("roomId") Long roomId);

    /** 某商品当前正在直播的直播间（商品详情"直播中"徽章） */
    @Select("SELECT r.* FROM t_live_room r " +
            "JOIN t_live_product lp ON lp.room_id = r.room_id " +
            "WHERE lp.prod_id = #{prodId} AND r.status = 1 " +
            "ORDER BY r.viewer_count DESC LIMIT 1")
    LiveRoom selectActiveRoomByProduct(@Param("prodId") Long prodId);

    /** 直播成交复盘：按商品聚合归因订单（已支付）的 GMV 与单量 */
    @Select("SELECT oi.prod_id AS prodId, COUNT(DISTINCT o.order_no) AS orders, " +
            "COALESCE(SUM(oi.subtotal), 0) AS gmv, COALESCE(SUM(oi.quantity), 0) AS qty " +
            "FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no " +
            "WHERE o.live_id = #{roomId} AND o.pay_status = 1 " +
            "GROUP BY oi.prod_id")
    List<Map<String, Object>> salesByProduct(@Param("roomId") Long roomId);

    /** 直播成交总览：归因已支付订单数与总 GMV */
    @Select("SELECT COUNT(DISTINCT o.order_no) AS orders, COALESCE(SUM(o.total_amount), 0) AS gmv " +
            "FROM t_order o WHERE o.live_id = #{roomId} AND o.pay_status = 1")
    Map<String, Object> salesTotal(@Param("roomId") Long roomId);
}
