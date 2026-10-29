package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.service.LiveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品直播与沉浸式实景带货控制器。
 * 公开接口：直播广场 (/live/rooms)、直播间详情 (/live/room/{id})、商品清单、弹幕查看、点赞。
 * 交互接口：弹幕互动 (/live/room/{id}/comment)。
 * 商户中台：直播控制台 (/live/merchant/**)，需具备 merchant:manage 权限。
 */
@RestController
@RequestMapping("/live")
public class LiveController {

    private final LiveService liveService;
    private final MerchantMapper merchantMapper;
    private final com.lanlink.shopping.mapper.LiveRoomMapper liveRoomMapper;
    private final com.lanlink.shopping.service.LiveRateGuard rateGuard;

    public LiveController(LiveService liveService, MerchantMapper merchantMapper,
                          com.lanlink.shopping.mapper.LiveRoomMapper liveRoomMapper,
                          com.lanlink.shopping.service.LiveRateGuard rateGuard) {
        this.liveService = liveService;
        this.merchantMapper = merchantMapper;
        this.liveRoomMapper = liveRoomMapper;
        this.rateGuard = rateGuard;
    }

    /** 客户端 IP（代理链取首个，与 SecurityRouteGuard 同口径） */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip == null ? "unknown" : ip;
    }

    // ==================== 1. 买家 / 观众公开端 ====================

    /** 直播广场：获取直播间列表 (status: 1直播中, 0预告, 2已结束, 不传则全部) */
    @GetMapping("/rooms")
    public R<List<LiveRoom>> listRooms(@RequestParam(required = false) Integer status) {
        return R.ok(liveService.listRooms(status));
    }

    /** 直播间详情（含商户与当前讲解商品） */
    @GetMapping("/room/{roomId}")
    public R<LiveRoom> getRoomDetail(@PathVariable Long roomId) {
        LiveRoom room = liveService.getRoomDetail(roomId);
        if (room == null) {
            return R.fail(404, "直播间不存在或已下线");
        }
        return R.ok(room);
    }

    /** 直播间商品专柜列表 */
    @GetMapping("/room/{roomId}/products")
    public R<List<LiveProduct>> getRoomProducts(@PathVariable Long roomId) {
        return R.ok(liveService.getRoomProducts(roomId));
    }

    /** 弹幕列表 */
    @GetMapping("/room/{roomId}/comments")
    public R<List<LiveComment>> getComments(@PathVariable Long roomId,
                                            @RequestParam(defaultValue = "30") Integer limit) {
        return R.ok(liveService.getRecentComments(roomId, limit));
    }

    /** 发送点赞 (高频轻量接口；IP 限流防刷) */
    @PostMapping("/room/{roomId}/like")
    public R<Integer> sendLike(@PathVariable Long roomId,
                               @RequestBody(required = false) Map<String, Object> body,
                               HttpServletRequest request) {
        LiveRoom room = liveRoomMapper.selectById(roomId);
        long current = room == null || room.getLikeCount() == null ? 0 : room.getLikeCount();
        if (!rateGuard.allow(clientIp(request) + ":like:" + roomId, 2, 2_000)) {
            return R.ok(null, (int) Math.min(Integer.MAX_VALUE, current));
        }
        int count = 1;
        if (body != null && body.get("count") instanceof Number num) {
            count = Math.min(20, Math.max(1, num.intValue()));
        }
        liveService.addLikes(roomId, count);
        return R.ok(null, (int) Math.min(Integer.MAX_VALUE, current + count));
    }

    /** 发送弹幕 / 评论 (登录用户使用个人昵称，未登录使用默认代表昵称) */
    @PostMapping("/room/{roomId}/comment")
    public R<LiveComment> sendComment(@PathVariable Long roomId,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String content = body != null ? (String) body.get("content") : null;
        String msgType = body != null && body.get("msgType") != null ? (String) body.get("msgType") : "chat";
        if (content == null || content.trim().isEmpty()) {
            return R.fail("请输入弹幕内容");
        }

        if (!rateGuard.allow(clientIp(request) + ":comment:" + roomId, 1, 3_000)) {
            return R.fail("发送太频繁，请稍后再试");
        }

        User u = UserContext.current(request);
        Long userId = u != null ? u.getUserId() : null;
        String nickname = u != null && u.getNickname() != null ? u.getNickname() : "企业采购代表";
        String avatar = "";

        LiveComment comment = liveService.addComment(roomId, userId, nickname, avatar, content, msgType);
        return R.ok("发送成功", comment);
    }

    // ==================== 2. 商户端直播中台控制 ====================

    /** 查询商户当前的活跃直播间 */
    @GetMapping("/merchant/current")
    @RequirePerm("merchant:manage")
    public R<LiveRoom> getMerchantRoom(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) {
            return R.fail(403, "当前账号尚未入驻商户");
        }
        return R.ok(liveService.getMerchantActiveRoom(m.getMerId()));
    }

    /** 创建并开启直播间 */
    @PostMapping("/merchant/create")
    @RequirePerm("merchant:manage")
    public R<LiveRoom> createRoom(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) {
            return R.fail(403, "当前账号尚未入驻商户");
        }

        String title = (String) body.get("title");
        if (title == null || title.trim().isEmpty()) {
            return R.fail("直播主题不能为空");
        }

        LiveRoom room = new LiveRoom();
        room.setTitle(title.trim());
        room.setCoverUrl((String) body.get("coverUrl"));
        room.setStreamUrl((String) body.get("streamUrl"));
        room.setNotice((String) body.get("notice"));
        room.setStatus(body.get("status") != null ? ((Number) body.get("status")).intValue() : 1);

        List<LiveProduct> products = new ArrayList<>();
        if (body.get("products") instanceof List<?> pList) {
            for (Object obj : pList) {
                if (obj instanceof Map<?, ?> pMap) {
                    LiveProduct lp = new LiveProduct();
                    if (pMap.get("prodId") != null) {
                        lp.setProdId(((Number) pMap.get("prodId")).longValue());
                    }
                    if (pMap.get("livePrice") != null) {
                        lp.setLivePrice(new BigDecimal(String.valueOf(pMap.get("livePrice"))));
                    }
                    if (pMap.get("liveStock") != null) {
                        lp.setLiveStock(((Number) pMap.get("liveStock")).intValue());
                    }
                    products.add(lp);
                }
            }
        }

        LiveRoom created = liveService.createRoom(m.getMerId(), userId, room, products);
        return R.ok("直播间创建成功", created);
    }

    /** 主播在直播中置顶讲解指定商品（校验直播间归属 + 商品归属） */
    @PostMapping("/merchant/{roomId}/pin-product")
    @RequirePerm("merchant:manage")
    public R<Boolean> pinProduct(@PathVariable Long roomId,
                                 @RequestBody Map<String, Object> body,
                                 HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (!ownsRoom(userId, roomId)) return R.fail(403, "无权操作他人直播间");
        Long prodId = body != null && body.get("prodId") != null ? ((Number) body.get("prodId")).longValue() : null;
        if (prodId == null) {
            return R.fail("请指定讲解商品");
        }
        boolean ok = liveService.pinProduct(roomId, prodId);
        return R.ok("已置顶当前讲解商品", ok);
    }

    /** 修改直播状态 (1=开播, 2=结束直播)，校验直播间归属 */
    @PostMapping("/merchant/{roomId}/status")
    @RequirePerm("merchant:manage")
    public R<Boolean> updateStatus(@PathVariable Long roomId,
                                   @RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (!ownsRoom(userId, roomId)) return R.fail(403, "无权操作他人直播间");
        Integer status = body != null && body.get("status") != null ? ((Number) body.get("status")).intValue() : null;
        if (status == null) {
            return R.fail("状态参数不能为空");
        }
        boolean ok = liveService.updateRoomStatus(roomId, status);
        return R.ok(status == 1 ? "已开启直播" : "直播已结束", ok);
    }

    // ==================== 3. 商业闭环：观看归因 / 讲解点击 / 成交复盘 ====================

    /** 观众进入直播间（在线数 +1，峰值抬升；公开，浏览器进入/刷新时调用；IP 限流防刷） */
    @PostMapping("/room/{roomId}/join")
    public R<Integer> joinRoom(@PathVariable Long roomId, HttpServletRequest request) {
        if (!rateGuard.allow(clientIp(request) + ":join:" + roomId, 1, 15_000)) {
            LiveRoom cur = liveRoomMapper.selectById(roomId);
            return R.ok(null, cur == null ? 0 : cur.getViewerCount());
        }
        liveService.joinRoom(roomId);
        LiveRoom room = liveRoomMapper.selectById(roomId);
        return R.ok("欢迎来到直播间", room == null ? 0 : room.getViewerCount());
    }

    /** 观众离开直播间（离开/关页时调用，在线数 -1；IP 限流防恶意清零） */
    @PostMapping("/room/{roomId}/leave")
    public R<Void> leaveRoom(@PathVariable Long roomId, HttpServletRequest request) {
        if (rateGuard.allow(clientIp(request) + ":leave:" + roomId, 1, 15_000)) {
            liveService.leaveRoom(roomId);
        }
        return R.ok(null, null);
    }

    /** 商品详情页"直播中"徽章：返回该商品当前直播中的直播间（无则 data=null） */
    @GetMapping("/room-by-product/{prodId}")
    public R<LiveRoom> roomByProduct(@PathVariable Long prodId) {
        return R.ok(liveService.findActiveRoomByProduct(prodId));
    }

    /** 讲解商品点击埋点：返回直播价等行信息，前端据此跳转商品详情（带归因参数；IP 限流防刷） */
    @PostMapping("/room/{roomId}/product/{prodId}/click")
    public R<LiveProduct> trackProductClick(@PathVariable Long roomId, @PathVariable Long prodId,
                                            HttpServletRequest request) {
        if (!rateGuard.allow(clientIp(request) + ":click:" + roomId + ":" + prodId, 1, 2_000)) {
            LiveProduct row = liveService.getRoomProducts(roomId).stream()
                    .filter(x -> prodId.equals(x.getProdId())).findFirst().orElse(null);
            return row == null ? R.fail("该商品未在本直播间挂载") : R.ok(row);
        }
        LiveProduct lp = liveService.trackProductClick(roomId, prodId);
        return lp == null ? R.fail("该商品未在本直播间挂载") : R.ok(lp);
    }

    /** 直播复盘看板（商户）：房间 + 商品漏斗（点击/单量/GMV） + 总成交 */
    @GetMapping("/merchant/{roomId}/stats")
    @RequirePerm("merchant:manage")
    public R<java.util.Map<String, Object>> roomStats(@PathVariable Long roomId, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (!ownsRoom(userId, roomId)) return R.fail(403, "无权查看他人直播间");
        return R.ok(liveService.roomStats(roomId));
    }

    /** 直播间归属校验：当前用户必须是直播间主办商户负责人 */
    private boolean ownsRoom(Long userId, Long roomId) {
        if (userId == null || roomId == null) return false;
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) return false;
        LiveRoom room = liveRoomMapper.selectById(roomId);
        return room != null && m.getMerId().equals(room.getMerId());
    }
}
