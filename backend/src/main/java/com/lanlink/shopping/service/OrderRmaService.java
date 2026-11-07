package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.RefundEvidenceValidator;
import com.lanlink.shopping.common.ValidationRules;
import com.lanlink.shopping.dto.rma.BuyerShipBackDTO;
import com.lanlink.shopping.dto.rma.MerchantInspectDTO;
import com.lanlink.shopping.dto.rma.MerchantRmaApproveDTO;
import com.lanlink.shopping.dto.rma.RmaApplyDTO;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RMA 售后退货退款与逆向物流全链路业务闭环服务 (P2 阶段)。
 */
@Service
public class OrderRmaService {

    private static final Logger log = LoggerFactory.getLogger(OrderRmaService.class);

    private final OrderRmaMapper rmaMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsTraceMapper traceMapper;
    private final PaymentService paymentService;
    private final MessageService messageService;

    public OrderRmaService(OrderRmaMapper rmaMapper,
                           OrderMapper orderMapper,
                           OrderItemMapper orderItemMapper,
                           ProductMapper productMapper,
                           MerchantMapper merchantMapper,
                           LogisticsWaybillMapper waybillMapper,
                           LogisticsTraceMapper traceMapper,
                           PaymentService paymentService,
                           MessageService messageService) {
        this.rmaMapper = rmaMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.waybillMapper = waybillMapper;
        this.traceMapper = traceMapper;
        this.paymentService = paymentService;
        this.messageService = messageService;
    }

    /** 买家提交退货退款 / 仅退款 RMA 售后申请 */
    @Transactional
    public OrderRma applyRma(Long userId, RmaApplyDTO dto) {
        if (dto == null || dto.getOrderNo() == null || dto.getOrderNo().isBlank()) {
            throw new BusinessException("订单号不能为空");
        }
        Order o = orderMapper.selectById(dto.getOrderNo());
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (o.getPayStatus() == null || o.getPayStatus() != 1) {
            throw new BusinessException("仅已支付订单可申请售后退款");
        }
        if (o.getOrderStatus() != null && (o.getOrderStatus() == 3 || o.getOrderStatus() == 5)) {
            throw new BusinessException("该订单已取消或已关闭，无法申请售后");
        }
        if (dto.getApplyReason() == null || dto.getApplyReason().isBlank()) {
            throw new BusinessException("请填写退款申请原因");
        }
        // 证据 ≥1 必填 + 权威校验归一（type 白名单/URL 前缀/扩展名一致/归属当前用户/File.exists）
        String evidences = RefundEvidenceValidator.normalize(dto.getProofImages(), userId, ChatMediaService.chatUploadRoot());
        // 原因分类枚举校验（CAS 之前 fail-fast，避免空耗一次状态流转）
        String reasonCategory = normalizeReasonCategory(dto.getReasonCategory());

        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        BigDecimal remain = o.getTotalAmount().subtract(already);
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("该订单已无可退金额");
        }
        BigDecimal applyAmt = dto.getApplyAmount() != null && dto.getApplyAmount().compareTo(BigDecimal.ZERO) > 0
                ? dto.getApplyAmount().min(remain) : remain;

        // 原子 CAS 锁定订单退款状态（t_order.refund_evidences 为链A独占列，链B证据权威在本 RMA 行，传 null 不双写）
        int hit = orderMapper.applyRefundAtomic(o.getOrderNo(), dto.getApplyReason().trim(), null);
        if (hit == 0) {
            throw new BusinessException("退款申请已提交或订单状态不允许申请，请勿重复操作");
        }

        // 定位所属商户
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderNo, o.getOrderNo()));
        Long merId = null;
        if (!items.isEmpty()) {
            Product p = productMapper.selectById(items.get(0).getProdId());
            if (p != null) merId = p.getMerId();
        }
        if (merId == null) merId = 1L;

        OrderRma rma = new OrderRma();
        rma.setRmaNo(genRmaNo());
        rma.setOrderNo(o.getOrderNo());
        rma.setUserId(userId);
        rma.setMerId(merId);
        rma.setRmaType("refund_only".equalsIgnoreCase(dto.getRmaType()) ? "refund_only" : "return_goods");
        rma.setStatus("buyer_applied");
        rma.setApplyAmount(applyAmt);
        rma.setActualRefundAmount(BigDecimal.ZERO);
        rma.setReasonCategory(reasonCategory);
        rma.setApplyReason(dto.getApplyReason().trim());
        rma.setProofImages(evidences);
        rma.setIsRestocked(0);
        rma.setCreateTime(LocalDateTime.now());
        rma.setUpdateTime(LocalDateTime.now());
        rmaMapper.insert(rma);

        // 回写订单高频字段
        o.setCurrentRmaNo(rma.getRmaNo());
        o.setRefundType(rma.getRmaType());
        orderMapper.updateById(o);

        messageService.send(userId, "order", "售后申请已提交",
                "订单 " + o.getOrderNo() + " 的 " + ("refund_only".equals(rma.getRmaType()) ? "仅退款" : "退货退款")
                        + " 申请已提交，等待商户审核处置。", o.getOrderNo());
        return rma;
    }

    /** RMA 原因分类白名单校验（ValidationRules.ALLOWED_RMA_REASON_CATEGORIES 同源） */
    private String normalizeReasonCategory(String raw) {
        String cat = raw == null || raw.isBlank() ? "other" : raw.trim();
        if (!ValidationRules.ALLOWED_RMA_REASON_CATEGORIES.contains(cat)) {
            throw new BusinessException("不支持的原因分类");
        }
        return cat;
    }

    /** 商户初审 RMA 申请（同意退货并下发仓库地址，或仅退款同意直接出款） */
    @Transactional
    public void merchantApproveRma(Long userId, String rmaNo, MerchantRmaApproveDTO dto) {
        Merchant m = mustMerchant(userId);
        OrderRma rma = rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
        if (rma == null || !rma.getMerId().equals(m.getMerId())) {
            throw new BusinessException("售后单不存在或无权操作");
        }

        String receiver = dto != null && dto.getReceiverName() != null ? dto.getReceiverName().trim() : m.getLegalPerson();
        String phone = dto != null && dto.getReceiverPhone() != null ? dto.getReceiverPhone().trim() : m.getContactPhone();
        String addr = dto != null && dto.getWarehouseAddress() != null ? dto.getWarehouseAddress().trim() : m.getBusinessAddress();
        String note = dto != null && dto.getAuditNote() != null ? dto.getAuditNote().trim() : "商户初审同意";

        int hit = rmaMapper.merchantApproveAtomic(rmaNo, receiver, phone, addr, note);
        if (hit == 0) {
            throw new BusinessException("售后单状态已变更或已被处理，请刷新后重试");
        }

        orderMapper.merchantApproveRefundAtomic(rma.getOrderNo());
        Order o = orderMapper.selectById(rma.getOrderNo());
        if (o != null) {
            o.setReturnReceiverName(receiver);
            o.setReturnReceiverPhone(phone);
            o.setReturnWarehouseAddr(addr);
            orderMapper.updateById(o);
        }

        if ("refund_only".equals(rma.getRmaType())) {
            // 仅退款：商户同意后直接触发打款出账
            RefundRequest refReq = new RefundRequest();
            refReq.setOrderNo(rma.getOrderNo());
            refReq.setAmount(rma.getApplyAmount());
            refReq.setReason(rma.getApplyReason());
            paymentService.refund(refReq, userId);
            rmaMapper.settleSuccessAtomic(rmaNo, rma.getApplyAmount(), o != null ? o.getOutRefundNo() : null, null);
            messageService.send(rma.getUserId(), "order", "仅退款初审已通过",
                    "订单 " + rma.getOrderNo() + " 仅退款申请商户已同意，退款已进入原路出款流程。", rma.getOrderNo());
        } else {
            // 退货退款：下发退货收件人与仓库地址，提示买家寄回商品
            messageService.send(rma.getUserId(), "order", "退货申请已通过，请寄回商品",
                    "订单 " + rma.getOrderNo() + " 商户已同意退货。退货地址：" + addr + "，收件人：" + receiver + " " + phone + "，请在寄出后回填运单号。",
                    rma.getOrderNo());
        }
    }

    /** 买家寄出退货包裹并填报运单号（联动生成逆向运单并在图层上打通轨迹） */
    @Transactional
    public void buyerShipBack(Long userId, String rmaNo, BuyerShipBackDTO dto) {
        if (dto == null || dto.getWaybillNo() == null || dto.getWaybillNo().isBlank()) {
            throw new BusinessException("退货运单号不能为空");
        }
        OrderRma rma = rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
        if (rma == null || !rma.getUserId().equals(userId)) {
            throw new BusinessException("售后单不存在");
        }
        if (!"merchant_approved".equals(rma.getStatus())) {
            throw new BusinessException("当前售后状态不允许填报运单号");
        }

        String carrier = dto.getCarrierName() == null || dto.getCarrierName().isBlank() ? "快递物流" : dto.getCarrierName().trim();
        String wbNo = dto.getWaybillNo().trim();

        Order o = orderMapper.selectById(rma.getOrderNo());

        // 联动创建逆向物流运单
        LogisticsWaybill rw = new LogisticsWaybill();
        rw.setWaybillNo("RW" + genOrderNo());
        rw.setOrderNo(rma.getOrderNo());
        rw.setRmaNo(rmaNo);
        rw.setWaybillDirection("reverse");
        rw.setLogisticsNo(wbNo);
        rw.setStatus("in_transit");
        rw.setPackageNo("退货包裹");
        rw.setSenderName(o != null && o.getReceiver() != null ? o.getReceiver() : "买家退货寄出");
        rw.setSenderPhone(o != null ? o.getPhone() : null);
        rw.setSenderAddress(o != null ? o.getAddress() : null);
        rw.setReceiverName(rma.getReturnReceiverName());
        rw.setReceiverPhone(rma.getReturnReceiverPhone());
        rw.setReceiverAddress(rma.getReturnWarehouseAddr());
        rw.setShipTime(LocalDateTime.now());
        rw.setCreateTime(LocalDateTime.now());
        rw.setUpdateTime(LocalDateTime.now());
        if (waybillMapper != null) {
            waybillMapper.insert(rw);
            // 写入初始轨迹
            if (traceMapper != null) {
                LogisticsTrace t = new LogisticsTrace();
                t.setWaybillId(rw.getWaybillId());
                t.setStatus("in_transit");
                t.setDescription("买家已寄出退货商品，承运商【" + carrier + "】，运单号：" + wbNo);
                t.setTraceTime(LocalDateTime.now());
                t.setCreateTime(LocalDateTime.now());
                traceMapper.insert(t);
            }
        }

        rmaMapper.shipBackAtomic(rmaNo, carrier, wbNo, rw.getWaybillId(), dto.getRemark());
        orderMapper.shipBackAtomic(rma.getOrderNo(), carrier, wbNo);

        messageService.send(rma.getMerId(), "order", "买家已寄出退货商品",
                "售后单 " + rmaNo + " 买家已寄回商品，承运商：" + carrier + "，单号：" + wbNo + "，请留意收货验货。", rma.getOrderNo());
    }

    /** 商户/仓库扫码验货质检入库（完好合格自动恢复库存并退款打款） */
    @Transactional
    public void merchantInspect(Long userId, String rmaNo, MerchantInspectDTO dto) {
        Merchant m = mustMerchant(userId);
        OrderRma rma = rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
        if (rma == null || !rma.getMerId().equals(m.getMerId())) {
            throw new BusinessException("售后单不存在或无权操作");
        }
        if (!"shipping_back".equals(rma.getStatus()) && !"received".equals(rma.getStatus())) {
            throw new BusinessException("当前售后单未处于已寄出待验货状态");
        }

        String status = dto != null && "damaged".equalsIgnoreCase(dto.getInspectStatus()) ? "damaged" : "passed";
        String note = dto != null && dto.getResult() != null ? dto.getResult().trim() : (status.equals("passed") ? "验货合格入库" : "货物破损异常");

        if ("passed".equals(status)) {
            // 1. 质检合格：标记入库
            rmaMapper.inspectAtomic(rmaNo, "inspected", userId, "passed", note, 1);
            orderMapper.updateInspectResult(rma.getOrderNo(), "passed", note);

            // 2. 物理库存与销量原子恢复
            restoreStock(rma.getOrderNo());

            // 3. 自动触发原路打款退款
            RefundRequest inspectRefReq = new RefundRequest();
            inspectRefReq.setOrderNo(rma.getOrderNo());
            inspectRefReq.setAmount(rma.getApplyAmount());
            inspectRefReq.setReason("RMA验货通过原路退款: " + rmaNo);
            paymentService.refund(inspectRefReq, userId);
            Order o = orderMapper.selectById(rma.getOrderNo());
            rmaMapper.settleSuccessAtomic(rmaNo, rma.getApplyAmount(), o != null ? o.getOutRefundNo() : null, null);

            messageService.send(rma.getUserId(), "order", "退货质检通过，已退款",
                    "售后单 " + rmaNo + " 商品验货合格已入库，退款金额 ¥" + rma.getApplyAmount() + " 已触发原路退回。", rma.getOrderNo());
        } else {
            // 货损异常：流转至 dispute 争议协商
            rmaMapper.inspectAtomic(rmaNo, "dispute", userId, "damaged", note, 0);
            orderMapper.updateInspectResult(rma.getOrderNo(), "damaged", note);

            messageService.send(rma.getUserId(), "order", "退货验货异常提醒",
                    "售后单 " + rmaNo + " 仓库验货发现异常：" + note + "，请及时联系商户协商处置。", rma.getOrderNo());
        }
    }

    /** 驳回 RMA 申请 */
    @Transactional
    public void rejectRma(Long userId, String rmaNo, String reason) {
        Merchant m = mustMerchant(userId);
        OrderRma rma = rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
        if (rma == null || !rma.getMerId().equals(m.getMerId())) {
            throw new BusinessException("售后单不存在或无权操作");
        }
        String why = reason == null || reason.isBlank() ? "商户初审未通过" : reason.trim();
        rmaMapper.rejectAtomic(rmaNo, why);
        orderMapper.rejectRefundAtomic(rma.getOrderNo(), why);
        messageService.send(rma.getUserId(), "order", "售后申请被驳回",
                "售后单 " + rmaNo + " 已被商户驳回：" + why, rma.getOrderNo());
    }

    /** 查询 RMA 详情 */
    public OrderRma getRmaDetail(String rmaNo) {
        return rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
    }

    /** 查询订单关联的最新 RMA */
    public OrderRma getByOrderNo(String orderNo) {
        return rmaMapper.findLatestByOrderNo(orderNo);
    }

    private void restoreStock(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem it : items) {
            productMapper.restoreStockAtomic(it.getProdId(), it.getQuantity());
        }
    }

    private Merchant mustMerchant(Long userId) {
        if (merchantMapper == null) throw new BusinessException("商户服务不可用");
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) throw new BusinessException("仅入驻商户可操作");
        return m;
    }

    private String genRmaNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "RMA" + ts + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private String genOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return ts + ThreadLocalRandom.current().nextInt(100000, 999999);
    }
}
