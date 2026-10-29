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

    // ==================== 商业闭环：观看归因 / 成交复盘 / 治理 ====================

    /** 某商品当前正在直播的直播间（商品详情"直播中"徽章；无则 null） */
    LiveRoom findActiveRoomByProduct(Long prodId);

    /** 观众进入直播间（在线数 +1，峰值同步抬升） */
    void joinRoom(Long roomId);

    /** 观众离开直播间（在线数 -1，下限 0） */
    void leaveRoom(Long roomId);

    /** 讲解商品点击埋点（返回该行 live 商品，含直播价；未挂载返回 null） */
    LiveProduct trackProductClick(Long roomId, Long prodId);

    /** 直播复盘看板：房间 + 商品清单 + 按商品成交（GMV/单量） + 总成交（商户端/管理端共用） */
    java.util.Map<String, Object> roomStats(Long roomId);

    /** 管理端：强制关播（违规治理，写 end_time） */
    boolean adminForceClose(Long roomId);

    /**
     * 平台审核直播间（准入门槛）：approved=true 通过（商户方可开播）；false 驳回并下线。
     * 审核结果站内通知商户；管理员在前台（用户端）即可观看直播，后台不做观看接入。
     */
    boolean reviewRoom(Long roomId, boolean approved, String reason, Long adminUserId);
}
