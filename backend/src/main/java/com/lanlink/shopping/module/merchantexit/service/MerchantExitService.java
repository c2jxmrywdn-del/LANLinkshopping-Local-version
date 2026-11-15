package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateBlocker;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateReport;
import com.lanlink.shopping.module.merchantexit.entity.MerchantExitLog;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitLogMapper;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitMapper;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.MessageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 退店状态机（CAS：每条流转都把前置状态写进 UPDATE ... WHERE，返回 0 → 400）。
 *
 * <p>流程：requestExit → approve（六门禁）→ PUBLIC_NOTICE（公示 notice-days 天，可撤回）
 * → 到期 freezeDue（job/手动）→ close（六门禁 + 保证金结算）→ CLOSED（不可逆软关闭）。
 * 每一步写 exit_log + 审计；关键节点给商户发站内信。
 */
@Service
public class MerchantExitService {

    private final MerchantService merchantService;
    private final MerchantExitMapper exitMapper;
    private final MerchantExitLogMapper logMapper;
    private final MerchantExitGateService gateService;
    private final MerchantDepositService depositService;
    private final MerchantExitProperties props;
    private final AuditService auditService;
    private final MessageService messageService;

    public MerchantExitService(MerchantService merchantService, MerchantExitMapper exitMapper,
                               MerchantExitLogMapper logMapper, MerchantExitGateService gateService,
                               MerchantDepositService depositService, MerchantExitProperties props,
                               AuditService auditService, MessageService messageService) {
        this.merchantService = merchantService;
        this.exitMapper = exitMapper;
        this.logMapper = logMapper;
        this.gateService = gateService;
        this.depositService = depositService;
        this.props = props;
        this.auditService = auditService;
        this.messageService = messageService;
    }

    @Transactional
    public void requestExit(Long userId, String reason, HttpServletRequest req) {
        Merchant m = merchantService.getByUser(userId);
        if (m == null) {
            throw new BusinessException(400, "请先完成商户入驻");
        }
        if (m.getReviewStatus() == null || m.getReviewStatus() != 1) {
            throw new BusinessException(400, "商户资质未通过审核，暂不能申请退店");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "请填写退店原因");
        }
        if (exitMapper.requestExit(m.getMerId(), reason.trim()) == 0) {
            throw new BusinessException(400, "当前状态不可申请退店");
        }
        writeLog(m.getMerId(), null, "EXIT_REQUESTED", userId, "MERCHANT", reason.trim());
        auditService.record(userId, "MERCHANT_EXIT_REQUEST", "商户 #" + m.getMerId() + " 申请退店", req);
        messageService.send(m.getUserId(), "merchant", "退店申请已提交",
                "您的退店申请已提交，店铺经营类操作已冻结，等待平台审核。", null);
    }

    @Transactional
    public void approve(Long adminUserId, Long merId, HttpServletRequest req) {
        Merchant m = merchantService.requireMerchant(merId);
        requireExitRequested(m);
        requireGateClear(merId);
        if (exitMapper.approve(merId, props.getNoticeDays()) == 0) {
            throw new BusinessException(400, "状态已变化，请刷新后重试");
        }
        writeLog(merId, "EXIT_REQUESTED", "PUBLIC_NOTICE", adminUserId, "ADMIN", "审核通过，进入公示期");
        auditService.record(adminUserId, "MERCHANT_EXIT_APPROVE",
                "商户 #" + merId + " 退店审核通过，公示 " + props.getNoticeDays() + " 天", req);
        messageService.send(m.getUserId(), "merchant", "退店申请已通过",
                "您的退店申请已通过，进入 " + props.getNoticeDays() + " 天公示期，公示期内可撤回。", null);
    }

    @Transactional
    public void reject(Long adminUserId, Long merId, String reason, HttpServletRequest req) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "驳回必须填写原因");
        }
        Merchant m = merchantService.requireMerchant(merId);
        requireExitRequested(m);
        if (exitMapper.reject(merId, reason.trim()) == 0) {
            throw new BusinessException(400, "状态已变化，请刷新后重试");
        }
        writeLog(merId, "EXIT_REQUESTED", null, adminUserId, "ADMIN", reason.trim());
        auditService.record(adminUserId, "MERCHANT_EXIT_REJECT",
                "商户 #" + merId + " 退店申请被驳回：" + reason.trim(), req);
        messageService.send(m.getUserId(), "merchant", "退店申请未通过",
                "您的退店申请未通过：" + reason.trim() + "。如有疑问请联系平台客服。", null);
    }

    @Transactional
    public void withdraw(Long userId, HttpServletRequest req) {
        Merchant m = merchantService.getByUser(userId);
        if (m == null) {
            throw new BusinessException(400, "请先完成商户入驻");
        }
        if (!"PUBLIC_NOTICE".equals(m.getExitStatus())) {
            throw new BusinessException(400, "仅公示期内可撤回退店申请");
        }
        if (exitMapper.withdraw(m.getMerId()) == 0) {
            throw new BusinessException(400, "状态已变化，请刷新后重试");
        }
        writeLog(m.getMerId(), "PUBLIC_NOTICE", null, userId, "MERCHANT", "商户撤回退店申请");
        auditService.record(userId, "MERCHANT_EXIT_WITHDRAW", "商户 #" + m.getMerId() + " 撤回退店申请", req);
        messageService.send(m.getUserId(), "merchant", "退店申请已撤回", "您的退店申请已撤回，店铺恢复正常经营。", null);
    }

    /**
     * 正式关闭：FROZEN→CLOSED（不可逆）。先门禁、再保证金结算、最后 CAS——
     * 三者同事务，CAS 失败会回滚保证金流水。
     */
    @Transactional
    public void close(Long adminUserId, Long merId, BigDecimal deductAmount, String remark,
                      HttpServletRequest req) {
        Merchant m = merchantService.requireMerchant(merId);
        if (!"FROZEN".equals(m.getExitStatus())) {
            throw new BusinessException(400, "仅清算中店铺可正式关闭");
        }
        requireGateClear(merId);
        BigDecimal deduct = deductAmount == null ? BigDecimal.ZERO
                : deductAmount.setScale(2, RoundingMode.HALF_UP);
        if (deduct.signum() < 0) {
            throw new BusinessException(400, "抵扣金额不能为负");
        }
        BigDecimal balance = depositService.balanceOf(merId);
        if (deduct.signum() > 0) {
            if (deduct.compareTo(balance) > 0) {
                throw new BusinessException(400, "抵扣金额不能超过保证金余额");
            }
            depositService.deduct(merId, deduct, StringUtils.hasText(remark) ? remark.trim() : "闭店欠费抵扣");
        }
        BigDecimal remaining = depositService.balanceOf(merId);
        if (remaining.signum() > 0) {
            depositService.refund(merId, remaining, "闭店保证金退还（账面登记）");
        }
        if (exitMapper.close(merId) == 0) {
            throw new BusinessException(400, "状态已变化，请刷新后重试");
        }
        writeLog(merId, "FROZEN", "CLOSED", adminUserId, "ADMIN",
                "正式关闭，保证金抵扣 " + deduct.toPlainString() + " 元并退还余额");
        auditService.record(adminUserId, "MERCHANT_EXIT_CLOSE",
                "商户 #" + merId + " 正式闭店（抵扣 " + deduct.toPlainString() + "）", req);
        messageService.send(m.getUserId(), "merchant", "店铺已正式关闭",
                "您的店铺已完成退店流程。交易与财务记录将按法规留存，感谢您的经营。", null);
    }

    /** 公示期满自动转 FROZEN（幂等：CAS 未命中即已处理，返回 false）。 */
    @Transactional
    public boolean freezeDue(Long merId) {
        if (exitMapper.freezeDue(merId) == 0) {
            return false;
        }
        Merchant m = merchantService.requireMerchant(merId);
        writeLog(merId, "PUBLIC_NOTICE", "FROZEN", null, "SYSTEM", "公示期满自动转入清算");
        messageService.send(m.getUserId(), "merchant", "退店进入清算阶段",
                "您的店铺公示期已满，进入冻结清算阶段，请配合完成结算与保证金清算。", null);
        return true;
    }

    private void requireExitRequested(Merchant m) {
        if (!"EXIT_REQUESTED".equals(m.getExitStatus())) {
            throw new BusinessException(400, "仅退店申请中可执行该操作");
        }
    }

    private void requireGateClear(Long merId) {
        GateReport report = gateService.check(merId);
        if (!report.ready()) {
            GateBlocker first = report.blockers().get(0);
            throw new BusinessException(400, "退店门禁未通过：" + first.label()
                    + (first.count() > 0 ? "（" + first.count() + "）" : "") + "，" + first.hint());
        }
    }

    /** 回退到正常经营时库内状态为 NULL，但 to_status 列 NOT NULL，故日志落 ACTIVE 占位。 */
    private static final String ACTIVE = "ACTIVE";

    private void writeLog(Long merId, String from, String to, Long actorId, String actorRole, String detail) {
        MerchantExitLog log = new MerchantExitLog();
        log.setMerId(merId);
        log.setFromStatus(from == null ? ACTIVE : from);
        log.setToStatus(to == null ? ACTIVE : to);
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        logMapper.insert(log);
    }
}
