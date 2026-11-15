package com.lanlink.shopping.module.finance.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import com.lanlink.shopping.module.finance.dto.FundBalance;
import com.lanlink.shopping.module.finance.entity.FinanceLedger;
import com.lanlink.shopping.module.finance.entity.MerchantWithdrawal;
import com.lanlink.shopping.module.finance.mapper.FinanceLedgerMapper;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.SequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 商户提现：申请冻结 → 管理端打款/驳回 → 商户撤回，台账与业务同事务落库。
 *
 * <p><b>可提余额口径（最小实现）</b>：
 * {@code Σ(t_merchant_earning.net_amount WHERE status IN ('SETTLED','REVERSED'))}
 * {@code − Σ(t_merchant_withdrawal.amount WHERE status=1 已打款)}
 * {@code − Σ(t_merchant_withdrawal.amount WHERE status=0 待审=在途冻结)}。
 * SETTLED 表示该笔净额已进入结算批次但批次尚未登记打款；**PAID（已随批次打款）不计入本口径**
 * （批次一旦登记线下对公打款，其收益行即转终态 PAID 并离开可提余额，避免同一笔钱被提现通道再付一次）；
 * REVERSED 冲正行净额为负（已进过批次的退款冲减），SETTLED 与 REVERSED 之和即「已结算未打款」；
 * PENDING/AVAILABLE 仍在持有期与待结算池，不进可提口径。驳回/撤回单已解冻，不计任何方向。
 *
 * <p><b>并发纪律（防双花）</b>：每条资金路径（申请冻结 / 打款 / 驳回 / 撤回）在事务内先取
 * {@link MerchantFundLockService#lockMerchant(Long) 商户行排他锁}（t_merchant 行
 * {@code SELECT ... FOR UPDATE} 当前读，实现见 {@link MerchantFundLockService}），
 * 这是同商户全部资金变动（含结算批次登记打款）的唯一互斥点——后来者在此排队，先行者提交后才能继续。互斥点之后，
 * 余额判定全部走 FOR UPDATE 锁定读（已结算净额、累计已打款、在途冻结三项聚合均为当前读），
 * 不依赖 REPEATABLE READ 快照：事务先做过的普通一致性读（如 mustMerchant 解析商户）会把
 * 读视图定在并发对手提交之前，此后快照读会漏掉对方已提交的冻结/打款，这正是双花根因。
 * 商户行锁与锁定读聚合两道防线任一生效即阻断双花：同商户并发双申请恰好一成一 400
 * 「可提余额不足」。状态流转全部条件更新（WHERE status=0），返回 0 即被并发先行者抢占。
 * 纯展示读（balanceOf）不参与判定，不加锁。
 *
 * <p><b>台账纪律</b>：冻结与解冻落台账 {@code t_finance_ledger}
 * （biz_type: withdraw_freeze / withdraw_reject_unrefreeze，撤回与驳回共用解冻类型）；
 * 打款只改单据状态——打款凭证 + reviewed_by 审核人即审计轨迹，不改变商户侧余额，故不落台账。
 * amount 正入负出，balance_after 在锁定读余额上顺序推算；与业务变更同一事务，回滚即同滚。
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private static final String MIN_AMOUNT = "1.00";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MerchantWithdrawalMapper withdrawalMapper;
    private final FinanceLedgerMapper ledgerMapper;
    private final MerchantService merchantService;
    private final SequenceService sequenceService;
    private final com.lanlink.shopping.module.attachment.service.AttachmentBizType voucherBizType =
            com.lanlink.shopping.module.attachment.service.AttachmentBizType.WITHDRAWAL_VOUCHER;
    /** 凭证中转 URL 形态固定为 /api/attachment/{no}/content，用于从 URL 反解附件单号做 bind。 */
    private static final String VOUCHER_URL_PREFIX = "/api/attachment/";
    private static final String VOUCHER_URL_SUFFIX = "/content";
    private final com.lanlink.shopping.module.attachment.service.AttachmentService attachmentService;
    /** 资金互斥点 + 可提余额口径唯一实现点（P0-A 通道 B 抽公共，禁止在此内联 settled/paid SQL）。 */
    private final MerchantFundLockService fundLock;

    public WithdrawalService(MerchantWithdrawalMapper withdrawalMapper,
                             FinanceLedgerMapper ledgerMapper,
                             MerchantService merchantService,
                             SequenceService sequenceService,
                             AttachmentService attachmentService,
                             MerchantFundLockService fundLock) {
        this.withdrawalMapper = withdrawalMapper;
        this.ledgerMapper = ledgerMapper;
        this.merchantService = merchantService;
        this.sequenceService = sequenceService;
        this.attachmentService = attachmentService;
        this.fundLock = fundLock;
    }

    /** 商户端：可提余额与在途冻结概览（读路径无锁，仅为展示；申请路径才走锁定读）。 */
    public java.util.Map<String, Object> balanceOf(Long userId) {
        Merchant m = mustMerchant(userId);
        BigDecimal settled = fundLock.settled(m.getMerId());
        BigDecimal paid = withdrawalMapper.sumPaid(m.getMerId());
        BigDecimal frozen = withdrawalMapper.sumFrozen(m.getMerId());
        BigDecimal balance = money(settled).subtract(money(paid)).subtract(money(frozen));
        return new java.util.LinkedHashMap<>(java.util.Map.of(
                "available", maxZero(balance),
                "settledTotal", maxZero(money(settled)),
                "paidTotal", maxZero(money(paid)),
                "frozenTotal", maxZero(money(frozen)),
                "pendingCount", withdrawalMapper.countPending(m.getMerId())));
    }

    /**
     * 商户申请提现：余额校验 → 落提现单（status=0 冻结）→ 台账 withdraw_freeze（负），
     * 同事务提交。先取商户行锁（资金互斥点），再以锁定读聚合三项余额（当前读），
     * 并发双申请只可能成功一笔（见类注释并发纪律）。
     */
    @Transactional
    public MerchantWithdrawal apply(Long userId, BigDecimal amount, String bankAccountName,
                                    String bankAccountNo, String bankName, String applyNote) {
        Merchant m = mustMerchant(userId);
        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        if (amt.compareTo(new BigDecimal(MIN_AMOUNT)) < 0) {
            throw new BusinessException(400, "提现金额最低 " + MIN_AMOUNT + " 元");
        }
        if (bankAccountName == null || bankAccountName.isBlank()) {
            throw new BusinessException(400, "请填写收款账户户名");
        }
        if (bankAccountNo == null || bankAccountNo.isBlank()) {
            throw new BusinessException(400, "请填写银行账号");
        }
        if (bankName == null || bankName.isBlank()) {
            throw new BusinessException(400, "请填写开户银行全称");
        }
        if (bankAccountName.length() > 64 || bankName.length() > 100) {
            throw new BusinessException(400, "户名或开户行超出长度限制");
        }
        if (!bankAccountNo.matches("^[0-9A-Za-z\\-\\s]{8,35}$")) {
            throw new BusinessException(400, "银行账号格式不正确（8~35 位数字或字母）");
        }

        // 资金互斥点：先锁商户行（与结算批次登记打款同一把锁，见 MerchantFundLockService），
        // 随后聚合全部为锁定读（当前读），快照读不参与余额判定，见类注释并发纪律
        fundLock.lockMerchant(m.getMerId());
        FundBalance fb = fundLock.lockedBalance(m.getMerId());
        BigDecimal balance = fb.balance();
        if (balance.compareTo(amt) < 0) {
            throw new BusinessException(400, "可提余额不足（当前可提 ¥"
                    + maxZero(balance).toPlainString() + "）");
        }

        String no = nextWithdrawNo();
        MerchantWithdrawal w = new MerchantWithdrawal();
        w.setMerId(m.getMerId());
        w.setWithdrawNo(no);
        w.setAmount(amt);
        w.setBalanceSnapshot(maxZero(balance));
        w.setBankAccountName(bankAccountName.trim());
        // 全号只在本次请求内存中参与掩码，落库仅前4后4掩码；商户档案 bank_account（AES 加密）不回写
        w.setBankAccountNoMasked(DesensitizeUtil.maskBankCard(bankAccountNo.replaceAll("\\s+", "")));
        w.setBankName(bankName.trim());
        w.setStatus(0);
        w.setApplyNote(applyNote == null ? null : applyNote.trim());
        w.setCreateTime(LocalDateTime.now());
        w.setUpdateTime(LocalDateTime.now());
        withdrawalMapper.insert(w);

        // 台账与业务同事务：冻结即出账（负），balance_after 在锁定读余额上顺序推算
        appendLedger(m.getMerId(), "withdraw_freeze", no, amt.negate(), balance.subtract(amt),
                "提现申请冻结（单号 " + no + "）");
        return w;
    }

    /**
     * 管理端审核通过并标记打款：待审 → 已打款（条件更新抢占）。
     * 打款凭证（附件中转 URL，运营侧 /attachment/upload 上传后回传 no→url）必填。
     * 打款不落台账：不改商户侧余额（冻结扣减发生在申请时），凭证与审核人即审计轨迹。
     * 二清口径对齐结算域：此处仅登记线下对公打款与凭证，平台不发起任何出金调用。
     */
    @Transactional
    public MerchantWithdrawal approve(Long adminUserId, Long id, String voucherUrl) {
        if (voucherUrl == null || voucherUrl.isBlank()) {
            throw new BusinessException(400, "请上传打款凭证");
        }
        MerchantWithdrawal w = require(id);
        fundLock.lockMerchant(w.getMerId());
        requireSettledCoverage(w.getMerId(), w.getWithdrawNo(), w.getAmount());
        LocalDateTime now = LocalDateTime.now();
        if (withdrawalMapper.markPaid(id, voucherUrl.trim(), adminUserId, now) == 0) {
            throw new BusinessException(400, "该提现单不在待审状态，无法打款");
        }
        bindVoucher(voucherUrl.trim(), w.getId(), adminUserId);
        // 打款是冻结的最终去向：余额不变（冻结扣减在申请时已发生），不写台账——
        // 台账只记资金变动，打款不改变商户侧余额，只改单据状态。
        return require(id);
    }

    /**
     * 管理端驳回：待审 → 驳回（条件更新抢占）+ 解冻（台账 withdraw_reject_unrefreeze，正入）。
     * 先取商户行锁（资金互斥点），与同商户其他资金路径互斥（见类注释并发纪律）。
     */
    @Transactional
    public MerchantWithdrawal reject(Long adminUserId, Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "请填写驳回原因");
        }
        MerchantWithdrawal w = require(id);
        fundLock.lockMerchant(w.getMerId());
        LocalDateTime now = LocalDateTime.now();
        if (withdrawalMapper.markRejected(id, reason.trim(), adminUserId, now) == 0) {
            throw new BusinessException(400, "该提现单不在待审状态，无法驳回");
        }
        // 台账 balance_after 必须用锁定读：普通读在 RR 下会沿用事务早期快照，
        // 漏掉并发已提交的结算/打款，导致对账出现无法解释的差额。
        BigDecimal settled = fundLock.lockedSettled(w.getMerId());
        BigDecimal paid = fundLock.lockedPaid(w.getMerId());
        // 解冻时点：该单已不再计入在途冻结（status 已翻转为 2），余额 = settled - paid
        BigDecimal balance = settled.subtract(paid);
        appendLedger(w.getMerId(), "withdraw_reject_unrefreeze", w.getWithdrawNo(), w.getAmount(),
                balance, "提现驳回解冻（单号 " + w.getWithdrawNo() + "）");
        return require(id);
    }

    /**
     * 商户撤回（仅待审态）：解冻（台账 withdraw_reject_unrefreeze，正入）。
     * merId 由服务端从会话解析（控制器传入），跨商户撤回在 SQL WHERE mer_id 一并挡掉。
     * 归属校验通过后取商户行锁（资金互斥点），与同商户其他资金路径互斥（见类注释并发纪律）。
     */
    @Transactional
    public MerchantWithdrawal cancel(Long userId, Long id) {
        Merchant m = mustMerchant(userId);
        MerchantWithdrawal w = require(id);
        if (!java.util.Objects.equals(w.getMerId(), m.getMerId())) {
            throw new BusinessException(403, "无权操作该提现单");
        }
        fundLock.lockMerchant(w.getMerId());
        LocalDateTime now = LocalDateTime.now();
        if (withdrawalMapper.markWithdrawn(id, m.getMerId(), now) == 0) {
            throw new BusinessException(400, "仅待审核的提现单可撤回");
        }
        BigDecimal settled = fundLock.lockedSettled(w.getMerId());
        BigDecimal paid = fundLock.lockedPaid(w.getMerId());
        BigDecimal balance = settled.subtract(paid);
        appendLedger(w.getMerId(), "withdraw_reject_unrefreeze", w.getWithdrawNo(), w.getAmount(),
                balance, "提现撤回解冻（单号 " + w.getWithdrawNo() + "）");
        return require(id);
    }

    /** 商户端提现记录分页（仅本人 merId）。 */
    public IPage<MerchantWithdrawal> myPage(Long userId, long current, long size, Integer status) {
        Merchant m = mustMerchant(userId);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantWithdrawal> qw =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<MerchantWithdrawal>lambdaQuery()
                        .eq(MerchantWithdrawal::getMerId, m.getMerId())
                        .eq(status != null, MerchantWithdrawal::getStatus, status)
                        .orderByDesc(MerchantWithdrawal::getId);
        return withdrawalMapper.selectPage(new Page<>(current, size), qw);
    }

    /** 管理端提现单分页（merId/status 可选过滤）。 */
    public IPage<MerchantWithdrawal> adminPage(long current, long size, Long merId, Integer status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantWithdrawal> qw =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<MerchantWithdrawal>lambdaQuery()
                        .eq(merId != null, MerchantWithdrawal::getMerId, merId)
                        .eq(status != null, MerchantWithdrawal::getStatus, status)
                        .orderByAsc(MerchantWithdrawal::getId)
                        .orderByDesc(MerchantWithdrawal::getCreateTime);
        return withdrawalMapper.selectPage(new Page<>(current, size), qw);
    }

    /** 商户端财务台账分页（仅本人 merId）。 */
    public IPage<FinanceLedger> ledgerPage(Long userId, long current, long size) {
        Merchant m = mustMerchant(userId);
        return ledgerMapper.selectPage(new Page<>(current, size),
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<FinanceLedger>lambdaQuery()
                        .eq(FinanceLedger::getMerId, m.getMerId())
                        .orderByDesc(FinanceLedger::getId));
    }

    private Merchant mustMerchant(Long userId) {
        Merchant m = merchantService.getByUser(userId);
        if (m == null) {
            throw new BusinessException(400, "仅入驻商户可操作提现");
        }
        return m;
    }

    private MerchantWithdrawal require(Long id) {
        MerchantWithdrawal w = withdrawalMapper.selectById(id);
        if (w == null) {
            throw new BusinessException(404, "提现单不存在");
        }
        return w;
    }

    private void appendLedger(Long merId, String bizType, String bizNo,
                              BigDecimal amount, BigDecimal balanceAfter, String remark) {
        FinanceLedger row = new FinanceLedger();
        row.setMerId(merId);
        row.setBizType(bizType);
        row.setBizNo(bizNo);
        row.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        row.setBalanceAfter(balanceAfter.setScale(2, RoundingMode.HALF_UP));
        row.setRemark(remark);
        row.setCreateTime(LocalDateTime.now());
        ledgerMapper.insert(row);
    }

    /**
     * 打款前置守卫：该商户「已结算净额 − 累计已打款 − 本单金额」不得为负。
     * 结算域后续若对 SETTLED 行做人工冲账，此守卫兜住超打（超打 = 台账净流出大于已结算净额）。
     * 守卫失败抛 400 回滚打款状态翻转。
     */
    private void requireSettledCoverage(Long merId, String withdrawNo, BigDecimal self) {
        // 守卫判定同样走锁定读（当前读）：互斥点之后快照读不参与余额判定（见类注释并发纪律）
        BigDecimal settled = fundLock.lockedSettled(merId);
        BigDecimal paid = fundLock.lockedPaid(merId);
        if (settled.subtract(paid).subtract(self).compareTo(BigDecimal.ZERO) < 0) {
            log.warn("[withdrawal] 打款守卫拦截：settled={} paid={} self={} no={}",
                    settled, paid, self, withdrawNo);
            throw new BusinessException(400, "该商户已结算净额不足以覆盖本笔打款，请先核对结算台账");
        }
    }

    /**
     * 凭证附件落绑：从凭证中转 URL 反解附件单号，bind 到提现单（bizId=提现单 id，admin 身份）。
     * 不 bind 的话附件停留在 PENDING 态，24 小时后被 AttachmentMaintenance 回收删除；且商户侧
     * 查看走本模块代理端点按 bizId 归属校验，不依赖附件 owner。
     * bind 失败只记日志不回滚打款——凭证 URL 已落提现单，可由运营手工补绑，不让材料问题挡资金状态机。
     */
    private void bindVoucher(String voucherUrl, Long withdrawalId, Long adminUserId) {
        try {
            if (voucherUrl == null || !voucherUrl.startsWith(VOUCHER_URL_PREFIX)
                    || !voucherUrl.endsWith(VOUCHER_URL_SUFFIX)) {
                throw new BusinessException(400, "打款凭证须为附件中转地址");
            }
            String no = voucherUrl.substring(VOUCHER_URL_PREFIX.length(),
                    voucherUrl.length() - VOUCHER_URL_SUFFIX.length());
            attachmentService.bind(no, voucherBizType, withdrawalId, adminUserId, true);
        } catch (BusinessException e) {
            log.warn("[withdrawal] 凭证绑定未成功（打款已登记，请运营手工补绑）：{} no={}",
                    e.getMessage(), voucherUrl);
        }
    }

    /** 商户端凭证代理：单据归属校验（merId 必须一致）后才取附件字节，fail-closed。 */
    public AttachmentService.Content loadVoucher(Long userId, Long withdrawalId, boolean thumbnail) {
        Merchant m = mustMerchant(userId);
        MerchantWithdrawal w = require(withdrawalId);
        if (!java.util.Objects.equals(w.getMerId(), m.getMerId())) {
            throw new BusinessException(403, "无权查看该凭证");
        }
        if (w.getVoucherUrl() == null || w.getVoucherUrl().isBlank()
                || !w.getVoucherUrl().startsWith(VOUCHER_URL_PREFIX)
                || !w.getVoucherUrl().endsWith(VOUCHER_URL_SUFFIX)) {
            throw new BusinessException(404, "该提现单暂无打款凭证");
        }
        String no = w.getVoucherUrl().substring(VOUCHER_URL_PREFIX.length(),
                w.getVoucherUrl().length() - VOUCHER_URL_SUFFIX.length());
        return attachmentService.loadContentForAdmin(no, thumbnail);
    }

    /**
     * 提现单号：WD + yyyyMMdd + 4 位序列（SequenceService 原子取号，跨实例并发安全）。
     */
    private String nextWithdrawNo() {
        String day = LocalDate.now().format(DAY);
        return "WD" + day + sequenceService.nextNo("withdraw:" + day, 4);
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 展示与快照余额不为负：理论并发窗口外的一致性抖动由 fail-closed 兜底为 0。 */
    private static BigDecimal maxZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO.setScale(2) : v;
    }
}
