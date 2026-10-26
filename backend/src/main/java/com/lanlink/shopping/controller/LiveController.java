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

    public LiveController(LiveService liveService, MerchantMapper merchantMapper) {
        this.liveService = liveService;
        this.merchantMapper = merchantMapper;
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

    /** 发送点赞 (高频轻量接口) */
    @PostMapping("/room/{roomId}/like")
    public R<Integer> sendLike(@PathVariable Long roomId,
                               @RequestBody(required = false) Map<String, Object> body) {
        int count = 1;
        if (body != null && body.containsKey("count")) {
            count = Math.min(20, Math.max(1, ((Number) body.get("count")).intValue()));
        }
        return R.ok(liveService.addLikes(roomId, count));
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

    /** 主播在直播中置顶讲解指定商品 */
    @PostMapping("/merchant/{roomId}/pin-product")
    @RequirePerm("merchant:manage")
    public R<Boolean> pinProduct(@PathVariable Long roomId,
                                 @RequestBody Map<String, Object> body) {
        Long prodId = body != null && body.get("prodId") != null ? ((Number) body.get("prodId")).longValue() : null;
        if (prodId == null) {
            return R.fail("请指定讲解商品");
        }
        boolean ok = liveService.pinProduct(roomId, prodId);
        return R.ok("已置顶当前讲解商品", ok);
    }

    /** 修改直播状态 (1=开播, 2=结束直播) */
    @PostMapping("/merchant/{roomId}/status")
    @RequirePerm("merchant:manage")
    public R<Boolean> updateStatus(@PathVariable Long roomId,
                                   @RequestBody Map<String, Object> body) {
        Integer status = body != null && body.get("status") != null ? ((Number) body.get("status")).intValue() : null;
        if (status == null) {
            return R.fail("状态参数不能为空");
        }
        boolean ok = liveService.updateRoomStatus(roomId, status);
        return R.ok(status == 1 ? "已开启直播" : "直播已结束", ok);
    }
}
