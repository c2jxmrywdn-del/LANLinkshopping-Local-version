package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.CreditRepayment;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.mapper.CreditAccountMapper;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.CreditRepaymentMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CreditTermService {
    private static final BigDecimal MAX_REQUEST = new BigDecimal("500000");
    private final CreditAccountMapper accountMapper;
    private final CreditBillMapper billMapper;
    private final CreditRepaymentMapper repaymentMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.lanlink.shopping.payment.wallet.service.WalletService walletService;

    public CreditTermService(CreditAccountMapper accountMapper, CreditBillMapper billMapper,
                             CreditRepaymentMapper repaymentMapper, UserMapper userMapper,
                             MessageService messageService, ApplicationEventPublisher eventPublisher,
                             com.lanlink.shopping.payment.wallet.service.WalletService walletService) {
        this.accountMapper = accountMapper;
        this.billMapper = billMapper;
        this.repaymentMapper = repaymentMapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.walletService = walletService;
    }

    public Map<String, Object> overview(Long userId) {
        CreditAccount a = accountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery()
                .eq(CreditAccount::getUserId, userId));
        List<CreditBill> bills = a == null ? List.of() : billMapper.selectList(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getAccountId, a.getId())
                .orderByAsc(CreditBill::getDueDate)
                .orderByDesc(CreditBill::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", a == null ? "NOT_APPLIED" : a.getStatus());
        out.put("account", a);
        out.put("bills", bills);
        BigDecimal outstanding = bills.stream().filter(b -> !"PAID".equals(b.getStatus()) && !"CANCELLED".equals(b.getStatus()))
                .map(CreditBill::getOutstandingAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("outstanding", outstanding);
        return out;
    }

    @Transactional
    public CreditAccount apply(Long userId, BigDecimal requestedLimit, Integer termDays, String purpose) {
        if (requestedLimit == null || requestedLimit.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("申请额度必须大于 0");
        if (requestedLimit.compareTo(MAX_REQUEST) > 0)
            throw new BusinessException("单次申请额度不可超过 50 万元");
        if (requestedLimit.scale() > 2) throw new BusinessException("额度最多两位小数");
        if (termDays == null || !Set.of(30, 60, 90).contains(termDays))
            throw new BusinessException("账期仅支持 30 / 60 / 90 天");
        CreditAccount old = accountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery().eq(CreditAccount::getUserId, userId));
        if (old != null && "ACTIVE".equals(old.getStatus()))
            throw new BusinessException("当前已有生效中的授信额度");
        CreditAccount a = old == null ? new CreditAccount() : old;
        User u = userMapper.selectById(userId);
        a.setUserId(userId);
        a.setEntId(u == null ? null : u.getEntId());
        a.setStatus("PENDING");
        a.setRequestedLimit(requestedLimit);
        a.setCreditLimit(BigDecimal.ZERO);
        a.setAvailableLimit(BigDecimal.ZERO);
        a.setUsedLimit(BigDecimal.ZERO);
        a.setTermDays(termDays);
        a.setPurpose(purpose == null ? "" : purpose.trim());
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
        CreditAccount a = accountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery().eq(CreditAccount::getUserId, userId));
        if (a == null || !"ACTIVE".equals(a.getStatus())) throw new BusinessException("当前没有可用的账期额度，请先申请并通过审核");
        if (accountMapper.reserve(a.getId(), amount) == 0) throw new BusinessException("账期可用额度不足");
        CreditBill b = new CreditBill();
        b.setAccountId(a.getId());
        b.setUserId(userId);
        b.setOrderNo(orderNo);
        b.setAmount(amount);
        b.setPaidAmount(BigDecimal.ZERO);
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

    @Transactional
    public CreditBill repay(Long userId, Long id, BigDecimal amount, String method) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("还款金额必须大于 0");
        CreditBill b = billMapper.selectForUpdate(id, userId);
        if (b == null) throw new BusinessException("账单不存在");
        if ("PAID".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus())) throw new BusinessException("账单已结清或已取消");
        if (amount.compareTo(b.getOutstandingAmount()) > 0) amount = b.getOutstandingAmount();
        if ("wallet".equalsIgnoreCase(method == null ? "wallet" : method)) {
            walletService.payFromWallet(userId, amount, "CREDIT-" + id);
        }
        CreditRepayment r = new CreditRepayment();
        r.setBillId(id); r.setUserId(userId); r.setAmount(amount);
        r.setMethod(method == null || method.isBlank() ? "wallet" : method);
        r.setReferenceNo("CR" + System.currentTimeMillis());
        r.setRemark("账期账单还款");
        r.setCreateTime(LocalDateTime.now());
        repaymentMapper.insert(r);

        b.setPaidAmount(b.getPaidAmount().add(amount));
        b.setOutstandingAmount(b.getOutstandingAmount().subtract(amount));
        b.setUpdateTime(LocalDateTime.now());
        boolean settled = b.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0;
        if (settled) { b.setOutstandingAmount(BigDecimal.ZERO); b.setStatus("PAID"); b.setSettledTime(LocalDateTime.now()); }
        else if (LocalDate.now().isAfter(b.getDueDate())) b.setStatus("OVERDUE");
        billMapper.updateById(b);

        CreditAccount a = accountMapper.selectById(b.getAccountId());
        if (a == null) throw new BusinessException("授信账户不存在");
        accountMapper.release(a.getId(), amount);

        if (settled) {
            messageService.send(userId, "credit", "账期已结清", "账单 " + id + " 已全部还清，授信额度已恢复。", String.valueOf(id));
            eventPublisher.publishEvent(new OrderPaidEvent(this, userId, b.getOrderNo(), b.getAmount()));
        } else {
            messageService.send(userId, "credit", "账期还款成功", "账单 " + id + " 本次还款 ¥" + amount + "，剩余 ¥" + b.getOutstandingAmount() + "。", String.valueOf(id));
        }
        return b;
    }

    @Transactional
    public void cancelOrderBill(Long userId, String orderNo) {
        CreditBill b = billMapper.selectOne(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getUserId, userId).eq(CreditBill::getOrderNo, orderNo));
        if (b == null || "CANCELLED".equals(b.getStatus()) || "PAID".equals(b.getStatus())) return;
        BigDecimal outstanding = b.getOutstandingAmount() == null ? BigDecimal.ZERO : b.getOutstandingAmount();
        b.setStatus("CANCELLED"); b.setUpdateTime(LocalDateTime.now());
        billMapper.updateById(b);
        if (outstanding.compareTo(BigDecimal.ZERO) > 0) accountMapper.release(b.getAccountId(), outstanding);
        messageService.send(userId, "credit", "账期订单已释放", "订单 " + orderNo + " 已取消，未使用授信已释放。", orderNo);
    }

    public List<CreditAccount> adminAccounts(String status) {
        return accountMapper.selectList(Wrappers.<CreditAccount>lambdaQuery()
                .eq(status != null && !status.isBlank(), CreditAccount::getStatus, status)
                .orderByDesc(CreditAccount::getApplyTime));
    }

    @Transactional
    public CreditAccount review(Long id, String status, BigDecimal approvedLimit, Integer termDays, String remark) {
        CreditAccount a = accountMapper.selectById(id);
        if (a == null) throw new BusinessException("授信申请不存在");
        if (!"ACTIVE".equals(status) && !"REJECTED".equals(status)) throw new BusinessException("审核状态无效");
        if ("ACTIVE".equals(status)) {
            BigDecimal used = a.getUsedLimit() == null ? BigDecimal.ZERO : a.getUsedLimit();
            BigDecimal limit = approvedLimit == null ? (a.getCreditLimit().compareTo(BigDecimal.ZERO) > 0 ? a.getCreditLimit() : a.getRequestedLimit()) : approvedLimit;
            if (limit.compareTo(BigDecimal.ZERO) <= 0 || limit.compareTo(MAX_REQUEST) > 0 || limit.compareTo(used) < 0)
                throw new BusinessException("批准额度不合法，不能低于已占用额度");
            if (termDays == null || !Set.of(30,60,90).contains(termDays)) termDays = a.getTermDays();
            a.setCreditLimit(limit); a.setAvailableLimit(limit.subtract(used)); a.setUsedLimit(used);
            a.setTermDays(termDays); a.setRiskLevel("标准"); a.setApprovedTime(LocalDateTime.now());
        } else {
            a.setCreditLimit(BigDecimal.ZERO); a.setAvailableLimit(BigDecimal.ZERO); a.setUsedLimit(BigDecimal.ZERO);
            a.setApprovedTime(null); a.setRiskLevel("未通过");
        }
        a.setStatus(status); a.setReviewRemark(remark == null ? "" : remark.trim()); a.setUpdateTime(LocalDateTime.now());
        accountMapper.updateById(a);
        messageService.send(a.getUserId(), "credit", "账期审核结果",
                "你的账期申请已" + ("ACTIVE".equals(status) ? "通过，额度 ¥" + a.getCreditLimit() + " / " + a.getTermDays() + " 天" : "被驳回") + "。",
                String.valueOf(id));
        return a;
    }
}
