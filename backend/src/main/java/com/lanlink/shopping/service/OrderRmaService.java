package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.RefundEvidenceValidator;
import com.lanlink.shopping.common.ValidationRules;
import com.lanlink.shopping.dto.rma.BuyerShipBackDTO;
import com.lanlink.shopping.dto.rma.MerchantInspectDTO;
import com.lanlink.shopping.dto.rma.MerchantRmaApproveDTO;
import com.lanlink.shopping.dto.rma.RmaApplyDTO;
import com.lanlink.shopping.dto.refund.RefundReviewRequest;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.integration.pricing.OrderAmounts;
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
 *
 * <p><b>P0-C 退款出款收口（本类职责边界）</b>：本类只推进售后状态与审批留痕，<b>绝不再直接调用
 * 渠道出款</b>。出款唯一入口是平台运营对「待平台复核」售后单/订单的显式复核动作：
 * <pre>
 *  refund_only:        buyer_applied → merchant_approved → [platform_pending] → platform_approved → success
 *  return_goods:       buyer_applied → merchant_approved → shipping_back → received/inspected
 *                                      → [platform_pending] → platform_approved → success
 *  平台驳回：           platform_pending → platform_rejected（不走钱）
 *  申诉重开：           platform_rejected → [platform_pending]（需存在在途售后工单，见 reopenForAppeal）
 *  商户驳回：           buyer_applied → rejected（不走钱）
 * </pre>
 * 其中 {@code [platform_pending]} 为本类新增状态常量；{@code platform_approved} 只是审批动作的
 * 中间态，正常会在同一事务内落到 {@code success}。每一次商户/平台审批动作都写 t_refund_approval
 * 一行（append-only），并落 t_audit_log。
 */
@Service
public class OrderRmaService {

    private static final Logger log = LoggerFactory.getLogger(OrderRmaService.class);

    /** P0-C 新增售后状态：商户已同意/验货通过，等待平台运营复核（此时还不允许出款）。 */
    public static final String RMA_STATUS_PLATFORM_PENDING = "platform_pending";
    /** P0-C 新增售后状态：平台复核已通过、出款处理中（事务内中间态，异常回滚）。 */
    public static final String RMA_STATUS_PLATFORM_APPROVED = "platform_approved";
    /** P0-C 新增售后状态：平台驳回复核，售后单关闭但可走工单申诉重开。 */
    public static final String RMA_STATUS_PLATFORM_REJECTED = "platform_rejected";

    /** 订单退款状态：允许提交平台复核的集合（与 OrderMapper#markRefundProcessingAtomic 出款白名单一致）。 */
    private static final java.util.Set<String> PLATFORM_REVIEWABLE_ORDER_STATUS =
            java.util.Set.of("merchant_approved", "shipping_back", "received", "partial");

    private final OrderRmaMapper rmaMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsTraceMapper traceMapper;
    private final PaymentService paymentService;
    private final MessageService messageService;

    /** P0-C 审批留痕（必要依赖，生产必注入；缺失时 fail-closed 禁止走审批/出款流程）。 */
    private volatile RefundApprovalMapper refundApprovalMapper;
    /** P0-C 审计（可选依赖，缺失时只跳过 t_audit_log，不阻断状态流转）。 */
    private volatile AuditService auditService;
    /** 申诉工单（可选依赖，仅 reopenForAppeal 用；缺失时禁止重开，避免无申诉凭据放行）。 */
    private volatile SupportTicketMapper supportTicketMapper;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRefundApprovalMapper(RefundApprovalMapper refundApprovalMapper) {
        this.refundApprovalMapper = refundApprovalMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSupportTicketMapper(SupportTicketMapper supportTicketMapper) {
        this.supportTicketMapper = supportTicketMapper;
    }

    /**
     * 库存幂等回补（P1-3）：可选注入（单测缺省时验货通过路径 fail-fast，避免退化为非幂等直写；
     * 生产由 Spring 注入）。统一走 {@link StockService}，t_stock_flow 唯一键保证一次验货只补一次。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StockService stockService;

    public void setStockService(StockService stockService) {
        this.stockService = stockService;
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
        BigDecimal remain = refundableTotal(o).subtract(already);
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

        // P0-C 双人留痕第 1 段：商户初审动作 append 一行（不删不改历史）
        recordApproval(rmaNo, rma.getOrderNo(), "MERCHANT", "APPROVE", userId, "MERCHANT",
                rma.getApplyAmount(), note, 0);
        audit(userId, "REFUND_MERCHANT_APPROVE",
                "售后单 " + rmaNo + " 商户初审通过，订单 " + rma.getOrderNo()
                        + "，申请金额 ¥" + (rma.getApplyAmount() == null ? "-" : rma.getApplyAmount().toPlainString()));

        orderMapper.merchantApproveRefundAtomic(rma.getOrderNo());
        Order o = orderMapper.selectById(rma.getOrderNo());
        if (o != null) {
            o.setReturnReceiverName(receiver);
            o.setReturnReceiverPhone(phone);
            o.setReturnWarehouseAddr(addr);
            orderMapper.updateById(o);
        }

        if ("refund_only".equals(rma.getRmaType())) {
            // P0-C：仅退款商户同意后**不再直接出款**，转为「待平台复核」；
            // 出款只能由平台运营通过 /admin/refund/review/rma/{rmaNo}/approve 显式触发。
            int moved = moveRmaStatus(rmaNo, "merchant_approved", RMA_STATUS_PLATFORM_PENDING);
            if (moved == 0) {
                throw new BusinessException("售后单状态已变化，请刷新后重试");
            }
            messageService.send(rma.getUserId(), "order", "仅退款初审已通过，待平台复核",
                    "订单 " + rma.getOrderNo() + " 仅退款申请商户已同意，已进入平台复核，复核通过后原路退款。", rma.getOrderNo());
        } else {
            // 退货退款：下发退货收件人与仓库地址，提示买家寄回商品（验货通过后同样进入平台复核）
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

            // 2. 物理库存与销量原子恢复（P1-3 幂等：bizNo=售后单号，同一验货事件只回补一次）
            if (stockService == null) {
                throw new IllegalStateException("StockService 未注入，拒绝执行库存回补（避免退化为非幂等直写）");
            }
            stockService.restoreByOrder(StockService.BIZ_RMA_RESTOCK, rmaNo, rma.getOrderNo(),
                    userId, "RMA 验货合格入库");

            // 3. P0-C：验货通过**不再自动出款**，进入「待平台复核」；出款只由平台运营显式动作触发
            int moved = moveRmaStatus(rmaNo, "inspected", RMA_STATUS_PLATFORM_PENDING);
            if (moved == 0) {
                throw new BusinessException("售后单状态已变化，请刷新后重试");
            }
            audit(userId, "REFUND_INSPECT_PASSED",
                    "售后单 " + rmaNo + " 验货合格入库，订单 " + rma.getOrderNo() + " 进入待平台复核");
            messageService.send(rma.getUserId(), "order", "退货质检通过，待平台复核",
                    "售后单 " + rmaNo + " 商品验货合格已入库，已进入平台复核，复核通过后 ¥"
                            + rma.getApplyAmount() + " 将原路退回。", rma.getOrderNo());
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
        int hit = rmaMapper.rejectAtomic(rmaNo, why);
        if (hit == 0) {
            throw new BusinessException("售后单状态已变更或已被处理，请刷新后重试");
        }
        // P0-C 双人留痕：商户驳回也 append 一行（驳回不影响后续平台/申诉链路）
        recordApproval(rmaNo, rma.getOrderNo(), "MERCHANT", "REJECT", userId, "MERCHANT", null, why, 0);
        audit(userId, "REFUND_MERCHANT_REJECT", "售后单 " + rmaNo + " 商户驳回：" + why);
        orderMapper.rejectRefundAtomic(rma.getOrderNo(), why);
        messageService.send(rma.getUserId(), "order", "售后申请被驳回",
                "售后单 " + rmaNo + " 已被商户驳回：" + why, rma.getOrderNo());
    }

    // ==================== P0-C 平台退款复核（出款唯一入口） ====================

    /**
     * 平台复核通过 · RMA 售后单：唯一允许触发渠道出款的服务端路径之一。
     *
     * <p>前置：售后单必须处于 {@code platform_pending}（商户已同意 / 验货已通过）。本方法在同一事务内
     * 依次完成：状态 CAS（pending→approved，防并发双复核）→ 写 PLATFORM/APPROVE 审批行 →
     * 调 {@link PaymentService#refund} 出款（内部再校验出款状态矩阵并原子消费审批行）→
     * 售后单结案。任何一步失败整体回滚，不会出现“已出款但售后单未结案”的半态。
     *
     * <p>幂等：售后单已 success 时直接返回既有结果，不再次出款；并发第二路 CAS 失败后同样按已结案返回。
     */
    @Transactional
    public OrderRma platformApproveRma(Long operatorId, String rmaNo, RefundReviewRequest req) {
        if (operatorId == null) throw new BusinessException(403, "请先登录平台运营账号");
        OrderRma rma = loadRma(rmaNo);
        if (rma == null) throw new BusinessException(404, "售后单不存在");
        if ("success".equals(rma.getStatus())) return rma; // 幂等：已结案不重复出款
        if (!RMA_STATUS_PLATFORM_PENDING.equals(rma.getStatus())) {
            throw new BusinessException(409,
                    "当前售后状态不可平台复核（" + rma.getStatus() + "）：须先完成商户初审/验货");
        }
        Order o = orderMapper.selectById(rma.getOrderNo());
        if (o == null) throw new BusinessException(404, "订单不存在");
        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        BigDecimal remain = refundableTotal(o).subtract(already);
        BigDecimal amount = req != null && req.getAmount() != null ? req.getAmount() : rma.getApplyAmount();
        validateReviewAmount(amount, remain);
        String note = noteOf(req, "平台复核通过");

        // 状态 CAS：并发的第二个复核请求在行锁后命中 0 行，不会重复插入审批行/重复出款
        int hit = transitionRmaStatus(rmaNo, RMA_STATUS_PLATFORM_PENDING, RMA_STATUS_PLATFORM_APPROVED,
                operatorId, note, "APPROVED");
        if (hit == 0) {
            OrderRma fresh = loadRma(rmaNo);
            if (fresh != null && "success".equals(fresh.getStatus())) return fresh;
            throw new BusinessException(409, "售后单状态已变化或已被其他运营处理，请刷新后重试");
        }
        recordApproval(rmaNo, rma.getOrderNo(), "PLATFORM", "APPROVE", operatorId, "ADMIN", amount, note, 0);
        audit(operatorId, "REFUND_PLATFORM_APPROVE",
                "售后单 " + rmaNo + " 平台复核通过，订单 " + rma.getOrderNo() + "，出款 ¥" + amount.toPlainString());

        boolean paid = paymentService.refund(
                new RefundRequest(rma.getOrderNo(), amount, "平台复核通过原路退款: " + rmaNo), operatorId);
        if (!paid) {
            // 渠道未受理：抛异常让整个事务回滚（审批行/状态一并回滚），运营可重新复核
            throw new BusinessException("渠道退款未受理，本次复核已回滚，请稍后重新复核");
        }
        Order after = orderMapper.selectById(rma.getOrderNo());
        rmaMapper.settleSuccessAtomic(rmaNo, amount, after != null ? after.getOutRefundNo() : null, null);
        messageService.send(rma.getUserId(), "order", "平台复核通过，退款已原路退回",
                "售后单 " + rmaNo + " 平台复核通过，退款 ¥" + amount.toPlainString() + " 已原路退回。", rma.getOrderNo());
        return loadRma(rmaNo);
    }

    /** 平台驳回复核 · RMA：只改状态与留痕，不发生任何资金动作；售后单进入 platform_rejected，可走工单申诉重开。 */
    @Transactional
    public OrderRma platformRejectRma(Long operatorId, String rmaNo, String reason) {
        if (operatorId == null) throw new BusinessException(403, "请先登录平台运营账号");
        OrderRma rma = loadRma(rmaNo);
        if (rma == null) throw new BusinessException(404, "售后单不存在");
        if (RMA_STATUS_PLATFORM_REJECTED.equals(rma.getStatus())) return rma; // 幂等
        if ("success".equals(rma.getStatus())) throw new BusinessException("该售后单已退款结案，不可驳回");
        if (!RMA_STATUS_PLATFORM_PENDING.equals(rma.getStatus())) {
            throw new BusinessException(409, "当前售后状态不可平台驳回（" + rma.getStatus() + "）");
        }
        String why = reason == null || reason.isBlank() ? "平台复核未通过" : reason.trim();
        int hit = transitionRmaStatus(rmaNo, RMA_STATUS_PLATFORM_PENDING, RMA_STATUS_PLATFORM_REJECTED,
                operatorId, why, "REJECTED");
        if (hit == 0) {
            OrderRma fresh = loadRma(rmaNo);
            if (fresh != null && RMA_STATUS_PLATFORM_REJECTED.equals(fresh.getStatus())) return fresh;
            throw new BusinessException(409, "售后单状态已变化，请刷新后重试");
        }
        recordApproval(rmaNo, rma.getOrderNo(), "PLATFORM", "REJECT", operatorId, "ADMIN", null, why, 0);
        markOrderRefundRejected(rma.getOrderNo(), why);
        audit(operatorId, "REFUND_PLATFORM_REJECT", "售后单 " + rmaNo + " 平台驳回：" + why);
        messageService.send(rma.getUserId(), "order", "平台复核未通过",
                "售后单 " + rmaNo + " 平台复核未通过：" + why + "。如有异议可提交售后工单申诉。", rma.getOrderNo());
        return loadRma(rmaNo);
    }

    /**
     * 平台复核通过 · 普通订单退款（链A，无 RMA）：与 RMA 路径同一套审批链与出款守卫，
     * 区别只是售后状态落在 t_order.refund_status（merchant_approved/shipping_back/...）。
     */
    @Transactional
    public Order platformApproveOrder(Long operatorId, String orderNo, RefundReviewRequest req) {
        if (operatorId == null) throw new BusinessException(403, "请先登录平台运营账号");
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException(404, "订单不存在");
        String rs = o.getRefundStatus() == null ? "none" : o.getRefundStatus();
        if ("success".equals(rs)) throw new BusinessException("该订单已全额退款");
        if ("rejected".equals(rs)) {
            throw new BusinessException(403, "退款申请已被驳回，禁止出款；如有异议请走售后工单申诉");
        }
        if (!PLATFORM_REVIEWABLE_ORDER_STATUS.contains(rs)) {
            throw new BusinessException(409, "当前退款状态不可平台复核（" + rs + "）：须先完成商户初审");
        }
        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        BigDecimal remain = refundableTotal(o).subtract(already);
        BigDecimal amount = req != null && req.getAmount() != null ? req.getAmount() : remain;
        validateReviewAmount(amount, remain);
        String note = noteOf(req, "平台复核通过");
        recordApproval(null, orderNo, "PLATFORM", "APPROVE", operatorId, "ADMIN", amount, note, 0);
        audit(operatorId, "REFUND_PLATFORM_APPROVE",
                "订单 " + orderNo + " 平台复核通过（链A），出款 ¥" + amount.toPlainString());
        boolean paid = paymentService.refund(new RefundRequest(orderNo, amount, note), operatorId);
        if (!paid) throw new BusinessException("渠道退款未受理，本次复核已回滚，请稍后重新复核");
        return orderMapper.selectById(orderNo);
    }

    /** 平台驳回复核 · 普通订单（链A）：只改退款状态与留痕，不出款。 */
    @Transactional
    public Order platformRejectOrder(Long operatorId, String orderNo, String reason) {
        if (operatorId == null) throw new BusinessException(403, "请先登录平台运营账号");
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException(404, "订单不存在");
        String rs = o.getRefundStatus() == null ? "none" : o.getRefundStatus();
        if ("success".equals(rs)) throw new BusinessException("该订单已退款结案，不可驳回");
        if ("rejected".equals(rs)) return o; // 幂等
        if (!PLATFORM_REVIEWABLE_ORDER_STATUS.contains(rs)) {
            throw new BusinessException(409, "当前退款状态不可平台驳回（" + rs + "）");
        }
        String why = reason == null || reason.isBlank() ? "平台复核未通过" : reason.trim();
        if (markOrderRefundRejected(orderNo, why) == 0) {
            throw new BusinessException(409, "订单退款状态已变化，请刷新后重试");
        }
        recordApproval(null, orderNo, "PLATFORM", "REJECT", operatorId, "ADMIN", null, why, 0);
        audit(operatorId, "REFUND_PLATFORM_REJECT", "订单 " + orderNo + " 平台驳回：" + why);
        messageService.send(o.getUserId(), "order", "平台复核未通过",
                "订单 " + orderNo + " 的退款申请平台复核未通过：" + why + "。如有异议可提交售后工单申诉。", orderNo);
        return orderMapper.selectById(orderNo);
    }

    /**
     * 申诉重开复核（最小实现）：平台已驳回的 RMA，只有在存在「引用该售后单的在途客服工单」
     * （t_support_ticket，status=OPEN/PROCESSING）时才允许重开为 platform_pending，防止无凭据绕过驳回。
     */
    @Transactional
    public OrderRma reopenForAppeal(Long operatorId, String rmaNo, String remark) {
        if (operatorId == null) throw new BusinessException(403, "请先登录平台运营账号");
        OrderRma rma = loadRma(rmaNo);
        if (rma == null) throw new BusinessException(404, "售后单不存在");
        if (!RMA_STATUS_PLATFORM_REJECTED.equals(rma.getStatus())) {
            throw new BusinessException(409, "仅平台已驳回的售后单可申诉重开");
        }
        if (supportTicketMapper == null) {
            throw new BusinessException(500, "申诉工单组件未就绪，禁止无凭据重开（fail-closed）");
        }
        Long tickets = supportTicketMapper.selectCount(Wrappers.<SupportTicket>lambdaQuery()
                .in(SupportTicket::getStatus, List.of("OPEN", "PROCESSING"))
                .and(w -> w.like(SupportTicket::getContent, rmaNo).or().like(SupportTicket::getTitle, rmaNo)));
        if (tickets == null || tickets == 0) {
            throw new BusinessException(409, "未找到引用该售后单的在途申诉工单，禁止重开复核");
        }
        String note = remark == null || remark.isBlank() ? "申诉核实成立，重开平台复核" : remark.trim();
        int hit = transitionRmaStatus(rmaNo, RMA_STATUS_PLATFORM_REJECTED, RMA_STATUS_PLATFORM_PENDING,
                operatorId, note, "REOPENED");
        if (hit == 0) throw new BusinessException(409, "售后单状态已变化，请刷新后重试");
        recordApproval(rmaNo, rma.getOrderNo(), "PLATFORM", "REOPEN", operatorId, "ADMIN",
                rma.getApplyAmount(), note, 0);
        // 把订单退款状态从 rejected 放回“已初审待复核”（出款白名单内），否则复核通过也过不了状态闸
        orderMapper.update(null, Wrappers.<Order>update()
                .eq("order_no", rma.getOrderNo())
                .eq("refund_status", "rejected")
                .set("refund_status", "merchant_approved")
                .set("refund_reject_reason", null)
                .set("update_time", LocalDateTime.now()));
        audit(operatorId, "REFUND_APPEAL_REOPEN", "售后单 " + rmaNo + " 申诉重开复核：" + note);
        messageService.send(rma.getUserId(), "order", "申诉已受理，售后单重新进入平台复核",
                "售后单 " + rmaNo + " 的申诉已核实，已重新进入平台复核。", rma.getOrderNo());
        return loadRma(rmaNo);
    }

    /** 订单退款审批留痕（双人留痕查询：MERCHANT 初审 + PLATFORM 复核/驳回/重开）。 */
    public List<RefundApproval> approvalHistory(String orderNo) {
        if (refundApprovalMapper == null || orderNo == null || orderNo.isBlank()) return List.of();
        return refundApprovalMapper.listByOrder(orderNo);
    }

    // -------- P0-C 内部工具 --------

    private OrderRma loadRma(String rmaNo) {
        return rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
    }

    /** 商户侧状态流转（不写平台复核列，例：merchant_approved→platform_pending、inspected→platform_pending）。 */
    private int moveRmaStatus(String rmaNo, String from, String to) {
        return rmaMapper.update(null, Wrappers.<OrderRma>update()
                .eq("rma_no", rmaNo).eq("status", from)
                .set("status", to)
                .set("update_time", LocalDateTime.now()));
    }

    /** 平台侧状态流转（同一语句原子写状态 + platform_audit_* 留痕列，防并发双复核）。 */
    private int transitionRmaStatus(String rmaNo, String from, String to,
                                    Long operatorId, String note, String auditStatus) {
        return rmaMapper.update(null, Wrappers.<OrderRma>update()
                .eq("rma_no", rmaNo).eq("status", from)
                .set("status", to)
                .set("platform_auditor_id", operatorId)
                .set("platform_audit_time", LocalDateTime.now())
                .set("platform_audit_note", note)
                .set("platform_audit_status", auditStatus)
                .set("update_time", LocalDateTime.now()));
    }

    /** 写一行审批留痕；表/组件缺失时 fail-closed（审批链不可残缺）。 */
    private Long recordApproval(String rmaNo, String orderNo, String stage, String action,
                                Long operatorId, String role, BigDecimal amount, String remark, int consumed) {
        if (refundApprovalMapper == null) {
            throw new BusinessException(500, "退款审批组件未就绪（t_refund_approval），禁止走审批/出款");
        }
        RefundApproval a = new RefundApproval();
        a.setRmaNo(rmaNo);
        a.setOrderNo(orderNo);
        a.setStage(stage);
        a.setAction(action);
        a.setOperatorId(operatorId == null ? 0L : operatorId);
        a.setOperatorRole(role);
        a.setAmount(amount);
        a.setRemark(remark == null ? null : (remark.length() > 255 ? remark.substring(0, 255) : remark));
        a.setConsumed(consumed);
        a.setCreateTime(LocalDateTime.now());
        refundApprovalMapper.insert(a);
        return a.getId();
    }

    private void audit(Long operatorId, String action, String detail) {
        if (auditService == null) return; // 审计旁路：缺失不阻断资金/状态主流程
        auditService.record(operatorId, action, detail, null);
    }

    /**
     * 可退总额（P1-6 唯一口径）：未发货 = goods + freight；已发货/部分发货 = goods（运费不退）。
     * 存量未回填（goods_amount=null）时 {@link OrderAmounts#refundableTotal(Order, boolean)} 回落 total_amount。
     * 与 {@code OrderService}/{@code PaymentService} 退款上限同源，避免三套口径。
     */
    private BigDecimal refundableTotal(Order o) {
        boolean shipped = o.getOrderStatus() != null && (o.getOrderStatus() == 1 || o.getOrderStatus() == 4);
        return OrderAmounts.refundableTotal(o, shipped);
    }

    private void validateReviewAmount(BigDecimal amount, BigDecimal remain) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("复核退款金额需大于 0");
        }
        if (remain != null && amount.compareTo(remain) > 0) {
            throw new BusinessException("复核退款金额超出可退余额（剩余 ¥"
                    + (remain == null ? "-" : remain.toPlainString()) + "）");
        }
    }

    private String noteOf(RefundReviewRequest req, String fallback) {
        String note = req == null ? null
                : (req.getRemark() != null && !req.getRemark().isBlank() ? req.getRemark() : req.getReason());
        return note == null || note.isBlank() ? fallback : note.trim();
    }

    /** 平台驳回后把订单退款状态置 rejected（仅从“可复核”状态迁，终态/在途不动）。 */
    private int markOrderRefundRejected(String orderNo, String reason) {
        return orderMapper.update(null, Wrappers.<Order>update()
                .eq("order_no", orderNo)
                .in("refund_status", List.of("merchant_approved", "shipping_back", "received", "partial"))
                .set("refund_status", "rejected")
                .set("refund_reject_reason", reason)
                .set("update_time", LocalDateTime.now()));
    }

    /** 查询 RMA 详情 */
    public OrderRma getRmaDetail(String rmaNo) {
        return rmaMapper.selectOne(Wrappers.<OrderRma>lambdaQuery().eq(OrderRma::getRmaNo, rmaNo));
    }

    /** 查询订单关联的最新 RMA */
    public OrderRma getByOrderNo(String orderNo) {
        return rmaMapper.findLatestByOrderNo(orderNo);
    }

    /**
     * 买家查询自己的 RMA 详情。
     *
     * <p>rmaNo 是可枚举的业务单号，服务层必须校验归属，否则任意登录用户都能读他人售后单
     * （含收货地址、电话、举证凭证 URL）。不属于当前用户时统一按"不存在"处理，避免探测。
     */
    public OrderRma getRmaDetailForBuyer(Long userId, String rmaNo) {
        OrderRma rma = getRmaDetail(rmaNo);
        if (rma == null || userId == null || !userId.equals(rma.getUserId())) {
            throw new BusinessException("售后单不存在");
        }
        return rma;
    }

    /** 买家按订单号查询自己订单的最新 RMA。 */
    public OrderRma getByOrderNoForBuyer(Long userId, String orderNo) {
        OrderRma rma = getByOrderNo(orderNo);
        if (rma == null || userId == null || !userId.equals(rma.getUserId())) {
            throw new BusinessException("售后单不存在");
        }
        return rma;
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
