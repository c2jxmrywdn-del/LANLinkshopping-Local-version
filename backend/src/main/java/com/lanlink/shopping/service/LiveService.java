package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LiveComment;
import com.lanlink.shopping.entity.LiveProduct;
import com.lanlink.shopping.entity.LiveRoom;

import java.util.List;

public interface LiveService {

    /** 获取直播间列表 (status: null全部, 1直播中, 0预告, 2已结束) */
    List<LiveRoom> listRooms(Integer status);

    /** 获取直播间详情（包含商户主体与讲解中商品） */
    LiveRoom getRoomDetail(Long roomId);

    /** 获取直播间专享商品清单（包含商品完整信息） */
    List<LiveProduct> getRoomProducts(Long roomId);

    /** 获取直播间最新弹幕与互动消息 */
    List<LiveComment> getRecentComments(Long roomId, int limit);

    /** 发送直播间弹幕/抢购消息 */
    LiveComment addComment(Long roomId, Long userId, String nickname, String avatar, String content, String msgType);

    /** 点赞增量 */
    int addLikes(Long roomId, int count);

    /** 主播置顶讲解商品 */
    boolean pinProduct(Long roomId, Long prodId);

    /** 商户创建直播间 */
    LiveRoom createRoom(Long merId, Long userId, LiveRoom room, List<LiveProduct> products);

    /** 商户切换直播状态 (1=开播, 2=下播) */
    boolean updateRoomStatus(Long roomId, Integer status);

    /** 查询商户名下当前活跃直播间 */
    LiveRoom getMerchantActiveRoom(Long merId);
}
