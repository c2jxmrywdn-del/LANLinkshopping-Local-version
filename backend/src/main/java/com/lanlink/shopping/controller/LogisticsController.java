package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.LogisticsWaybillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物流运单（商家发货 + 轨迹查询）
 */
@RestController
@RequestMapping("/logistics")
public class LogisticsController {

    private final LogisticsWaybillService waybillService;

    public LogisticsController(LogisticsWaybillService waybillService) {
        this.waybillService = waybillService;
    }

    /** 商家发货（创建运单） */
    public record ShipBody(String orderNo, Long providerId, Long templateId, String logisticsNo,
                           BigDecimal weight, BigDecimal volume, Boolean remote, Integer quantity,
                           String senderName, String senderPhone, String senderAddress) {}

    @PostMapping("/ship")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> ship(@Valid @RequestBody ShipBody body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        LogisticsWaybillService.ShipRequest req = new LogisticsWaybillService.ShipRequest(
                body.orderNo(), body.providerId(), body.templateId(), body.logisticsNo(),
                body.weight(), body.volume(), body.remote(), body.quantity(),
                body.senderName(), body.senderPhone(), body.senderAddress());
        return R.ok("发货成功", waybillService.ship(req, userId, request));
    }

    /** 状态流转（商家/运营手工操作，如确认签收） */
    @PostMapping("/transit/{waybillId}")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> transit(@PathVariable Long waybillId,
                                       @RequestParam String status,
                                       @RequestParam(required = false) String note,
                                       HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("状态更新成功", waybillService.transit(waybillId, status, note, userId, request));
    }

    /** 运单分页（可按状态/订单号/服务商过滤） */
    @GetMapping("/waybill/list")
    public R<Page<LogisticsWaybill>> list(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String orderNo,
                                          @RequestParam(required = false) Long providerId) {
        return R.ok(waybillService.page(page, size, status, orderNo, providerId));
    }

    /** 运单轨迹（买家/商家/运营均可查看） */
    @GetMapping("/trace/{waybillId}")
    public R<List<LogisticsTrace>> traces(@PathVariable Long waybillId) {
        return R.ok(waybillService.traces(waybillId));
    }

    /** 轨迹同步（模拟服务商接口推送节点，运营/联调用） */
    @PostMapping("/trace/sync/{waybillId}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> syncTrace(@PathVariable Long waybillId,
                                            @RequestBody List<Map<String, Object>> nodes) {
        return R.ok("轨迹同步完成", waybillService.syncTrace(waybillId, nodes));
    }
}
