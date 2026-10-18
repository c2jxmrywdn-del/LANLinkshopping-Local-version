package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流运单核心服务：发货、状态流转、轨迹同步、异常告警。
 *
 * 设计文档：docs/物流板块业务设计文档.md
 *  - 发货 ship()：校验订单已支付待发货 → 运费计算 → 生成运单 → 订单流转 → 首条轨迹（事务）
 *  - 状态机流转 transit()：沿 LogisticsStateMachine 合法边流转，禁止跳级/回退
 *  - 轨迹同步 syncTrace()：解析服务商节点 → 去重落库 → 状态流转 → 异常告警（幂等）
 */
@Service
public class LogisticsWaybillService {

    private static final DateTimeFormatter WAYBILL_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsTraceMapper traceMapper;
    private final LogisticsAlertMapper alertMapper;
    private final LogisticsProviderMapper providerMapper;
    private final LogisticsTemplateMapper templateMapper;
    private final OrderMapper orderMapper;
    private final LogisticsFreightCalculator freightCalculator;
    private final LogisticsTraceParser traceParser;
    private final MessageService messageService;
    private final AuditService auditService;
    private final SequenceService sequenceService;

    public LogisticsWaybillService(LogisticsWaybillMapper waybillMapper,
                                   LogisticsTraceMapper traceMapper,
                                   LogisticsAlertMapper alertMapper,
                                   LogisticsProviderMapper providerMapper,
                                   LogisticsTemplateMapper templateMapper,
                                   OrderMapper orderMapper,
                                   LogisticsFreightCalculator freightCalculator,
                                   LogisticsTraceParser traceParser,
                                   MessageService messageService,
                                   AuditService auditService,
                                   SequenceService sequenceService) {
        this.waybillMapper = waybillMapper;
        this.traceMapper = traceMapper;
        this.alertMapper = alertMapper;
        this.providerMapper = providerMapper;
        this.templateMapper = templateMapper;
        this.orderMapper = orderMapper;
        this.freightCalculator = freightCalculator;
        this.traceParser = traceParser;
        this.messageService = messageService;
        this.auditService = auditService;
        this.sequenceService = sequenceService;
    }

    /** 发货请求 */
    public record ShipRequest(String orderNo, Long providerId, Long templateId, String logisticsNo,
                              BigDecimal weight, BigDecimal volume, Boolean remote, Integer quantity,
                              String senderName, String senderPhone, String senderAddress) {}

    /**
     * 商家发货：创建运单（事务保证运单+订单+轨迹一致性）。
     */
    @Transactional
    public LogisticsWaybill ship(ShipRequest req, Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        Order order = orderMapper.selectById(req.orderNo());
        if (order == null) throw new BusinessException("订单不存在");
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            throw new BusinessException("订单未支付，不能发货");
        }
        if (order.getOrderStatus() != null && order.getOrderStatus() != 0) {
            throw new BusinessException("订单不在待发货状态，不能重复发货");
        }
        // 原子标记已发货（行锁保证并发下仅 1 个请求能成功，防并发重复发货撞运单号/创建重复运单）
        int shipped = orderMapper.markShippedAtomic(order.getOrderNo());
        if (shipped == 0) {
            throw new BusinessException("订单不在待发货状态或已被并发发货");
        }
        order.setOrderStatus(1);
        LogisticsProvider provider = providerMapper.selectById(req.providerId());
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BusinessException("物流服务商不存在或已停用");
        }
        LogisticsTemplate template = req.templateId() == null ? null : templateMapper.selectById(req.templateId());
        if (template != null && (template.getStatus() == null || template.getStatus() != 1)) {
            throw new BusinessException("运费模板已停用");
        }

        // 运费计算（留痕快照）：先从完整收货地址提取省份，再做区域加价匹配
        BigDecimal orderAmount = order.getTotalAmount();
        String province = LogisticsFreightCalculator.extractProvince(order.getAddress());
        LogisticsFreightCalculator.CalcResult calc = template == null
                ? new LogisticsFreightCalculator.CalcResult(java.math.BigDecimal.ZERO, "{}")
                : freightCalculator.calculate(template, req.weight(), req.volume(),
                        province, Boolean.TRUE.equals(req.remote()), orderAmount, req.quantity());

        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillNo(nextWaybillNo());
        wb.setOrderNo(order.getOrderNo());
        wb.setProviderId(provider.getProviderId());
        wb.setTemplateId(req.templateId());
        wb.setLogisticsNo(req.logisticsNo());
        wb.setFreight(calc.freight());
        wb.setFreightCalcJson(calc.detailJson());
        wb.setWeight(req.weight());
        wb.setVolume(req.volume());
        wb.setSenderName(req.senderName());
        wb.setSenderPhone(req.senderPhone());
        wb.setSenderAddress(req.senderAddress());
        wb.setReceiverName(order.getReceiver());
        wb.setReceiverPhone(order.getPhone());
        wb.setReceiverAddress(order.getAddress());
        wb.setStatus(LogisticsStateMachine.PICKED_UP); // 发货即揽收
        wb.setExceptionFlag(0);
        wb.setShipTime(LocalDateTime.now());
        wb.setCreatedBy(operatorId);
        waybillMapper.insert(wb);

        // 订单流转已由 markShippedAtomic 完成，更新实体状态供返回值/审计留痕
        order.setOrderStatus(1);

        // 首条轨迹（揽收）
        insertTrace(wb.getWaybillId(), LocalDateTime.now(), "商家已发货",
                LogisticsStateMachine.PICKED_UP, false, "manual");

        auditService.record(operatorId, "LOGISTICS_SHIP",
                "发货成功 运单号=" + wb.getWaybillNo() + " 订单=" + order.getOrderNo(), httpReq);
        return wb;
    }

    /** 状态流转（含状态机校验 + 终态时间留痕） */
    @Transactional
    public LogisticsWaybill transit(Long waybillId, String toStatus, String operatorNote, Long operatorId,
                                    jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        if (!LogisticsStateMachine.canTransit(wb.getStatus(), toStatus)) {
            throw new BusinessException("非法状态流转: " + wb.getStatus() + " -> " + toStatus);
        }
        if (wb.getStatus().equals(toStatus)) return wb; // 幂等
        wb.setStatus(toStatus);
        if (LogisticsStateMachine.DELIVERED.equals(toStatus)) wb.setDeliveredTime(LocalDateTime.now());
        waybillMapper.updateById(wb);
        if (operatorNote != null && !operatorNote.isBlank()) {
            insertTrace(wb.getWaybillId(), LocalDateTime.now(), operatorNote, toStatus, false, "manual");
        }
        auditService.record(operatorId, "LOGISTICS_TRANSIT",
                "运单 " + wb.getWaybillNo() + " 状态流转为 " + toStatus, httpReq);
        return wb;
    }

    /**
     * 轨迹同步：导入服务商节点列表（mock/api 共用入口）。
     * @param nodes 每项 {traceTime, location, description}
     */
    @Transactional
    public Map<String, Object> syncTrace(Long waybillId, List<Map<String, Object>> nodes) {
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        int imported = 0, alerts = 0;
        for (Map<String, Object> node : nodes) {
            LocalDateTime traceTime = parseTime(node.get("traceTime"));
            String description = String.valueOf(node.getOrDefault("description", ""));
            String location = String.valueOf(node.getOrDefault("location", ""));
            LogisticsTraceParser.ParsedNode parsed = traceParser.parse(description);
            // 去重（唯一索引兜底 + 先查）
            boolean exists = traceMapper.selectCount(new LambdaQueryWrapper<LogisticsTrace>()
                    .eq(LogisticsTrace::getWaybillId, waybillId)
                    .eq(LogisticsTrace::getTraceTime, traceTime)
                    .eq(LogisticsTrace::getDescription, description)) > 0;
            if (exists) continue;
            insertTrace(waybillId, traceTime, location + " " + description, parsed.status(), parsed.abnormal(), "api");
            imported++;
            // 状态流转（状态机校验）
            if (LogisticsStateMachine.canTransit(wb.getStatus(), parsed.status())
                    && !wb.getStatus().equals(parsed.status())) {
                wb.setStatus(parsed.status());
                if (LogisticsStateMachine.DELIVERED.equals(parsed.status())) wb.setDeliveredTime(traceTime);
                waybillMapper.updateById(wb);
            }
            // 异常告警（幂等）
            if (parsed.abnormal() && parsed.alertType() != null) {
                alerts += raiseAlertIfNeeded(wb, parsed.alertType(), description) ? 1 : 0;
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("waybillNo", wb.getWaybillNo());
        out.put("imported", imported);
        out.put("alerts", alerts);
        out.put("status", wb.getStatus());
        return out;
    }

    /** 异常告警（同运单同类型未处理告警存在时不重复创建），并推送站内信 */
    boolean raiseAlertIfNeeded(LogisticsWaybill wb, String alertType, String content) {
        long active = alertMapper.countActiveByType(wb.getWaybillId(), alertType);
        if (active > 0) return false;
        LogisticsAlert alert = new LogisticsAlert();
        alert.setWaybillId(wb.getWaybillId());
        alert.setAlertType(alertType);
        alert.setAlertLevel("lost".equals(alertType) || "damage".equals(alertType) ? "high" : "medium");
        alert.setContent(content);
        alert.setStatus("pending");
        alertMapper.insert(alert);
        wb.setExceptionFlag(1);
        waybillMapper.updateById(wb);
        // 站内信推送商家/运营（订单归属用户）
        Order order = orderMapper.selectById(wb.getOrderNo());
        if (order != null && order.getUserId() != null) {
            messageService.send(order.getUserId(), "logistics",
                    "物流异常告警", "运单 " + wb.getWaybillNo() + " 出现异常：" + content, wb.getWaybillNo());
        }
        return true;
    }

    private void insertTrace(Long waybillId, LocalDateTime traceTime, String description,
                             String status, boolean abnormal, String source) {
        LogisticsTrace t = new LogisticsTrace();
        t.setWaybillId(waybillId);
        t.setTraceTime(traceTime);
        t.setLocation("");
        t.setDescription(description);
        t.setStatus(status);
        t.setIsAbnormal(abnormal ? 1 : 0);
        t.setSource(source);
        traceMapper.insert(t);
    }

    private String nextWaybillNo() {
        String day = LocalDate.now().format(WAYBILL_DAY);
        // 序列服务原子取号（防并发同序号撞 uk_waybill_no 唯一约束），键含日期天然按日重置
        return "LL" + day + sequenceService.nextNo("waybill:" + day, 4);
    }

    private LocalDateTime parseTime(Object raw) {
        if (raw == null) return LocalDateTime.now();
        if (raw instanceof LocalDateTime ldt) return ldt;
        try {
            return LocalDateTime.parse(String.valueOf(raw).replace(" ", "T"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /** 分页查询（管理端/商家侧） */
    public Page<LogisticsWaybill> page(long page, long size, String status, String orderNo, Long providerId) {
        LambdaQueryWrapper<LogisticsWaybill> qw = new LambdaQueryWrapper<LogisticsWaybill>()
                .orderByDesc(LogisticsWaybill::getWaybillId);
        if (status != null && !status.isBlank()) qw.eq(LogisticsWaybill::getStatus, status);
        if (orderNo != null && !orderNo.isBlank()) qw.eq(LogisticsWaybill::getOrderNo, orderNo);
        if (providerId != null) qw.eq(LogisticsWaybill::getProviderId, providerId);
        return waybillMapper.selectPage(new Page<>(page, size), qw);
    }

    public List<LogisticsTrace> traces(Long waybillId) {
        return traceMapper.selectList(new LambdaQueryWrapper<LogisticsTrace>()
                .eq(LogisticsTrace::getWaybillId, waybillId)
                .orderByAsc(LogisticsTrace::getTraceTime));
    }
}
