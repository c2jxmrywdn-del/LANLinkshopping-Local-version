package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.entity.LiveRoom;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.LiveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 直播治理（平台运营后台）。
 *  - GET  /admin/live/rooms?status=      全站直播间分页总览
 *  - POST /admin/live/rooms/{id}/force-close   强制关播（违规治理）
 *  - GET  /admin/live/rooms/{id}/stats         任意直播间复盘看板
 */
@RestController
@RequestMapping("/admin/live")
@RequirePerm("admin:all")
public class AdminLiveController {

    private final LiveService liveService;
    private final com.lanlink.shopping.mapper.LiveRoomMapper liveRoomMapper;

    public AdminLiveController(LiveService liveService,
                               com.lanlink.shopping.mapper.LiveRoomMapper liveRoomMapper) {
        this.liveService = liveService;
        this.liveRoomMapper = liveRoomMapper;
    }

    @GetMapping("/rooms")
    public R<List<LiveRoom>> rooms(@RequestParam(required = false) Integer status) {
        return R.ok(liveService.listRooms(status));
    }

    @PostMapping("/{roomId}/force-close")
    public R<Boolean> forceClose(@PathVariable Long roomId) {
        boolean ok = liveService.adminForceClose(roomId);
        return R.ok(ok ? "已强制关播" : "直播间不存在或已结束", ok);
    }

    /** 平台审核：批准（商户方可开播）/ 驳回（下线并回显原因），结果站内通知商户 */
    @PostMapping("/{roomId}/review")
    public R<Boolean> review(@PathVariable Long roomId,
                             @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        // approved 类型容错：Boolean 或 "true"/"false" 字符串均可（防前端序列化差异误驳回）
        Object rawApproved = body == null ? null : body.get("approved");
        boolean approved = Boolean.TRUE.equals(rawApproved) || "true".equalsIgnoreCase(String.valueOf(rawApproved));
        String reason = body != null && body.get("reason") != null ? String.valueOf(body.get("reason")) : null;
        Long adminUserId = com.lanlink.shopping.config.UserContext.currentUserId(request);
        return R.ok(approved ? "已批准，商户可开播" : "已驳回并通知商户", liveService.reviewRoom(roomId, approved, reason, adminUserId));
    }

    @GetMapping("/{roomId}/stats")
    public R<Map<String, Object>> stats(@PathVariable Long roomId) {
        return R.ok(liveService.roomStats(roomId));
    }
}
