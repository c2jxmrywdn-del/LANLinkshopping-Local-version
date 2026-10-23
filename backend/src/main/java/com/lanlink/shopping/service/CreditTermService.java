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
    private static final Set<Integer> TERM_DAYS = Set.of(30, 60, 90);
    private static final Set<String> REPAY_METHODS = Set.of("wallet", "transfer");

    private final CreditAccountMapper accountMapper;
    private final CreditBillMapper billMapper;
    private final CreditRepaymentMapper repaymentMapper;
    private final CreditAdjustRequestMapper adjustMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.lanlink.shopping.payment.wallet.service.WalletService walletService;

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
        a.setRiskLevel("待评估");
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
        b.setAmount(amount);
        b.setPaidAmount(ZERO);
        b.setOutstandingAmount(amount);
        b.setDueDate(LocalDate.now().plusDays(a.getTermDays()));
        b.setStatus("UNPAID");
        b.setCreateTime(LocalDateTime.now());
        b.setUpdateTime(LocalDateTime.now());
        billMapper.insert(b);
        messageService.send(userId, "credit", "账期已生效",
                "订单 " + orderNo + " 已占用授信 ¥" + amount + "，到期日 " + b.getDueDate() + "。", orderNo);
        return b;
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
            a.setRiskLevel("标准");
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

    private void validateTerm(Integer termDays) {
        if (termDays == null || !TERM_DAYS.contains(termDays))
            throw new BusinessException("账期仅支持 30 / 60 / 90 天");
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
