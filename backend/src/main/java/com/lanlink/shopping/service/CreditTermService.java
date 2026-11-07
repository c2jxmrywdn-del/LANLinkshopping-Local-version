package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditAdjustRequest;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.CreditRepayment;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.vo.RepayResultVO;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.mapper.CreditAccountMapper;
import com.lanlink.shopping.mapper.CreditAdjustRequestMapper;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.CreditRepaymentMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CreditTermService {
    private static final Logger log = LoggerFactory.getLogger(CreditTermService.class);
    private static final BigDecimal MAX_REQUEST = new BigDecimal("500000");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final int MIN_TERM_DAYS = 7;
    public static final int MAX_TERM_DAYS = 15;
    public static final BigDecimal DEFAULT_DAILY_RATE = new BigDecimal("0.000300");
    public static final BigDecimal DEFAULT_APR = new BigDecimal("0.1080");
    public static final int DEFAULT_GRACE_DAYS = 3;
    private static final Set<String> REPAY_METHODS = Set.of("wallet", "transfer");

    private final CreditAccountMapper accountMapper;
    private final CreditBillMapper billMapper;
    private final CreditRepaymentMapper repaymentMapper;
    private final CreditAdjustRequestMapper adjustMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.lanlink.shopping.payment.wallet.service.WalletService walletService;

    /** 审计服务（可选注入，保持既有构造器签名不变，缺省时跳过审计） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;

    /** 物流结算单 Mapper（可选注入，支持物流运费账期支付担保） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.mapper.LogisticsSettlementMapper settlementMapper;

    /** 结算明细 / 运单 Mapper（可选注入：账期支付的结算单属主校验依赖；缺失时 fail-closed） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.mapper.LogisticsSettlementItemMapper settlementItemMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.mapper.LogisticsWaybillMapper waybillMapper;

    public CreditTermService(CreditAccountMapper accountMapper, CreditBillMapper billMapper,
                             CreditRepaymentMapper repaymentMapper, CreditAdjustRequestMapper adjustMapper,
                             UserMapper userMapper, MessageService messageService,
                             ApplicationEventPublisher eventPublisher,
                             com.lanlink.shopping.payment.wallet.service.WalletService walletService) {
        this.accountMapper = accountMapper;
        this.billMapper = billMapper;
        this.repaymentMapper = repaymentMapper;
        this.adjustMapper = adjustMapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.walletService = walletService;
    }

    // ==================== 概览 ====================

    public Map<String, Object> overview(Long userId) {
        CreditAccount a = accountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery()
                .eq(CreditAccount::getUserId, userId));
        List<CreditBill> bills = a == null ? List.of() : billMapper.selectList(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getAccountId, a.getId())
                .orderByAsc(CreditBill::getDueDate)
                .orderByDesc(CreditBill::getCreateTime));

        LocalDate today = LocalDate.now();
        LocalDate dueSoonLine = today.plusDays(7);
        BigDecimal outstanding = ZERO;
        BigDecimal overdueAmount = ZERO;
        BigDecimal dueSoonAmount = ZERO;
        BigDecimal paidTotal = ZERO;
        int overdueCount = 0;
        int dueSoonCount = 0;

        for (CreditBill b : bills) {
            BigDecimal billOutstanding = nvl(b.getOutstandingAmount());
            paidTotal = paidTotal.add(nvl(b.getPaidAmount()));
            boolean settled = "PAID".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus());
            if (settled) continue;
            outstanding = outstanding.add(billOutstanding);
            boolean overdue = "OVERDUE".equals(b.getStatus())
                    || (b.getDueDate() != null && b.getDueDate().isBefore(today));
            if (overdue) {
                overdueAmount = overdueAmount.add(billOutstanding);
                overdueCount++;
            } else if (b.getDueDate() != null && !b.getDueDate().isAfter(dueSoonLine)) {
                dueSoonAmount = dueSoonAmount.add(billOutstanding);
                dueSoonCount++;
            }
        }

        List<CreditRepayment> pendingRows = repaymentMapper.selectList(Wrappers.<CreditRepayment>lambdaQuery()
                .eq(CreditRepayment::getUserId, userId)
                .eq(CreditRepayment::getStatus, "PENDING"));
        Map<Long, BigDecimal> pendingByBill = new LinkedHashMap<>();
        BigDecimal pendingTransferAmount = ZERO;
        for (CreditRepayment r : pendingRows) {
            pendingTransferAmount = pendingTransferAmount.add(nvl(r.getAmount()));
            pendingByBill.merge(r.getBillId(), nvl(r.getAmount()), BigDecimal::add);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outstanding", outstanding);
        summary.put("overdueAmount", overdueAmount);
        summary.put("overdueCount", overdueCount);
        summary.put("dueSoonAmount", dueSoonAmount);
        summary.put("dueSoonCount", dueSoonCount);
        summary.put("paidTotal", paidTotal);
        summary.put("billCount", bills.size());
        summary.put("pendingTransferAmount", pendingTransferAmount);
        summary.put("pendingByBill", pendingByBill);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", a == null ? "NOT_APPLIED" : a.getStatus());
        out.put("account", a);
        out.put("bills", bills);
        out.put("outstanding", outstanding);
        out.put("summary", summary);
        out.put("pendingAdjustment", a == null ? null : adjustMapper.selectPendingByAccount(a.getId()));
        return out;
    }

    // ==================== 授信申请 ====================

    @Transactional
    public CreditAccount apply(Long userId, BigDecimal requestedLimit, Integer termDays, String purpose) {
        validateLimit(requestedLimit, ZERO, false);
        validateTerm(termDays);
        CreditAccount old = accountMapper.selectByUserForUpdate(userId);
        if (old != null && "ACTIVE".equals(old.getStatus()))
            throw new BusinessException("当前已有生效中的授信额度，如需调整请提交调额申请");
        CreditAccount a = old == null ? new CreditAccount() : old;
        User u = userMapper.selectById(userId);
        a.setUserId(userId);
        a.setEntId(u == null ? null : u.getEntId());
        a.setStatus("PENDING");
        a.setRequestedLimit(requestedLimit);
        a.setCreditLimit(ZERO);
        a.setAvailableLimit(ZERO);
        a.setUsedLimit(ZERO);
        a.setTermDays(termDays);
        a.setPurpose(text(purpose, 255));
        a.setRiskLevel("第三方机构待核验");
        if (a.getRiskTier() == null) a.setRiskTier("A");
        if (a.getDailyRate() == null) a.setDailyRate(DEFAULT_DAILY_RATE);
        if (a.getApr() == null) a.setApr(DEFAULT_APR);
        if (a.getGraceDays() == null) a.setGraceDays(DEFAULT_GRACE_DAYS);
        a.setReviewRemark(null);
        a.setApplyTime(LocalDateTime.now());
        a.setApprovedTime(null);
        a.setUpdateTime(LocalDateTime.now());
        if (a.getId() == null) accountMapper.insert(a); else accountMapper.updateById(a);
        messageService.send(userId, "credit", "账期申请已提交",
                "授信申请 ¥" + requestedLimit + " / " + termDays + " 天，等待平台审核。", String.valueOf(a.getId()));
        return a;
    }

    @Transactional
    public CreditBill createBillForOrder(Long userId, String orderNo, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("账期金额必须大于 0");
        CreditAccount a = accountMapper.selectByUserForUpdate(userId);
        if (a == null || !"ACTIVE".equals(a.getStatus())) throw new BusinessException("当前没有可用的账期额度，请先申请并通过审核");
        if (accountMapper.reserve(a.getId(), amount) == 0) throw new BusinessException("账期可用额度不足");
        CreditBill b = new CreditBill();
        b.setAccountId(a.getId());
        b.setUserId(userId);
        b.setOrderNo(orderNo);
        b.setBillType("ORDER_PROCUREMENT");
        b.setFreezeStatus("ACTIVE");
        b.setAmount(amount);
        b.setPaidAmount(ZERO);
        b.setOutstandingAmount(amount);
        b.setPrincipalAmount(amount);
        b.setInterestAmount(ZERO);
        b.setPaidInterest(ZERO);
        b.setPenaltyAmount(ZERO);
        b.setPaidPenalty(ZERO);
        b.setDailyRate(a.getDailyRate() != null ? a.getDailyRate() : DEFAULT_DAILY_RATE);
        b.setApr(a.getApr() != null ? a.getApr() : DEFAULT_APR);
        b.setInterestStartDate(LocalDate.now());
        b.setLastAccrualDate(LocalDate.now());
        b.setDueDate(LocalDate.now().plusDays(a.getTermDays()));
        b.setStatus("UNPAID");
        b.setCreateTime(LocalDateTime.now());
        b.setUpdateTime(LocalDateTime.now());
        billMapper.insert(b);
        messageService.send(userId, "credit", "账期已生效",
                "订单 " + orderNo + " 已占用授信 ¥" + amount + "，到期日 " + b.getDueDate() + "。", orderNo);
        return b;
    }

    @Transactional
    public CreditBill createBillForSettlement(Long userId, String settlementNo, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("账期金额必须大于 0");
        CreditAccount a = accountMapper.selectByUserForUpdate(userId);
        if (a == null || !"ACTIVE".equals(a.getStatus())) throw new BusinessException("当前没有可用的账期额度，请先申请并通过审核");
        if (accountMapper.reserve(a.getId(), amount) == 0) throw new BusinessException("账期可用额度不足以担保本次物流运费结算 ¥" + amount);
        CreditBill b = new CreditBill();
        b.setAccountId(a.getId());
        b.setUserId(userId);
        b.setOrderNo(settlementNo);
        b.setBillType("LOGISTICS_FREIGHT");
        b.setSettlementNo(settlementNo);
        b.setFreezeStatus("ACTIVE");
        b.setAmount(amount);
        b.setPaidAmount(ZERO);
        b.setOutstandingAmount(amount);
        b.setPrincipalAmount(amount);
        b.setInterestAmount(ZERO);
        b.setPaidInterest(ZERO);
        b.setPenaltyAmount(ZERO);
        b.setPaidPenalty(ZERO);
        b.setDailyRate(a.getDailyRate() != null ? a.getDailyRate() : DEFAULT_DAILY_RATE);
        b.setApr(a.getApr() != null ? a.getApr() : DEFAULT_APR);
        b.setInterestStartDate(LocalDate.now());
        b.setLastAccrualDate(LocalDate.now());
        b.setDueDate(LocalDate.now().plusDays(a.getTermDays()));
        b.setStatus("UNPAID");
        b.setCreateTime(LocalDateTime.now());
        b.setUpdateTime(LocalDateTime.now());
        billMapper.insert(b);
        messageService.send(userId, "credit", "物流运费账期已生效",
                "物流结算单 " + settlementNo + " 已占用授信 ¥" + amount + "，到期日 " + b.getDueDate() + "。", settlementNo);
        return b;
    }

    @Transactional
    public CreditBill paySettlementWithCredit(Long settlementId, Long userId, jakarta.servlet.http.HttpServletRequest httpReq) {
        if (settlementMapper == null) throw new BusinessException("物流结算服务暂不可用");
        com.lanlink.shopping.entity.LogisticsSettlement s = settlementMapper.selectById(settlementId);
        if (s == null) throw new BusinessException("物流结算单不存在");
        if (!"approved".equals(s.getStatus())) throw new BusinessException("仅审核通过的物流结算单支持使用账期支付");
        assertSettlementOwnership(s, userId, httpReq);
        // 先原子抢占（WHERE status='approved'）再建账单：并发下后到者 affected=0 即拒绝，
        // 且其已产生的账单插入随本事务回滚，杜绝同一结算单双重建账单。
        if (settlementMapper.markPaidWithCredit(settlementId) == 0) {
            throw new BusinessException("结算单状态已变化或已被支付，请刷新后重试");
        }
        s.setStatus("paid");
        s.setPayMethod("credit_guarantee");
        s.setPaidTime(LocalDateTime.now());
        CreditBill bill = createBillForSettlement(userId, s.getSettlementNo(), s.getPayableAmount());
        if (settlementMapper.markPaidCreditBill(settlementId, bill.getId()) == 0) {
            throw new BusinessException("结算单状态已变化，请刷新后重试");
        }
        s.setCreditBillId(bill.getId());
        s.setUpdateTime(LocalDateTime.now());
        if (auditService != null) {
            auditService.record(userId, "LOGISTICS_SETTLE_CREDIT", "结算单 " + s.getSettlementNo() + " 通过企业账期担保完成支付", httpReq);
        }
        return bill;
    }

    /**
     * 结算单属主校验（fail-closed）：非管理员的付款人必须与结算单明细运单的发货方一致，
     * 防止任意商户把他人的 approved 结算单置为 paid。运单归属以 created_by（发货操作人）为准。
     */
    private void assertSettlementOwnership(com.lanlink.shopping.entity.LogisticsSettlement s, Long userId,
                                           jakarta.servlet.http.HttpServletRequest httpReq) {
        // 平台运营台代为结算走管理端身份（LogisticsCenter 既有流程），不受商户归属约束
        if (com.lanlink.shopping.config.UserContext.identity(httpReq) == com.lanlink.shopping.common.UserIdentity.ADMIN) {
            return;
        }
        if (settlementItemMapper == null || waybillMapper == null) {
            throw new BusinessException("物流结算服务暂不可用");
        }
        List<com.lanlink.shopping.entity.LogisticsSettlementItem> items = settlementItemMapper.selectList(
                Wrappers.<com.lanlink.shopping.entity.LogisticsSettlementItem>lambdaQuery()
                        .eq(com.lanlink.shopping.entity.LogisticsSettlementItem::getSettlementId, s.getSettlementId()));
        List<Long> waybillIds = items == null ? List.of() : items.stream()
                .map(com.lanlink.shopping.entity.LogisticsSettlementItem::getWaybillId)
                .filter(Objects::nonNull)
                .toList();
        if (waybillIds.isEmpty()) throw new BusinessException("结算单无结算明细，暂不支持账期支付");
        List<com.lanlink.shopping.entity.LogisticsWaybill> waybills = waybillMapper.selectBatchIds(waybillIds);
        if (waybills == null || waybills.size() < waybillIds.size()) {
            throw new BusinessException("结算单运单数据缺失，暂不支持账期支付");
        }
        boolean allMine = waybills.stream().allMatch(w -> userId.equals(w.getCreatedBy()));
        if (!allMine) throw new BusinessException("仅结算单归属商户可使用账期支付该结算单");
    }

    public List<CreditBill> bills(Long userId) {
        return billMapper.selectList(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getUserId, userId).orderByAsc(CreditBill::getDueDate));
    }

    public CreditBill detail(Long userId, Long id) {
        CreditBill b = billMapper.selectOne(Wrappers.<CreditBill>lambdaQuery().eq(CreditBill::getId, id).eq(CreditBill::getUserId, userId));
        if (b == null) throw new BusinessException("账单不存在");
        return b;
    }

    public List<CreditRepayment> repayments(Long userId, Long billId) {
        detail(userId, billId);
        return repaymentMapper.selectList(Wrappers.<CreditRepayment>lambdaQuery()
                .eq(CreditRepayment::getUserId, userId)
                .eq(CreditRepayment::getBillId, billId)
                .orderByDesc(CreditRepayment::getCreateTime));
    }

    // ==================== 还款 / 转账待确认 ====================

    /**
     * 账单还款。wallet 实时入账并释放授信；transfer 仅登记待确认，不冲减账单。
     * 可还金额始终为「账单待还 - 待确认转账金额」，避免重复提交导致超还。
     */
    @Transactional
    public RepayResultVO repay(Long userId, Long id, BigDecimal amount, String method, String referenceNo) {
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0)
            throw new BusinessException("还款金额必须大于 0");
        if (amount.scale() > 2) throw new BusinessException("还款金额最多两位小数");
        String payMethod = normalizeMethod(method);
        CreditBill b = billMapper.selectForUpdate(id, userId);
        if (b == null) throw new BusinessException("账单不存在");
        if ("PAID".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus()))
            throw new BusinessException("账单已结清或已取消");

        BigDecimal pending = nvl(repaymentMapper.sumPendingAmount(id));
        BigDecimal payable = nvl(b.getOutstandingAmount()).subtract(pending);
        if (payable.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("该账单存在待确认的转账登记，当前无可还金额");
        if (amount.compareTo(payable) > 0)
            throw new BusinessException("当前可还金额为 ¥" + payable.toPlainString() + "，请调整还款金额");

        CreditRepayment r = new CreditRepayment();
        r.setBillId(id);
        r.setUserId(userId);
        r.setAmount(amount);
        r.setMethod(payMethod);
        r.setRemark("transfer".equals(payMethod) ? "对公转账登记" : "账期账单还款");
        r.setCreateTime(LocalDateTime.now());

        if ("transfer".equals(payMethod)) {
            String ref = text(referenceNo, 64);
            r.setStatus("PENDING");
            r.setReferenceNo(ref.isEmpty() ? "TR" + System.currentTimeMillis() : ref);
            repaymentMapper.insert(r);
            messageService.send(userId, "credit", "转账登记已提交",
                    "账单 " + id + " 转账 ¥" + amount + " 已登记，待平台财务核销后入账。", String.valueOf(id));
            return repayResult(b, r, true, payable.subtract(amount));
        }

        walletService.payFromWallet(userId, amount, "CREDIT-" + id);
        CreditAccount a = accountMapper.selectByIdForUpdate(b.getAccountId());
        if (a == null) throw new BusinessException("授信账户不存在");
        r.setStatus("CONFIRMED");
        r.setReferenceNo("CR" + System.currentTimeMillis());
        r.setConfirmedTime(LocalDateTime.now());
        repaymentMapper.insert(r);
        settleBill(b, amount, a);
        return repayResult(b, r, false, payable.subtract(amount));
    }

    /**
     * 管理端财务核销对公转账：确认后冲减账单并释放授信，驳回则只改状态。
     * 锁序：账单 → 授信账户 → 还款记录，与用户还款/订单取消保持一致。
     */
    @Transactional
    public CreditRepayment confirmTransferRepayment(Long repaymentId, boolean approved, String remark) {
        CreditRepayment preview = repaymentMapper.selectById(repaymentId);
        if (preview == null) throw new BusinessException("还款记录不存在");
        CreditBill b = billMapper.selectByIdForUpdate(preview.getBillId());
        if (b == null) throw new BusinessException("账单不存在");
        CreditAccount a = accountMapper.selectByIdForUpdate(b.getAccountId());
        if (a == null) throw new BusinessException("授信账户不存在");
        CreditRepayment r = repaymentMapper.selectByIdForUpdate(repaymentId);
        if (r == null || !"PENDING".equals(r.getStatus()))
            throw new BusinessException("该转账登记已处理");

        String note = text(remark, 255);
        if (!approved) {
            if (repaymentMapper.rejectPending(repaymentId, note) != 1)
                throw new BusinessException("该转账登记已被其他操作处理");
            r.setStatus("REJECTED");
            r.setConfirmRemark(note);
            r.setConfirmedTime(LocalDateTime.now());
            messageService.send(r.getUserId(), "credit", "转账核销未通过",
                    "账单 " + r.getBillId() + " 的转账 ¥" + r.getAmount() + " 未通过核销" +
                            (note.isEmpty() ? "。" : "：" + note), String.valueOf(r.getBillId()));
            return r;
        }

        if ("PAID".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus()))
            throw new BusinessException("账单已结清或已取消，无法确认转账入账");
        if (r.getAmount() == null || r.getAmount().compareTo(nvl(b.getOutstandingAmount())) > 0)
            throw new BusinessException("转账金额超过账单待还金额，无法确认");
        if (repaymentMapper.confirmPending(repaymentId, note) != 1)
            throw new BusinessException("该转账登记已被其他操作处理");
        r.setStatus("CONFIRMED");
        r.setConfirmRemark(note);
        r.setConfirmedTime(LocalDateTime.now());
        settleBill(b, r.getAmount(), a);
        return r;
    }

    public List<Map<String, Object>> adminRepayments(String status) {
        String queryStatus = "ALL".equalsIgnoreCase(status) ? null : (isBlank(status) ? "PENDING" : status.trim());
        List<CreditRepayment> rows = repaymentMapper.selectList(Wrappers.<CreditRepayment>lambdaQuery()
                .eq(queryStatus != null, CreditRepayment::getStatus, queryStatus)
                .orderByDesc(CreditRepayment::getCreateTime));
        List<Long> billIds = rows.stream().map(CreditRepayment::getBillId).filter(Objects::nonNull).distinct().toList();
        Map<Long, CreditBill> billMap = new LinkedHashMap<>();
        if (!billIds.isEmpty()) {
            for (CreditBill bill : billMapper.selectBatchIds(billIds)) billMap.put(bill.getId(), bill);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CreditRepayment r : rows) {
            CreditBill b = billMap.get(r.getBillId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("billId", r.getBillId());
            m.put("userId", r.getUserId());
            m.put("amount", r.getAmount());
            m.put("method", r.getMethod());
            m.put("referenceNo", r.getReferenceNo());
            m.put("remark", r.getRemark());
            m.put("status", r.getStatus());
            m.put("confirmRemark", r.getConfirmRemark());
            m.put("createTime", r.getCreateTime());
            m.put("confirmedTime", r.getConfirmedTime());
            m.put("orderNo", b == null ? null : b.getOrderNo());
            m.put("billAmount", b == null ? null : b.getAmount());
            m.put("outstandingAmount", b == null ? null : b.getOutstandingAmount());
            m.put("billStatus", b == null ? null : b.getStatus());
            out.add(m);
        }
        return out;
    }

    @Transactional
    public void cancelOrderBill(Long userId, String orderNo) {
        CreditBill found = billMapper.selectOne(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getUserId, userId).eq(CreditBill::getOrderNo, orderNo));
        if (found == null) return;
        CreditBill b = billMapper.selectByIdForUpdate(found.getId());
        if (b == null || "CANCELLED".equals(b.getStatus()) || "PAID".equals(b.getStatus())) return;
        CreditAccount a = accountMapper.selectByIdForUpdate(b.getAccountId());
        if (a == null) throw new BusinessException("授信账户不存在");
        BigDecimal outstanding = nvl(b.getOutstandingAmount());
        int rejected = repaymentMapper.rejectPendingByBill(b.getId(), "订单取消，转账登记自动驳回");
        b.setStatus("CANCELLED");
        b.setOutstandingAmount(ZERO);
        b.setUpdateTime(LocalDateTime.now());
        if (billMapper.updateById(b) != 1) throw new BusinessException("账单取消失败");
        if (outstanding.compareTo(BigDecimal.ZERO) > 0 && accountMapper.release(a.getId(), outstanding) != 1)
            throw new BusinessException("授信额度释放失败");
        messageService.send(userId, "credit", "账期订单已释放", "订单 " + orderNo + " 已取消，未使用授信已释放。", orderNo);
        if (rejected > 0)
            log.info("取消账期订单 {} 时自动驳回 {} 条待确认转账登记", orderNo, rejected);
    }

    /**
     * 账期订单退款资产守恒结算（冲减账单未还额度并释放授信，已还现金原路退回企业钱包）。
     * 退款金额 = 冲减未还额度 (释放可用授信) + 退还已还现金 (退回企业钱包)
     * 绝不可对已还现金二次释放授信，维护资产绝对守恒。
     */
    @Transactional
    public BigDecimal refundOrderBill(Long userId, String orderNo, BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        CreditBill found = billMapper.selectOne(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getOrderNo, orderNo));
        if (found == null) {
            log.warn("[CREDIT-REFUND] 未找到订单 {} 对应的账期账单，跳过账期核减", orderNo);
            return BigDecimal.ZERO;
        }
        CreditBill b = billMapper.selectByIdForUpdate(found.getId());
        if (b == null || "CANCELLED".equals(b.getStatus())) {
            log.warn("[CREDIT-REFUND] 账单 {} 不存在或已取消", found.getId());
            return BigDecimal.ZERO;
        }
        CreditAccount a = accountMapper.selectByIdForUpdate(b.getAccountId());
        if (a == null) {
            throw new BusinessException("授信账户不存在");
        }

        BigDecimal outstanding = nvl(b.getOutstandingAmount());
        BigDecimal paid = nvl(b.getPaidAmount());
        BigDecimal totalRefund = refundAmount;

        // 1. 先冲减未结清金额 (释放对应授信额度)
        BigDecimal creditRelease = BigDecimal.ZERO;
        if (outstanding.compareTo(BigDecimal.ZERO) > 0) {
            creditRelease = totalRefund.min(outstanding);
            outstanding = outstanding.subtract(creditRelease);
            b.setOutstandingAmount(outstanding);
            if (accountMapper.release(a.getId(), creditRelease) != 1) {
                throw new BusinessException("授信额度释放失败");
            }
        }

        // 2. 剩余退款金额属于已还现金部分，原路退回企业钱包 (绝不可二次释放授信)
        BigDecimal cashToWallet = totalRefund.subtract(creditRelease);
        if (cashToWallet.compareTo(BigDecimal.ZERO) > 0) {
            if (cashToWallet.compareTo(paid) > 0) {
                log.warn("[CREDIT-REFUND] 退还现金超出账单已还金额: cashToWallet={}, paid={}", cashToWallet, paid);
                cashToWallet = paid;
            }
            if (cashToWallet.compareTo(BigDecimal.ZERO) > 0) {
                paid = paid.subtract(cashToWallet);
                b.setPaidAmount(paid);
                walletService.refundToWallet(b.getUserId(), cashToWallet, orderNo, "账期已还现金退款入账");
            }
        }

        // 3. 更新账单总额与状态
        BigDecimal newAmount = b.getAmount().subtract(creditRelease).subtract(cashToWallet);
        if (newAmount.compareTo(BigDecimal.ZERO) < 0) newAmount = BigDecimal.ZERO;
        b.setAmount(newAmount);
        b.setPrincipalAmount(newAmount);

        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            if (paid.compareTo(BigDecimal.ZERO) <= 0) {
                b.setStatus("CANCELLED");
            } else {
                b.setStatus("PAID");
                b.setSettledTime(LocalDateTime.now());
            }
        }
        b.setUpdateTime(LocalDateTime.now());
        if (billMapper.updateById(b) != 1) {
            throw new BusinessException("账单更新失败");
        }

        String notice = "订单 " + orderNo + " 退款结算成功：释放授信 " + creditRelease + " 元";
        if (cashToWallet.compareTo(BigDecimal.ZERO) > 0) {
            notice += "，退回企业钱包 " + cashToWallet + " 元";
        }
        messageService.send(b.getUserId(), "credit", "账期退款到账", notice + "。", orderNo);
        log.info("[CREDIT-REFUND] 账期退款成功 orderNo={} refundAmount={} creditRelease={} cashToWallet={}",
                orderNo, refundAmount, creditRelease, cashToWallet);
        return totalRefund;
    }

    // ==================== 逾期定时任务 ====================

    /**
     * 账期账单逾期检查：每天凌晨 2 点扫描到期未还账单。
     * 到期日早于今天且仍为 UNPAID 的账单，经条件 UPDATE 流转为 OVERDUE（仅 UNPAID→OVERDUE，
     * 返回 1 才视为本次流转成功，天然防并发与重复通知），并对每个账单所属用户发送站内催收提醒。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 2 * * ?")
    public void overdueCheck() {
        LocalDate today = LocalDate.now();
        List<CreditBill> candidates = billMapper.selectOverdueCandidates(today);
        int overdue = 0;
        for (CreditBill b : candidates) {
            if (b.getId() == null || b.getUserId() == null) continue;
            // 条件 UPDATE 防重复：仅 UNPAID 且已到期的账单会流转成功，重跑/并发下不会重复发消息
            if (billMapper.markOverdueIfUnpaid(b.getId(), today) != 1) continue;
            overdue++;
            BigDecimal outstanding = nvl(b.getOutstandingAmount());
            messageService.send(b.getUserId(), "credit", "账期账单已逾期",
                    "账单 " + b.getId() + "（订单 " + (isBlank(b.getOrderNo()) ? "-" : b.getOrderNo())
                            + "）已逾期，应还金额 ¥" + outstanding + "，请尽快还款以免影响授信使用。",
                    isBlank(b.getOrderNo()) ? String.valueOf(b.getId()) : b.getOrderNo());
        }
        if (overdue > 0) {
            log.info("账期逾期检查完成：{} 笔账单由 UNPAID 置为 OVERDUE", overdue);
            if (auditService != null) {
                auditService.record(null, "CREDIT_BILL_OVERDUE", "账期逾期定时检查：" + overdue + " 笔账单置为 OVERDUE", null);
            }
        }
    }

    // ==================== 管理端授信审核 ====================

    public List<CreditAccount> adminAccounts(String status) {
        return accountMapper.selectList(Wrappers.<CreditAccount>lambdaQuery()
                .eq(status != null && !status.isBlank(), CreditAccount::getStatus, status)
                .orderByDesc(CreditAccount::getApplyTime));
    }

    @Transactional
    public CreditAccount review(Long id, String status, BigDecimal approvedLimit, Integer termDays, String remark) {
        if (!"ACTIVE".equals(status) && !"REJECTED".equals(status)) throw new BusinessException("审核状态无效");
        CreditAccount a = accountMapper.selectByIdForUpdate(id);
        if (a == null) throw new BusinessException("授信申请不存在");
        BigDecimal used = nvl(a.getUsedLimit());
        if ("ACTIVE".equals(status)) {
            BigDecimal limit = approvedLimit == null
                    ? (nvl(a.getCreditLimit()).compareTo(BigDecimal.ZERO) > 0 ? a.getCreditLimit() : a.getRequestedLimit())
                    : approvedLimit;
            validateLimit(limit, used, true);
            int days = termDays == null ? a.getTermDays() : termDays;
            validateTerm(days);
            a.setCreditLimit(limit);
            a.setAvailableLimit(limit.subtract(used));
            a.setUsedLimit(used);
            a.setTermDays(days);
            a.setRiskLevel("第三方机构核准");
            if (a.getRiskTier() == null) {
                a.setRiskTier("A");
            }
            if (a.getCreditGrantToken() == null) {
                a.setCreditGrantToken("AUTH_TOKEN_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
            }
            if (a.getAssessmentRefNo() == null) {
                a.setAssessmentRefNo("CRA_EXT_" + System.currentTimeMillis());
            }
            if (a.getValidUntil() == null) {
                a.setValidUntil(LocalDateTime.now().plusMonths(6));
            }
            if (a.getDailyRate() == null) a.setDailyRate(DEFAULT_DAILY_RATE);
            if (a.getApr() == null) a.setApr(DEFAULT_APR);
            if (a.getGraceDays() == null) a.setGraceDays(DEFAULT_GRACE_DAYS);
            if (a.getApprovedTime() == null) a.setApprovedTime(LocalDateTime.now());
            int withdrawn = adjustMapper.withdrawPendingByAccount(id, "平台直接调整授信，待审调额申请已自动撤回");
            if (withdrawn > 0) log.info("授信账户 {} 直接审核通过，自动撤回 {} 条待审调额申请", id, withdrawn);
        } else {
            if (used.compareTo(BigDecimal.ZERO) > 0) throw new BusinessException("该账户存在已占用授信，无法驳回");
            a.setCreditLimit(ZERO);
            a.setAvailableLimit(ZERO);
            a.setUsedLimit(ZERO);
            a.setApprovedTime(null);
            a.setRiskLevel("未通过");
        }
        a.setStatus(status);
        a.setReviewRemark(text(remark, 255));
        a.setUpdateTime(LocalDateTime.now());
        if (accountMapper.updateById(a) == 0) log.warn("授信账户 {} 审核字段未发生变化", id);
        messageService.send(a.getUserId(), "credit", "账期审核结果",
                "你的账期申请已" + ("ACTIVE".equals(status) ? "通过，额度 ¥" + a.getCreditLimit() + " / " + a.getTermDays() + " 天" : "被驳回") + "。",
                String.valueOf(id));
        return a;
    }

    // ==================== 调额申请 ====================

    public List<CreditAdjustRequest> adjustments(Long userId) {
        return adjustMapper.selectList(Wrappers.<CreditAdjustRequest>lambdaQuery()
                .eq(CreditAdjustRequest::getUserId, userId)
                .orderByDesc(CreditAdjustRequest::getApplyTime));
    }

    @Transactional
    public CreditAdjustRequest applyAdjustment(Long userId, BigDecimal requestedLimit, Integer termDays, String purpose) {
        CreditAccount a = accountMapper.selectByUserForUpdate(userId);
        if (a == null || !"ACTIVE".equals(a.getStatus()))
            throw new BusinessException("当前没有生效中的授信额度，请先完成账期申请");
        BigDecimal used = nvl(a.getUsedLimit());
        validateLimit(requestedLimit, used, true);
        validateTerm(termDays);
        if (requestedLimit.compareTo(nvl(a.getCreditLimit())) == 0 && termDays.equals(a.getTermDays()))
            throw new BusinessException("新额度与账期均未变化，无需提交调额申请");
        CreditAdjustRequest pending = adjustMapper.selectPendingByAccount(a.getId());
        if (pending != null) throw new BusinessException("已有待审核的调额申请，请先撤回后再提交");

        CreditAdjustRequest r = new CreditAdjustRequest();
        r.setAccountId(a.getId());
        r.setUserId(userId);
        r.setRequestedLimit(requestedLimit);
        r.setTermDays(termDays);
        r.setPurpose(text(purpose, 255));
        r.setStatus("PENDING");
        r.setApplyTime(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        adjustMapper.insert(r);
        messageService.send(userId, "credit", "调额申请已提交",
                "申请额度 ¥" + requestedLimit + " / " + termDays + " 天，审核期间原授信额度继续可用。", String.valueOf(r.getId()));
        return r;
    }

    @Transactional
    public CreditAdjustRequest withdrawAdjustment(Long userId, Long id) {
        CreditAdjustRequest r = adjustMapper.selectByIdForUpdate(id);
        if (r == null || !Objects.equals(r.getUserId(), userId)) throw new BusinessException("调额申请不存在");
        if (!"PENDING".equals(r.getStatus())) throw new BusinessException("仅待审核的调额申请可以撤回");
        r.setStatus("WITHDRAWN");
        r.setReviewRemark("用户主动撤回");
        r.setReviewedTime(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        if (adjustMapper.updateById(r) != 1) throw new BusinessException("调额申请撤回失败");
        messageService.send(userId, "credit", "调额申请已撤回", "你可以重新提交新的调额申请。", String.valueOf(id));
        return r;
    }

    public List<Map<String, Object>> adminAdjustments(String status) {
        String queryStatus = "ALL".equalsIgnoreCase(status) ? null : (isBlank(status) ? null : status.trim());
        List<CreditAdjustRequest> rows = adjustMapper.selectList(Wrappers.<CreditAdjustRequest>lambdaQuery()
                .eq(queryStatus != null, CreditAdjustRequest::getStatus, queryStatus)
                .orderByDesc(CreditAdjustRequest::getApplyTime));
        List<Long> accountIds = rows.stream().map(CreditAdjustRequest::getAccountId).filter(Objects::nonNull).distinct().toList();
        Map<Long, CreditAccount> accountMap = new LinkedHashMap<>();
        if (!accountIds.isEmpty()) {
            for (CreditAccount a : accountMapper.selectBatchIds(accountIds)) accountMap.put(a.getId(), a);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CreditAdjustRequest r : rows) {
            CreditAccount a = accountMap.get(r.getAccountId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("accountId", r.getAccountId());
            m.put("userId", r.getUserId());
            m.put("requestedLimit", r.getRequestedLimit());
            m.put("termDays", r.getTermDays());
            m.put("purpose", r.getPurpose());
            m.put("status", r.getStatus());
            m.put("approvedLimit", r.getApprovedLimit());
            m.put("approvedTermDays", r.getApprovedTermDays());
            m.put("reviewRemark", r.getReviewRemark());
            m.put("applyTime", r.getApplyTime());
            m.put("reviewedTime", r.getReviewedTime());
            m.put("creditLimit", a == null ? null : a.getCreditLimit());
            m.put("usedLimit", a == null ? null : a.getUsedLimit());
            m.put("availableLimit", a == null ? null : a.getAvailableLimit());
            m.put("accountStatus", a == null ? null : a.getStatus());
            out.add(m);
        }
        return out;
    }

    @Transactional
    public CreditAdjustRequest reviewAdjustment(Long id, String status, BigDecimal approvedLimit, Integer termDays, String remark) {
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) throw new BusinessException("审核状态无效");
        CreditAdjustRequest preview = adjustMapper.selectById(id);
        if (preview == null) throw new BusinessException("调额申请不存在");
        CreditAccount a = accountMapper.selectByIdForUpdate(preview.getAccountId());
        if (a == null) throw new BusinessException("授信账户不存在");
        CreditAdjustRequest r = adjustMapper.selectByIdForUpdate(id);
        if (r == null || !"PENDING".equals(r.getStatus())) throw new BusinessException("该调额申请已处理");

        String note = text(remark, 255);
        if ("APPROVED".equals(status)) {
            if (!"ACTIVE".equals(a.getStatus())) throw new BusinessException("授信账户未生效，无法调整额度");
            BigDecimal limit = approvedLimit == null ? r.getRequestedLimit() : approvedLimit;
            int days = termDays == null ? r.getTermDays() : termDays;
            validateLimit(limit, nvl(a.getUsedLimit()), true);
            validateTerm(days);
            a.setCreditLimit(limit);
            a.setAvailableLimit(limit.subtract(nvl(a.getUsedLimit())));
            a.setTermDays(days);
            a.setUpdateTime(LocalDateTime.now());
            if (accountMapper.updateById(a) == 0) log.warn("授信账户 {} 调额字段未发生变化", a.getId());
            r.setApprovedLimit(limit);
            r.setApprovedTermDays(days);
            r.setStatus("APPROVED");
        } else {
            r.setStatus("REJECTED");
        }
        r.setReviewRemark(note);
        r.setReviewedTime(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        if (adjustMapper.updateById(r) != 1) throw new BusinessException("调额审核失败");
        messageService.send(r.getUserId(), "credit", "调额审核结果",
                "你的调额申请已" + ("APPROVED".equals(status)
                        ? "通过，新额度 ¥" + a.getCreditLimit() + " / " + a.getTermDays() + " 天"
                        : "被驳回") + "。", String.valueOf(id));
        return r;
    }

    // ==================== 内部方法 ====================

    private RepayResultVO repayResult(CreditBill bill, CreditRepayment repayment, boolean pending, BigDecimal payableAmount) {
        RepayResultVO out = new RepayResultVO();
        out.setBill(bill);
        out.setRepayment(repayment);
        out.setPending(pending);
        out.setPayableAmount(payableAmount.max(BigDecimal.ZERO));
        return out;
    }

    /** 统一账单入账逻辑：冲减待还、结清/逾期流转、释放授信、发消息。账户须已在调用方锁定。 */
    private void settleBill(CreditBill b, BigDecimal amount, CreditAccount a) {
        BigDecimal paid = nvl(b.getPaidAmount()).add(amount);
        BigDecimal outstanding = nvl(b.getOutstandingAmount()).subtract(amount);
        boolean settled = outstanding.compareTo(BigDecimal.ZERO) <= 0;
        b.setPaidAmount(paid);
        b.setOutstandingAmount(settled ? ZERO : outstanding);
        b.setUpdateTime(LocalDateTime.now());
        if (settled) {
            b.setStatus("PAID");
            b.setSettledTime(LocalDateTime.now());
        } else if (b.getDueDate() != null && LocalDate.now().isAfter(b.getDueDate())) {
            b.setStatus("OVERDUE");
        }
        if (billMapper.updateById(b) != 1) throw new BusinessException("账单状态更新失败");
        if (accountMapper.release(a.getId(), amount) != 1) throw new BusinessException("授信额度释放失败");

        if (settled) {
            messageService.send(b.getUserId(), "credit", "账期已结清",
                    "账单 " + b.getId() + " 已全部还清，授信额度已恢复。", String.valueOf(b.getId()));
            eventPublisher.publishEvent(new OrderPaidEvent(this, b.getUserId(), b.getOrderNo(), b.getAmount()));
        } else {
            messageService.send(b.getUserId(), "credit", "账期还款成功",
                    "账单 " + b.getId() + " 本次还款 ¥" + amount + "，剩余 ¥" + b.getOutstandingAmount() + "。", String.valueOf(b.getId()));
        }
    }

    private String normalizeMethod(String method) {
        String value = isBlank(method) ? "wallet" : method.trim().toLowerCase();
        if (!REPAY_METHODS.contains(value)) throw new BusinessException("还款方式仅支持企业钱包或对公转账");
        return value;
    }

    private void validateLimit(BigDecimal limit, BigDecimal used, boolean allowEqual) {
        if (limit == null || limit.compareTo(new BigDecimal("1000")) < 0)
            throw new BusinessException("授信额度不可低于 1000 元");
        if (limit.compareTo(MAX_REQUEST) > 0) throw new BusinessException("授信额度不可超过 50 万元");
        if (limit.scale() > 2) throw new BusinessException("授信额度最多两位小数");
        int cmp = limit.compareTo(used);
        if (cmp < 0 || (!allowEqual && cmp == 0 && used.compareTo(BigDecimal.ZERO) > 0))
            throw new BusinessException("授信额度不能低于已占用额度 ¥" + used.toPlainString());
    }

    public Map<String, Object> calculateInterestTrial(BigDecimal amount, Integer termDays, BigDecimal dailyRateInput, Integer graceDaysInput, Integer overdueDaysInput) {
        return calculateInterestTrial(amount, termDays, dailyRateInput, graceDaysInput, overdueDaysInput, null, false);
    }

    public Map<String, Object> calculateInterestTrial(BigDecimal amount, Integer termDays, BigDecimal dailyRateInput, Integer graceDaysInput, Integer overdueDaysInput, String riskTier, Boolean withLogisticsDiscount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("试算本金必须大于 0");
        }
        int days = termDays == null ? MIN_TERM_DAYS : termDays;
        validateTerm(days);

        String tier = (riskTier == null || riskTier.isBlank()) ? "A" : riskTier.trim().toUpperCase();
        BigDecimal resolvedDailyRate;
        int grace;
        if (dailyRateInput != null) {
            resolvedDailyRate = dailyRateInput;
            grace = graceDaysInput == null ? DEFAULT_GRACE_DAYS : Math.max(0, graceDaysInput);
        } else if (riskTier != null && !riskTier.isBlank()) {
            switch (tier) {
                case "AAA" -> {
                    resolvedDailyRate = new BigDecimal("0.000150"); // 0.015%/天, APR 5.40%
                    grace = graceDaysInput == null ? 3 : Math.max(0, graceDaysInput);
                }
                case "AA" -> {
                    resolvedDailyRate = new BigDecimal("0.000200"); // 0.020%/天, APR 7.20%
                    grace = graceDaysInput == null ? 3 : Math.max(0, graceDaysInput);
                }
                case "B" -> {
                    resolvedDailyRate = new BigDecimal("0.000300"); // 0.030%/天, APR 10.80%
                    grace = graceDaysInput == null ? 2 : Math.max(0, graceDaysInput);
                }
                default -> { // A
                    resolvedDailyRate = new BigDecimal("0.000250"); // 0.025%/天, APR 9.00%
                    grace = graceDaysInput == null ? 3 : Math.max(0, graceDaysInput);
                }
            }
        } else {
            resolvedDailyRate = DEFAULT_DAILY_RATE;
            grace = graceDaysInput == null ? DEFAULT_GRACE_DAYS : Math.max(0, graceDaysInput);
        }

        BigDecimal discountRate = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(withLogisticsDiscount)) {
            discountRate = "AAA".equals(tier) ? new BigDecimal("0.1500") : new BigDecimal("0.1000");
            resolvedDailyRate = resolvedDailyRate.multiply(BigDecimal.ONE.subtract(discountRate)).setScale(6, java.math.RoundingMode.HALF_UP);
        }

        if (resolvedDailyRate.compareTo(BigDecimal.ZERO) < 0 || resolvedDailyRate.compareTo(new BigDecimal("0.001")) > 0) {
            throw new BusinessException("日利率不合法（允许范围：0 ~ 0.1%/天）");
        }
        int overdue = overdueDaysInput == null ? 0 : Math.max(0, overdueDaysInput);

        int interestBearingDays = Math.max(0, days - grace);
        BigDecimal normalInterest = amount.multiply(resolvedDailyRate)
                .multiply(BigDecimal.valueOf(interestBearingDays))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal overdueRate = resolvedDailyRate.multiply(new BigDecimal("1.5"));
        BigDecimal penaltyInterest = amount.multiply(overdueRate)
                .multiply(BigDecimal.valueOf(overdue))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal totalInterest = normalInterest.add(penaltyInterest);
        BigDecimal totalRepayment = amount.add(totalInterest);
        BigDecimal dailyCost = amount.multiply(resolvedDailyRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal apr = resolvedDailyRate.multiply(new BigDecimal("360")).setScale(4, java.math.RoundingMode.HALF_UP);

        // 标准档位对比 (7天/10天/15天)
        List<Map<String, Object>> tierComparisons = new ArrayList<>();
        for (int tierDays : List.of(7, 10, 15)) {
            int tierInterestDays = Math.max(0, tierDays - grace);
            BigDecimal tierInterest = amount.multiply(resolvedDailyRate)
                    .multiply(BigDecimal.valueOf(tierInterestDays))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("termDays", tierDays);
            t.put("graceDays", grace);
            t.put("interestBearingDays", tierInterestDays);
            t.put("normalInterest", tierInterest);
            t.put("totalRepayment", amount.add(tierInterest));
            t.put("isCurrent", tierDays == days);
            tierComparisons.add(t);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("principal", amount);
        res.put("termDays", days);
        res.put("riskTier", tier);
        res.put("dailyRate", resolvedDailyRate);
        res.put("dailyRatePercent", resolvedDailyRate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%");
        res.put("apr", apr);
        res.put("aprPercent", apr.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%");
        res.put("graceDays", grace);
        res.put("interestBearingDays", interestBearingDays);
        res.put("normalInterest", normalInterest);
        res.put("overdueDays", overdue);
        res.put("overdueRate", overdueRate);
        res.put("overdueRatePercent", overdueRate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%");
        res.put("penaltyInterest", penaltyInterest);
        res.put("totalInterest", totalInterest);
        res.put("totalRepayment", totalRepayment);
        res.put("dailyCost", dailyCost);
        res.put("tierComparisons", tierComparisons);
        res.put("discountRate", discountRate);
        res.put("complianceNote", "按单利年化 APR 真实披露，遵守合规利率红线；平台不留存原始征信报告。");
        return res;
    }

    private void validateTerm(Integer termDays) {
        if (termDays == null || termDays < MIN_TERM_DAYS || termDays > MAX_TERM_DAYS)
            throw new BusinessException("账期仅支持 7 ~ 15 天（标准档位：7天/10天/15天）");
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String text(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new BusinessException("文本长度不可超过 " + max + " 个字符");
        return trimmed;
    }
}
