package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsDocument;
import com.lanlink.shopping.entity.LogisticsPodRecord;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.LogisticsWaybillItem;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsDocumentMapper;
import com.lanlink.shopping.mapper.LogisticsPodRecordMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillItemMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流运单核心服务：发货、多包裹拆单、状态流转、轨迹同步、地图聚合与异常告警。
 *
 * 工业品 B2B 核心扩展：
 *  - ship()：兼容整单一次性发货（自动关联全部明细与坐标）
 *  - shipSplit()：工业品多包裹拆单发货，CAS行锁扣减防超发，订单聚合状态机(0待发货/4部分发货/1已发货)
 *  - transit()：沿 LogisticsStateMachine 合法流转，签收联动订单完成（仅全包裹送达闭环）
 *  - orderWaybills()：全景多运单聚合查询，输出 GIS 动态矢量地图数据与卡车在途轨迹
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
    private final OrderItemMapper orderItemMapper;
    private final LogisticsWaybillItemMapper waybillItemMapper;

    /** 商户档案（可选注入：发货/签收的归属校验用，缺省环境放行由权限注解兜底） */
    @Autowired(required = false)
    private MerchantMapper merchantMapper;

    @Autowired(required = false)
    private LogisticsDocumentMapper documentMapper;

    @Autowired(required = false)
    private LogisticsPodRecordMapper podRecordMapper;

    /** tracking 模块发货事件发布器（可选注入：历史单测直构时为 null，publish 守卫跳过） */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 11 参数构造器，供历史单测无缝兼容使用 */
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
        this(waybillMapper, traceMapper, alertMapper, providerMapper, templateMapper,
                orderMapper, freightCalculator, traceParser, messageService, auditService,
                sequenceService, null, null);
    }

    /** 主构造器（Spring 自动装配所有依赖） */
    @Autowired
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
                                   SequenceService sequenceService,
                                   @Autowired(required = false) OrderItemMapper orderItemMapper,
                                   @Autowired(required = false) LogisticsWaybillItemMapper waybillItemMapper) {
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
        this.orderItemMapper = orderItemMapper;
        this.waybillItemMapper = waybillItemMapper;
    }

    /** 传统整单发货请求 */
    public record ShipRequest(String orderNo, Long providerId, Long templateId, String logisticsNo,
                              BigDecimal weight, BigDecimal volume, Boolean remote, Integer quantity,
                              String senderName, String senderPhone, String senderAddress) {}

    /** 拆单商品明细项 */
    public record SplitItem(Long orderItemId, Integer quantity) {}

    /** 随货同行单证项 (CoA/MTR/CoC) */
    public record CoaDocItem(
            String name,
            String url,
            String batchNo,
            String type,
            Long size,
            String hash
    ) {}

    /** 多包裹拆单发货请求 */
    public record SplitShipRequest(
            String orderNo,
            Long providerId,
            Long templateId,
            String logisticsNo,
            BigDecimal weight,
            BigDecimal volume,
            Boolean remote,
            String senderName,
            String senderPhone,
            String senderAddress,
            String transportMode,   // express / ltl / ftl / direct / self
            String packageNo,       // 如 "PKG-01"
            Integer packageIndex,   // 如 1
            Integer packageTotal,   // 如 2
            List<SplitItem> items,  // 本包裹包含的商品明细
            List<CoaDocItem> coaDocs, // 随货同行单证 (CoA/MTR/CoC)
            LogisticsFreightCalculator.VasRequest vas // 工业增值服务项
    ) {
        public SplitShipRequest(
                String orderNo, Long providerId, Long templateId, String logisticsNo,
                BigDecimal weight, BigDecimal volume, Boolean remote,
                String senderName, String senderPhone, String senderAddress,
                String transportMode, String packageNo, Integer packageIndex,
                Integer packageTotal, List<SplitItem> items
        ) {
            this(orderNo, providerId, templateId, logisticsNo, weight, volume, remote,
                    senderName, senderPhone, senderAddress, transportMode, packageNo,
                    packageIndex, packageTotal, items, null, null);
        }
    }

    /** 现场 e-POD 签收回执提交入参 */
    public record PodUploadRequest(
            Long waybillId,
            String signerName,
            String signerPhone,
            LocalDateTime signedTime,
            String stampPhotoUrl,
            List<String> cargoPhotos,
            BigDecimal geoLng,
            BigDecimal geoLat,
            String signRemark
    ) {}

    /** 现场 e-POD 验签审核入参 */
    public record PodAuditRequest(
            String action, // approve / reject
            String auditNote
    ) {}

    /** e-POD 回执视图对象 */
    public record PodInfoVO(
            Long podId,
            String signerName,
            String signerPhone,
            LocalDateTime signedTime,
            String stampPhotoUrl,
            List<String> cargoPhotos,
            BigDecimal geoLng,
            BigDecimal geoLat,
            String signRemark,
            String auditStatus,
            String auditNote,
            LocalDateTime auditTime
    ) {}

    /** 地图路径节点 VO */
    public record MapNodeVO(
            Double lng,
            Double lat,
            String name,
            String hubType,
            LocalDateTime time,
            String status,
            Integer isAbnormal,
            String description
    ) {}

    /** 卡车在途位置与航向 VO */
    public record TruckPositionVO(
            Double lng,
            Double lat,
            Double headingAngle,
            String status,
            String locationName,
            Double progressFraction
    ) {}

    /** GIS 路线与轨迹 VO */
    public record MapRouteVO(
            GeoCoordinateRegistry.GeoPoint origin,
            GeoCoordinateRegistry.GeoPoint destination,
            List<MapNodeVO> nodes,
            TruckPositionVO truckPosition,
            Double distanceKm
    ) {}

    /** 运单明细 VO（含包裹明细、CoA质保证书、e-POD回执、轨迹与地图数据） */
    public record WaybillDetailVO(
            LogisticsWaybill waybill,
            String providerName,
            String providerCode,
            List<LogisticsWaybillItem> items,
            List<LogisticsDocument> coaDocs,
            PodInfoVO podInfo,
            List<LogisticsTrace> traces,
            MapRouteVO mapRoute
    ) {
        public WaybillDetailVO(
                LogisticsWaybill waybill,
                String providerName,
                String providerCode,
                List<LogisticsWaybillItem> items,
                List<LogisticsTrace> traces,
                MapRouteVO mapRoute
        ) {
            this(waybill, providerName, providerCode, items, List.of(), null, traces, mapRoute);
        }
    }

    /** 订单全景多运单聚合 VO */
    public record OrderWaybillAggregation(
            String orderNo,
            Integer orderStatus,
            String orderStatusDesc,
            Integer totalWaybills,
            Boolean allShipped,
            List<OrderItem> orderItems,
            List<WaybillDetailVO> waybills
    ) {}

    /**
     * 商家传统发货：整单一次性发货（兼容存量与单测）。
     */
    @Transactional
    public LogisticsWaybill ship(ShipRequest req, Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        Order order = orderMapper.selectById(req.orderNo());
        if (order == null) throw new BusinessException("订单不存在");
        assertOrderBelongsToOperator(order.getOrderNo(), operatorId, "发货");
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            throw new BusinessException("订单未支付，不能发货");
        }
        if (order.getRefundStatus() != null &&
                List.of("applied", "merchant_approved", "shipping_back", "received", "processing").contains(order.getRefundStatus())) {
            throw new BusinessException("该订单正在售后退款流程中（" + order.getRefundStatus() + "），禁止发货");
        }
        if (order.getOrderStatus() != null && order.getOrderStatus() != 0) {
            throw new BusinessException("订单不在待发货状态，不能重复发货");
        }
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

        BigDecimal orderAmount = order.getTotalAmount();
        String province = LogisticsFreightCalculator.extractProvince(order.getAddress());
        LogisticsFreightCalculator.CalcResult calc = template == null
                ? new LogisticsFreightCalculator.CalcResult(BigDecimal.ZERO, "{}")
                : freightCalculator.calculate(template, req.weight(), req.volume(),
                province, Boolean.TRUE.equals(req.remote()), orderAmount, req.quantity());

        GeoCoordinateRegistry.GeoPoint senderPt = GeoCoordinateRegistry.lookup(req.senderAddress(), null);
        GeoCoordinateRegistry.GeoPoint receiverPt = GeoCoordinateRegistry.lookup(order.getAddress(), null);

        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillNo(nextWaybillNo());
        wb.setOrderNo(order.getOrderNo());
        wb.setPackageNo("PKG-01");
        wb.setPackageIndex(1);
        wb.setPackageTotal(1);
        wb.setTransportMode("express");
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
        wb.setSenderLng(senderPt.lngBd());
        wb.setSenderLat(senderPt.latBd());
        wb.setReceiverLng(receiverPt.lngBd());
        wb.setReceiverLat(receiverPt.latBd());
        wb.setCurrentLng(senderPt.lngBd());
        wb.setCurrentLat(senderPt.latBd());
        wb.setCurrentLocation(senderPt.name());
        wb.setStatus(LogisticsStateMachine.PICKED_UP);
        wb.setExceptionFlag(0);
        wb.setShipTime(LocalDateTime.now());
        wb.setCreatedBy(operatorId);
        waybillMapper.insert(wb);

        // 记录明细发货状态
        if (orderItemMapper != null) {
            List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, order.getOrderNo()));
            for (OrderItem oi : items) {
                int remain = (oi.getQuantity() != null ? oi.getQuantity() : 1) - (oi.getShippedQuantity() != null ? oi.getShippedQuantity() : 0);
                if (remain > 0) {
                    orderItemMapper.increaseShippedQuantityAtomic(oi.getItemId(), remain);
                }
                if (waybillItemMapper != null) {
                    LogisticsWaybillItem lwi = new LogisticsWaybillItem();
                    lwi.setWaybillId(wb.getWaybillId());
                    lwi.setWaybillNo(wb.getWaybillNo());
                    lwi.setOrderNo(order.getOrderNo());
                    lwi.setOrderItemId(oi.getItemId());
                    lwi.setProdId(oi.getProdId());
                    lwi.setProdName(oi.getProdName());
                    lwi.setShippedQty(remain > 0 ? remain : 1);
                    waybillItemMapper.insert(lwi);
                }
            }
        }

        // 首条轨迹
        insertTrace(wb.getWaybillId(), LocalDateTime.now(), "商家已发货并完成包裹打包",
                LogisticsStateMachine.PICKED_UP, false, "manual",
                senderPt.lngBd(), senderPt.latBd(), senderPt.name(), "warehouse");

        auditService.record(operatorId, "LOGISTICS_SHIP",
                "整单发货成功 运单号=" + wb.getWaybillNo() + " 订单=" + order.getOrderNo(), httpReq);
        if (eventPublisher != null) eventPublisher.publishEvent(new com.lanlink.shopping.module.tracking.event.WaybillShippedEvent(this, wb.getWaybillId(), wb.getOrderNo(), wb.getProviderId(), wb.getLogisticsNo(), wb.getWaybillNo()));
        return wb;
    }

    /**
     * 工业品多包裹拆单发货：支持按商品 SKU 拆单、分批多次发货，行锁 CAS 严格防并发超发。
     */
    @Transactional
    public LogisticsWaybill shipSplit(SplitShipRequest req, Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        if (orderItemMapper == null || waybillItemMapper == null) {
            throw new BusinessException("拆单发货组件未完全装载");
        }
        Order order = orderMapper.selectById(req.orderNo());
        if (order == null) throw new BusinessException("订单不存在");
        assertOrderBelongsToOperator(order.getOrderNo(), operatorId, "发货");
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            throw new BusinessException("订单未支付，不能发货");
        }
        if (order.getRefundStatus() != null &&
                List.of("applied", "merchant_approved", "shipping_back", "received", "processing").contains(order.getRefundStatus())) {
            throw new BusinessException("该订单正在售后退款流程中（" + order.getRefundStatus() + "），禁止发货");
        }
        if (order.getOrderStatus() == null || (order.getOrderStatus() != 0 && order.getOrderStatus() != 4)) {
            throw new BusinessException("订单当前状态不允许拆单发货（仅待发货或部分发货可操作）");
        }
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("拆单发货明细不能为空");
        }

        List<LogisticsWaybillItem> pendingWaybillItems = new ArrayList<>();
        int totalShippedQty = 0;

        // 统一按 orderItemId 升序加行锁，杜绝多线程并发拆包时的死锁 (Deadlock Prevention)
        List<SplitItem> sortedItems = req.items().stream()
                .filter(si -> si != null && si.orderItemId() != null)
                .sorted(java.util.Comparator.comparing(SplitItem::orderItemId))
                .toList();

        for (SplitItem si : sortedItems) {
            if (si.quantity() == null || si.quantity() <= 0) {
                continue;
            }
            OrderItem oi = orderItemMapper.selectById(si.orderItemId());
            if (oi == null || !oi.getOrderNo().equals(order.getOrderNo())) {
                throw new BusinessException("订单明细不存在或不属于该订单: ID=" + si.orderItemId());
            }

            // 原子 CAS 行锁扣减，防并发超发
            int updated = orderItemMapper.increaseShippedQuantityAtomic(si.orderItemId(), si.quantity());
            if (updated == 0) {
                int remain = (oi.getQuantity() != null ? oi.getQuantity() : 0) - (oi.getShippedQuantity() != null ? oi.getShippedQuantity() : 0);
                throw new BusinessException("商品 [" + oi.getProdName() + "] 可发货数量不足或已被并发拆发（剩余可发: " + remain + "）");
            }

            LogisticsWaybillItem lwi = new LogisticsWaybillItem();
            lwi.setOrderNo(order.getOrderNo());
            lwi.setOrderItemId(oi.getItemId());
            lwi.setProdId(oi.getProdId());
            lwi.setProdName(oi.getProdName());
            lwi.setShippedQty(si.quantity());
            pendingWaybillItems.add(lwi);
            totalShippedQty += si.quantity();
        }

        if (pendingWaybillItems.isEmpty()) {
            throw new BusinessException("本次发货商品数量必须大于 0");
        }

        // 重新检查该订单全部明细的发货进度，原子流转订单状态
        List<OrderItem> allItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, order.getOrderNo()));
        boolean allFullyShipped = allItems.stream().allMatch(oi ->
                oi.getShippedQuantity() != null && oi.getShippedQuantity() >= (oi.getQuantity() != null ? oi.getQuantity() : 0));

        if (allFullyShipped) {
            orderMapper.markFullyShippedAtomic(order.getOrderNo());
            order.setOrderStatus(1);
        } else {
            orderMapper.markPartialShippedAtomic(order.getOrderNo());
            order.setOrderStatus(4);
        }

        LogisticsProvider provider = providerMapper.selectById(req.providerId());
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BusinessException("物流服务商不存在或已停用");
        }
        LogisticsTemplate template = req.templateId() == null ? null : templateMapper.selectById(req.templateId());
        if (template != null && (template.getStatus() == null || template.getStatus() != 1)) {
            throw new BusinessException("运费模板已停用");
        }

        BigDecimal orderAmount = order.getTotalAmount();
        String province = LogisticsFreightCalculator.extractProvince(order.getAddress());
        LogisticsFreightCalculator.CalcResult calc = template == null
                ? new LogisticsFreightCalculator.CalcResult(BigDecimal.ZERO, "{}")
                : freightCalculator.calculate(template, req.weight(), req.volume(),
                province, Boolean.TRUE.equals(req.remote()), orderAmount, totalShippedQty, req.vas());

        GeoCoordinateRegistry.GeoPoint senderPt = GeoCoordinateRegistry.lookup(req.senderAddress(), null);
        GeoCoordinateRegistry.GeoPoint receiverPt = GeoCoordinateRegistry.lookup(order.getAddress(), null);

        int pIndex = req.packageIndex() != null && req.packageIndex() > 0 ? req.packageIndex() : 1;
        int pTotal = req.packageTotal() != null && req.packageTotal() > 0 ? req.packageTotal() : Math.max(1, pIndex);
        String pNo = req.packageNo() != null && !req.packageNo().isBlank() ? req.packageNo() : ("PKG-" + String.format("%02d", pIndex));
        String tMode = req.transportMode() != null && !req.transportMode().isBlank() ? req.transportMode() : "express";

        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillNo(nextWaybillNo());
        wb.setOrderNo(order.getOrderNo());
        wb.setPackageNo(pNo);
        wb.setPackageIndex(pIndex);
        wb.setPackageTotal(pTotal);
        wb.setTransportMode(tMode);
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
        wb.setSenderLng(senderPt.lngBd());
        wb.setSenderLat(senderPt.latBd());
        wb.setReceiverLng(receiverPt.lngBd());
        wb.setReceiverLat(receiverPt.latBd());
        wb.setCurrentLng(senderPt.lngBd());
        wb.setCurrentLat(senderPt.latBd());
        wb.setCurrentLocation(senderPt.name());
        wb.setStatus(LogisticsStateMachine.PICKED_UP);
        wb.setExceptionFlag(0);
        wb.setShipTime(LocalDateTime.now());
        wb.setCreatedBy(operatorId);
        if (req.coaDocs() != null && !req.coaDocs().isEmpty()) {
            try {
                wb.setCoaDocsJson(JSON.writeValueAsString(req.coaDocs()));
            } catch (Exception ignored) {}
        }
        waybillMapper.insert(wb);

        // 插入随货同行单证 (CoA/MTR)
        if (req.coaDocs() != null && !req.coaDocs().isEmpty() && documentMapper != null) {
            for (CoaDocItem doc : req.coaDocs()) {
                if (doc == null || doc.url() == null || doc.url().isBlank()) continue;
                LogisticsDocument ld = new LogisticsDocument();
                ld.setWaybillId(wb.getWaybillId());
                ld.setWaybillNo(wb.getWaybillNo());
                ld.setOrderNo(order.getOrderNo());
                ld.setDocType(doc.type() != null ? doc.type() : "coa");
                ld.setDocName(doc.name() != null ? doc.name() : "出厂检验报告 (CoA)");
                ld.setFileUrl(doc.url());
                ld.setFileSize(doc.size() != null ? doc.size() : 0L);
                ld.setMimeType(doc.url().endsWith(".pdf") ? "application/pdf" : "image/jpeg");
                ld.setSha256Hash(doc.hash());
                ld.setBatchNo(doc.batchNo());
                ld.setUploaderId(operatorId);
                documentMapper.insert(ld);
            }
        }

        // 插入运单商品明细
        for (LogisticsWaybillItem item : pendingWaybillItems) {
            item.setWaybillId(wb.getWaybillId());
            item.setWaybillNo(wb.getWaybillNo());
            waybillItemMapper.insert(item);
        }

        // 首条轨迹
        insertTrace(wb.getWaybillId(), LocalDateTime.now(), "包裹 [" + pNo + "] 已完成备货出库，等待物流承运",
                LogisticsStateMachine.PICKED_UP, false, "manual",
                senderPt.lngBd(), senderPt.latBd(), senderPt.name(), "warehouse");

        auditService.record(operatorId, "LOGISTICS_SPLIT_SHIP",
                "拆单发货成功 运单号=" + wb.getWaybillNo() + " 包裹=" + pNo + " 状态=" + (allFullyShipped ? "全部发完" : "部分发货"), httpReq);
        if (eventPublisher != null) eventPublisher.publishEvent(new com.lanlink.shopping.module.tracking.event.WaybillShippedEvent(this, wb.getWaybillId(), wb.getOrderNo(), wb.getProviderId(), wb.getLogisticsNo(), wb.getWaybillNo()));
        return wb;
    }

    /** 状态流转（含状态机校验 + 终态时间留痕） */
    @Transactional
    public LogisticsWaybill transit(Long waybillId, String toStatus, String operatorNote, Long operatorId,
                                    jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        assertOrderBelongsToOperator(wb.getOrderNo(), operatorId, "操作该运单");
        if (!LogisticsStateMachine.canTransit(wb.getStatus(), toStatus)) {
            throw new BusinessException("非法状态流转: " + wb.getStatus() + " -> " + toStatus);
        }
        if (wb.getStatus().equals(toStatus)) return wb; // 幂等
        wb.setStatus(toStatus);
        if (LogisticsStateMachine.DELIVERED.equals(toStatus)) {
            wb.setDeliveredTime(LocalDateTime.now());
            if (wb.getReceiverLng() != null) {
                wb.setCurrentLng(wb.getReceiverLng());
                wb.setCurrentLat(wb.getReceiverLat());
                wb.setCurrentLocation("客户已签收");
            }
            // 签收联动订单完成（仅全包裹送达时闭环）
            completeOrderIfShipped(wb.getOrderNo());
        }
        waybillMapper.updateById(wb);
        if (operatorNote != null && !operatorNote.isBlank()) {
            insertTrace(wb.getWaybillId(), LocalDateTime.now(), operatorNote, toStatus, false, "manual");
        }
        auditService.record(operatorId, "LOGISTICS_TRANSIT",
                "运单 " + wb.getWaybillNo() + " 状态流转为 " + toStatus, httpReq);
        return wb;
    }

    /**
     * 工业现场 e-POD 签收回单上传（买家/现场收货人/司机上传公章照片与货物外观照片）。
     */
    @Transactional
    public LogisticsPodRecord uploadPod(PodUploadRequest req, Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        if (req == null || req.waybillId() == null) {
            throw new BusinessException("运单ID不能为空");
        }
        LogisticsWaybill wb = waybillMapper.selectById(req.waybillId());
        if (wb == null) {
            throw new BusinessException("运单不存在");
        }
        Order order = orderMapper.selectById(wb.getOrderNo());
        if (order == null) {
            throw new BusinessException("关联订单不存在");
        }
        if (req.signerName() == null || req.signerName().isBlank()) {
            throw new BusinessException("现场签收人姓名不能为空");
        }
        if (req.stampPhotoUrl() == null || req.stampPhotoUrl().isBlank()) {
            throw new BusinessException("加盖公章的纸质签收原件照片不能为空");
        }

        LogisticsPodRecord pod = new LogisticsPodRecord();
        pod.setWaybillId(wb.getWaybillId());
        pod.setWaybillNo(wb.getWaybillNo());
        pod.setOrderNo(wb.getOrderNo());
        pod.setUploaderId(operatorId);
        pod.setSignerName(req.signerName());
        pod.setSignerPhone(req.signerPhone());
        pod.setSignedTime(req.signedTime() != null ? req.signedTime() : LocalDateTime.now());
        pod.setStampPhotoUrl(req.stampPhotoUrl());
        String cargoPhotosJson = "[]";
        if (req.cargoPhotos() != null && !req.cargoPhotos().isEmpty()) {
            try {
                cargoPhotosJson = JSON.writeValueAsString(req.cargoPhotos());
            } catch (Exception ignored) {}
        }
        pod.setCargoPhotosJson(cargoPhotosJson);
        pod.setGeoLng(req.geoLng() != null ? req.geoLng() : wb.getReceiverLng());
        pod.setGeoLat(req.geoLat() != null ? req.geoLat() : wb.getReceiverLat());
        pod.setSignRemark(req.signRemark());
        pod.setAuditStatus("pending");

        if (podRecordMapper != null) {
            podRecordMapper.insert(pod);
        }

        // 同步运单快照
        wb.setPodSignedBy(req.signerName());
        wb.setPodSignedPhone(req.signerPhone());
        wb.setPodSignedTime(pod.getSignedTime());
        wb.setPodStampPhoto(req.stampPhotoUrl());
        wb.setPodCargoPhotos(cargoPhotosJson);
        wb.setPodAuditStatus("pending");
        waybillMapper.updateById(wb);

        insertTrace(wb.getWaybillId(), LocalDateTime.now(), "现场收货人已提交 e-POD 电子回单（公章及实物照片已上传），等待商户/平台验签",
                wb.getStatus(), false, "buyer_pod", pod.getGeoLng(), pod.getGeoLat(), "现场收货点", "client");

        auditService.record(operatorId, "LOGISTICS_POD_UPLOAD",
                "提交现场e-POD回执 运单=" + wb.getWaybillNo() + " 签收人=" + req.signerName(), httpReq);
        return pod;
    }

    /**
     * 商户 / 平台管理人员在线验签 e-POD（审核通过流转终态，驳回要求重新拍照）。
     */
    @Transactional
    public LogisticsWaybill auditPod(Long waybillId, PodAuditRequest req, Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        if (waybillId == null) throw new BusinessException("运单ID不能为空");
        if (req == null || req.action() == null || req.action().isBlank()) {
            throw new BusinessException("验签动作(action)不能为空");
        }
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        assertOrderBelongsToOperator(wb.getOrderNo(), operatorId, "验签e-POD");

        if (!"pending".equals(wb.getPodAuditStatus())) {
            throw new BusinessException("当前运单未处于待验签状态(当前状态: " + wb.getPodAuditStatus() + ")");
        }

        boolean approved = "approve".equalsIgnoreCase(req.action());
        String newStatus = approved ? "approved" : "rejected";
        LocalDateTime now = LocalDateTime.now();

        wb.setPodAuditStatus(newStatus);
        wb.setPodAuditNote(req.auditNote());
        wb.setPodAuditedBy(operatorId);
        wb.setPodAuditedAt(now);

        if (podRecordMapper != null) {
            List<LogisticsPodRecord> records = podRecordMapper.selectList(
                    Wrappers.<LogisticsPodRecord>lambdaQuery()
                            .eq(LogisticsPodRecord::getWaybillId, waybillId)
                            .orderByDesc(LogisticsPodRecord::getPodId));
            if (!records.isEmpty()) {
                LogisticsPodRecord r = records.get(0);
                r.setAuditStatus(newStatus);
                r.setAuditorId(operatorId);
                r.setAuditNote(req.auditNote());
                r.setAuditTime(now);
                podRecordMapper.updateById(r);
            }
        }

        if (approved) {
            wb.setStatus(LogisticsStateMachine.DELIVERED);
            wb.setDeliveredTime(now);
            if (wb.getReceiverLng() != null) {
                wb.setCurrentLng(wb.getReceiverLng());
                wb.setCurrentLat(wb.getReceiverLat());
                wb.setCurrentLocation("客户现场已完成验签签收");
            }
            insertTrace(wb.getWaybillId(), now, "e-POD 验签合格（买方单位公章印鉴核实通过），货物确认完好签收",
                    LogisticsStateMachine.DELIVERED, false, "epod_approved", wb.getCurrentLng(), wb.getCurrentLat(), "客户现场", "client");
            completeOrderIfShipped(wb.getOrderNo());
        } else {
            insertTrace(wb.getWaybillId(), now, "e-POD 验签未通过: " + (req.auditNote() != null ? req.auditNote() : "公章印鉴不清晰或缺少关键外观照") + "，请重新拍摄补传",
                    wb.getStatus(), true, "epod_rejected", wb.getCurrentLng(), wb.getCurrentLat(), "客户现场", "client");
        }

        waybillMapper.updateById(wb);
        auditService.record(operatorId, "LOGISTICS_POD_AUDIT",
                "审核e-POD回执 运单=" + wb.getWaybillNo() + " 结论=" + newStatus + " 批注=" + req.auditNote(), httpReq);
        return wb;
    }

    /**
     * 运单签收后联动订单完成（已发货→已完成，原子防并发；仅当该订单名下所有包裹全部签收且无欠发时闭环）。
     */
    private void completeOrderIfShipped(String orderNo) {
        try {
            List<LogisticsWaybill> wbs = waybillMapper.selectList(Wrappers.<LogisticsWaybill>lambdaQuery()
                    .eq(LogisticsWaybill::getOrderNo, orderNo));
            if (wbs.isEmpty()) return;

            boolean allDelivered = wbs.stream().allMatch(w -> LogisticsStateMachine.DELIVERED.equals(w.getStatus()));
            if (!allDelivered) {
                // 多包裹场景：还有包裹在途，严禁提前关闭交易
                return;
            }

            if (orderItemMapper != null) {
                List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderNo, orderNo));
                if (!items.isEmpty()) {
                    boolean allShipped = items.stream().allMatch(i ->
                            i.getShippedQuantity() != null && i.getShippedQuantity() >= (i.getQuantity() != null ? i.getQuantity() : 0));
                    if (!allShipped) {
                        return; // 尚有商品未发货
                    }
                }
            }

            if (orderMapper.markCompletedAtomic(orderNo) > 0) {
                Order o = orderMapper.selectById(orderNo);
                if (o != null) {
                    messageService.send(o.getUserId(), "order", "订单已完成",
                            "您的订单 " + orderNo + " 名下所有包裹均已成功签收，交易完成。", orderNo);
                }
            }
        } catch (Exception e) {
            // 联动失败不影响签收主流程，由每日自动确认兜底
        }
    }

    /**
     * 轨迹同步：导入服务商节点列表（mock/api 共用入口）。
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
            LogisticsTraceParser.ParsedNode parsed = resolveParsedNode(node, description);

            boolean exists = traceMapper.selectCount(new LambdaQueryWrapper<LogisticsTrace>()
                    .eq(LogisticsTrace::getWaybillId, waybillId)
                    .eq(LogisticsTrace::getTraceTime, traceTime)
                    .eq(LogisticsTrace::getDescription, description)) > 0;
            if (exists) continue;

            GeoCoordinateRegistry.GeoPoint pt = GeoCoordinateRegistry.lookup(location + " " + description, null);
            insertTrace(waybillId, traceTime, (location.isBlank() ? "" : location + " ") + description,
                    parsed.status(), parsed.abnormal(), "api",
                    pt.lngBd(), pt.latBd(), pt.name(), pt.hubType());
            imported++;

            if (LogisticsStateMachine.canTransit(wb.getStatus(), parsed.status())
                    && !wb.getStatus().equals(parsed.status())) {
                wb.setStatus(parsed.status());
                wb.setCurrentLng(pt.lngBd());
                wb.setCurrentLat(pt.latBd());
                wb.setCurrentLocation(pt.name());
                if (LogisticsStateMachine.DELIVERED.equals(parsed.status())) {
                    wb.setDeliveredTime(traceTime);
                    if (wb.getReceiverLng() != null) {
                        wb.setCurrentLng(wb.getReceiverLng());
                        wb.setCurrentLat(wb.getReceiverLat());
                        wb.setCurrentLocation("客户已签收");
                    }
                    completeOrderIfShipped(wb.getOrderNo());
                }
                waybillMapper.updateById(wb);
            }

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

    /**
     * 轨迹节点状态解析（out/logistics-tracking-plan.md P2/P3 任务 3）：
     * 节点携带显式 status（tracking 链路经 TrackingStatusMapper 七态收敛下传）时，
     * 合法平台态优先于描述关键词推导；未携带/非法时回退既有 LogisticsTraceParser 语义，
     * 人工同步路径（/logistics/trace/sync、transit）行为不变。
     * 显式异常/退回态且描述未命中告警类型时按 delay 兜底，保证异常告警链路不断；
     * 显式正常态但描述命中异常关键词（如「滞留」）仍保留告警。
     */
    private LogisticsTraceParser.ParsedNode resolveParsedNode(Map<String, Object> node, String description) {
        LogisticsTraceParser.ParsedNode byDescription = traceParser.parse(description);
        Object explicit = node == null ? null : node.get("status");
        if (explicit == null) return byDescription;
        String status = String.valueOf(explicit).trim();
        if (status.isEmpty() || !LogisticsStateMachine.isValid(status)
                || status.equals(byDescription.status())) {
            return byDescription;
        }
        boolean stateAbnormal = LogisticsStateMachine.EXCEPTION.equals(status)
                || LogisticsStateMachine.RETURNED.equals(status)
                || LogisticsStateMachine.LOST.equals(status);
        if (!stateAbnormal) {
            return new LogisticsTraceParser.ParsedNode(status, byDescription.abnormal(), byDescription.alertType());
        }
        String alertType = byDescription.alertType() != null ? byDescription.alertType() : "delay";
        return new LogisticsTraceParser.ParsedNode(status, true, alertType);
    }

    /**
     * 订单全景多运单聚合查询（买家端/商户端/管理端）：
     * 返回订单多包裹、SKU 明细拆分、完整轨迹及地图 GIS 矢量动态航线数据。
     */
    public OrderWaybillAggregation orderWaybills(String orderNo, Long operatorUserId, boolean isAdmin) {
        Order order = orderMapper.selectById(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        assertOrderLogisticsVisible(order, operatorUserId, isAdmin);

        List<OrderItem> orderItems = orderItemMapper != null
                ? orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo))
                : List.of();

        List<LogisticsWaybill> waybills = waybillMapper.selectList(Wrappers.<LogisticsWaybill>lambdaQuery()
                .eq(LogisticsWaybill::getOrderNo, orderNo)
                .orderByAsc(LogisticsWaybill::getPackageIndex)
                .orderByAsc(LogisticsWaybill::getWaybillId));

        List<WaybillDetailVO> detailVOList = new ArrayList<>();
        for (LogisticsWaybill wb : waybills) {
            LogisticsProvider provider = wb.getProviderId() != null ? providerMapper.selectById(wb.getProviderId()) : null;
            String providerName = provider != null ? provider.getProviderName() : "自营专线快运";
            String providerCode = provider != null ? provider.getProviderCode() : "EXPRESS";

            List<LogisticsWaybillItem> items = waybillItemMapper != null
                    ? waybillItemMapper.selectList(Wrappers.<LogisticsWaybillItem>lambdaQuery().eq(LogisticsWaybillItem::getWaybillId, wb.getWaybillId()))
                    : List.of();

            List<LogisticsDocument> coaDocs = documentMapper != null
                    ? documentMapper.selectList(Wrappers.<LogisticsDocument>lambdaQuery().eq(LogisticsDocument::getWaybillId, wb.getWaybillId()))
                    : List.of();

            PodInfoVO podInfo = null;
            if (wb.getPodSignedBy() != null || wb.getPodStampPhoto() != null) {
                List<String> cargoPhotos = new ArrayList<>();
                if (wb.getPodCargoPhotos() != null && !wb.getPodCargoPhotos().isBlank()) {
                    try {
                        cargoPhotos = JSON.readValue(wb.getPodCargoPhotos(), new TypeReference<List<String>>() {});
                    } catch (Exception ignored) {}
                }
                podInfo = new PodInfoVO(
                        null,
                        wb.getPodSignedBy(),
                        wb.getPodSignedPhone(),
                        wb.getPodSignedTime(),
                        wb.getPodStampPhoto(),
                        cargoPhotos,
                        wb.getReceiverLng(),
                        wb.getReceiverLat(),
                        wb.getPodAuditNote(),
                        wb.getPodAuditStatus() != null ? wb.getPodAuditStatus() : "none",
                        wb.getPodAuditNote(),
                        wb.getPodAuditedAt()
                );
            }

            List<LogisticsTrace> traces = traceMapper.selectList(Wrappers.<LogisticsTrace>lambdaQuery()
                    .eq(LogisticsTrace::getWaybillId, wb.getWaybillId())
                    .orderByAsc(LogisticsTrace::getTraceTime));

            MapRouteVO route = buildMapRoute(wb, traces);
            detailVOList.add(new WaybillDetailVO(wb, providerName, providerCode, items, coaDocs, podInfo, traces, route));
        }

        boolean allShipped = !orderItems.isEmpty() && orderItems.stream().allMatch(i ->
                i.getShippedQuantity() != null && i.getShippedQuantity() >= (i.getQuantity() != null ? i.getQuantity() : 0));

        String statusDesc = switch (order.getOrderStatus() != null ? order.getOrderStatus() : -1) {
            case 0 -> "待发货";
            case 1 -> "已发货";
            case 2 -> "已完成";
            case 3 -> "已取消";
            case 4 -> "部分发货";
            case 5 -> "已关闭";
            default -> "处理中";
        };

        return new OrderWaybillAggregation(
                order.getOrderNo(),
                order.getOrderStatus(),
                statusDesc,
                waybills.size(),
                allShipped,
                orderItems,
                detailVOList
        );
    }

    /** 构建 GIS 矢量动态航线数据 */
    private MapRouteVO buildMapRoute(LogisticsWaybill wb, List<LogisticsTrace> traces) {
        GeoCoordinateRegistry.GeoPoint origin;
        if (wb.getSenderLng() != null && wb.getSenderLat() != null) {
            origin = new GeoCoordinateRegistry.GeoPoint(
                    wb.getSenderLng().doubleValue(), wb.getSenderLat().doubleValue(),
                    wb.getSenderAddress() != null ? wb.getSenderAddress() : "发货仓", "warehouse");
        } else {
            origin = GeoCoordinateRegistry.lookup(wb.getSenderAddress(), null);
        }

        GeoCoordinateRegistry.GeoPoint destination;
        if (wb.getReceiverLng() != null && wb.getReceiverLat() != null) {
            destination = new GeoCoordinateRegistry.GeoPoint(
                    wb.getReceiverLng().doubleValue(), wb.getReceiverLat().doubleValue(),
                    wb.getReceiverAddress() != null ? wb.getReceiverAddress() : "收货地", "client");
        } else {
            destination = GeoCoordinateRegistry.lookup(wb.getReceiverAddress(), null);
        }

        double totalDist = GeoCoordinateRegistry.distanceKm(origin, destination);

        List<MapNodeVO> nodes = new ArrayList<>();
        // 初始发货节点
        nodes.add(new MapNodeVO(
                origin.lng(), origin.lat(), origin.name(), origin.hubType(),
                wb.getShipTime() != null ? wb.getShipTime() : LocalDateTime.now(),
                LogisticsStateMachine.PICKED_UP, 0, "包裹备货并完成出库揽收"
        ));

        for (LogisticsTrace t : traces) {
            double lng = t.getLongitude() != null ? t.getLongitude().doubleValue() : origin.lng();
            double lat = t.getLatitude() != null ? t.getLatitude().doubleValue() : origin.lat();
            String name = t.getCity() != null && !t.getCity().isBlank() ? t.getCity() : t.getLocation();
            if (name == null || name.isBlank()) {
                GeoCoordinateRegistry.GeoPoint match = GeoCoordinateRegistry.lookup(t.getDescription(), null);
                lng = match.lng();
                lat = match.lat();
                name = match.name();
            }
            nodes.add(new MapNodeVO(
                    lng, lat, name, t.getHubType() != null ? t.getHubType() : "hub",
                    t.getTraceTime(), t.getStatus(), t.getIsAbnormal(), t.getDescription()
            ));
        }

        boolean isDelivered = LogisticsStateMachine.DELIVERED.equals(wb.getStatus());
        if (isDelivered) {
            nodes.add(new MapNodeVO(
                    destination.lng(), destination.lat(), destination.name(), destination.hubType(),
                    wb.getDeliveredTime() != null ? wb.getDeliveredTime() : LocalDateTime.now(),
                    LogisticsStateMachine.DELIVERED, 0, "客户已签收完成"
            ));
        }

        double fraction;
        double truckLng;
        double truckLat;
        String locationDesc;

        if (isDelivered) {
            fraction = 1.0;
            truckLng = destination.lng();
            truckLat = destination.lat();
            locationDesc = "已送达客户目的地";
        } else if (LogisticsStateMachine.PICKED_UP.equals(wb.getStatus())) {
            fraction = 0.05;
            truckLng = origin.lng();
            truckLat = origin.lat();
            locationDesc = origin.name();
        } else {
            int traceCount = Math.max(1, traces.size());
            fraction = Math.min(0.85, 0.2 + (traceCount * 0.15));
            if (wb.getCurrentLng() != null && wb.getCurrentLat() != null) {
                truckLng = wb.getCurrentLng().doubleValue();
                truckLat = wb.getCurrentLat().doubleValue();
                locationDesc = wb.getCurrentLocation() != null ? wb.getCurrentLocation() : "在途运输中";
            } else {
                GeoCoordinateRegistry.GeoPoint interp = GeoCoordinateRegistry.interpolate(origin, destination, fraction, "干线在途", "transfer");
                truckLng = interp.lng();
                truckLat = interp.lat();
                locationDesc = interp.name();
            }
        }

        GeoCoordinateRegistry.GeoPoint currentTruckPt = new GeoCoordinateRegistry.GeoPoint(truckLng, truckLat, locationDesc, "truck");
        double heading = GeoCoordinateRegistry.heading(currentTruckPt, destination);

        TruckPositionVO truckPos = new TruckPositionVO(
                truckLng, truckLat, heading, wb.getStatus(), locationDesc, fraction
        );

        return new MapRouteVO(origin, destination, nodes, truckPos, totalDist);
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
        Order order = orderMapper.selectById(wb.getOrderNo());
        if (order != null && order.getUserId() != null) {
            messageService.send(order.getUserId(), "logistics",
                    "物流异常告警", "运单 " + wb.getWaybillNo() + " 出现异常：" + content, wb.getWaybillNo());
        }
        return true;
    }

    private void insertTrace(Long waybillId, LocalDateTime traceTime, String description,
                             String status, boolean abnormal, String source) {
        insertTrace(waybillId, traceTime, description, status, abnormal, source, null, null, null, null);
    }

    private void insertTrace(Long waybillId, LocalDateTime traceTime, String description,
                             String status, boolean abnormal, String source,
                             BigDecimal lng, BigDecimal lat, String city, String hubType) {
        LogisticsTrace t = new LogisticsTrace();
        t.setWaybillId(waybillId);
        t.setTraceTime(traceTime);
        t.setLocation(city != null ? city : "");
        t.setDescription(description);
        t.setStatus(status);
        t.setIsAbnormal(abnormal ? 1 : 0);
        t.setSource(source);
        t.setLongitude(lng);
        t.setLatitude(lat);
        t.setCity(city);
        t.setHubType(hubType != null ? hubType : "hub");
        traceMapper.insert(t);
    }

    private String nextWaybillNo() {
        String day = LocalDate.now().format(WAYBILL_DAY);
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

    /** 分页查询（管理端/商家侧）：按操作者身份隔离 */
    public Page<LogisticsWaybill> page(long page, long size, String status, String orderNo, Long providerId,
                                       Long operatorUserId, boolean adminView) {
        LambdaQueryWrapper<LogisticsWaybill> qw = new LambdaQueryWrapper<LogisticsWaybill>()
                .orderByDesc(LogisticsWaybill::getWaybillId);
        if (status != null && !status.isBlank()) qw.eq(LogisticsWaybill::getStatus, status);
        if (orderNo != null && !orderNo.isBlank()) qw.eq(LogisticsWaybill::getOrderNo, orderNo);
        if (providerId != null) qw.eq(LogisticsWaybill::getProviderId, providerId);
        if (!adminView) {
            if (operatorUserId == null) return new Page<>(page, size, 0);
            List<String> scope = orderNosOfOperator(operatorUserId);
            if (scope.isEmpty()) return new Page<>(page, size, 0);
            qw.in(LogisticsWaybill::getOrderNo, scope);
        }
        return waybillMapper.selectPage(new Page<>(page, size), qw);
    }

    /** 发货/签收归属校验 */
    private void assertOrderBelongsToOperator(String orderNo, Long operatorUserId, String action) {
        if (merchantMapper == null || operatorUserId == null) return;
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUserId, operatorUserId));
        if (m == null) return;
        if (orderMapper.merchantItems(m.getMerId(), List.of(orderNo)).isEmpty()) {
            throw new BusinessException("该订单不含您的商品，无权" + action);
        }
    }

    /** 操作者可见的订单号集合 */
    private List<String> orderNosOfOperator(Long operatorUserId) {
        if (merchantMapper != null) {
            Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                    .eq(Merchant::getUserId, operatorUserId));
            if (m != null) return orderMapper.merchantOrderNos(m.getMerId());
        }
        return orderMapper.buyerOrderNos(operatorUserId);
    }

    /**
     * 订单物流可见性归属校验（orderWaybills / traces 共用三分支）：
     * 平台运营放行；买家属主放行；订单含本店商品的商户放行；其余一律拒绝（fail-closed）。
     */
    private void assertOrderLogisticsVisible(Order order, Long operatorUserId, boolean isAdmin) {
        if (isAdmin) return;
        boolean isOwner = operatorUserId != null && operatorUserId.equals(order.getUserId());
        boolean isOrderMerchant = false;
        if (merchantMapper != null && operatorUserId != null) {
            Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, operatorUserId));
            if (m != null && !orderMapper.merchantItems(m.getMerId(), List.of(order.getOrderNo())).isEmpty()) {
                isOrderMerchant = true;
            }
        }
        if (!isOwner && !isOrderMerchant) {
            throw new BusinessException("无权查看该订单物流信息");
        }
    }

    /** 运单轨迹查询（买家本人/订单归属商户/平台运营）：先经运单→订单归属校验，未通过一律拒绝 */
    public List<LogisticsTrace> traces(Long waybillId, Long operatorUserId, boolean isAdmin) {
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        Order order = orderMapper.selectById(wb.getOrderNo());
        if (order == null) throw new BusinessException("订单不存在");
        assertOrderLogisticsVisible(order, operatorUserId, isAdmin);
        return traceMapper.selectList(new LambdaQueryWrapper<LogisticsTrace>()
                .eq(LogisticsTrace::getWaybillId, waybillId)
                .orderByAsc(LogisticsTrace::getTraceTime));
    }

    /** 运单轨迹查询（路径段可传运单号或运单 ID，先解析定位再走既有归属校验） */
    public List<LogisticsTrace> traces(String waybillIdOrNo, Long operatorUserId, boolean isAdmin) {
        return traces(resolveWaybillId(waybillIdOrNo), operatorUserId, isAdmin);
    }

    /**
     * 路径段运单定位：纯数字视为运单主键；否则按运单号（nextWaybillNo 生成的 LL 前缀）等值查询。
     * 数字越界（非本库主键）与未知运单号统一按“运单不存在”拒绝，不落 500。
     */
    public Long resolveWaybillId(String waybillIdOrNo) {
        String s = waybillIdOrNo == null ? "" : waybillIdOrNo.trim();
        if (!s.isEmpty() && s.chars().allMatch(Character::isDigit)) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException ignored) {
                throw new BusinessException("运单不存在");
            }
        }
        LogisticsWaybill wb = waybillMapper.selectOne(new LambdaQueryWrapper<LogisticsWaybill>()
                .eq(LogisticsWaybill::getWaybillNo, s).last("LIMIT 1"));
        if (wb == null) throw new BusinessException("运单不存在");
        return wb.getWaybillId();
    }

    public List<LogisticsProvider> enabledProviders() {
        return providerMapper.selectList(new LambdaQueryWrapper<LogisticsProvider>()
                .eq(LogisticsProvider::getStatus, 1)
                .orderByAsc(LogisticsProvider::getSort)
                .orderByAsc(LogisticsProvider::getProviderId));
    }

    public List<LogisticsTemplate> enabledTemplates(Long providerId) {
        LambdaQueryWrapper<LogisticsTemplate> qw = new LambdaQueryWrapper<LogisticsTemplate>()
                .eq(LogisticsTemplate::getStatus, 1);
        if (providerId != null) qw.eq(LogisticsTemplate::getProviderId, providerId);
        qw.orderByAsc(LogisticsTemplate::getTemplateId);
        return templateMapper.selectList(qw);
    }
}
