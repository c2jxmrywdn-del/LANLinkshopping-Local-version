package com.lanlink.shopping.payment.wallet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.wallet.entity.Wallet;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.mapper.WalletLogMapper;
import com.lanlink.shopping.payment.wallet.mapper.WalletMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包服务（参考 litemall 预充值钱包 / mall 账户模块二次设计）。
 * 安全与一致性约定：
 *  - 余额扣减一律走条件更新（balance >= amount），并发下不透支；
 *  - 每笔变动写流水（含变动后余额），支持对账与审计；
 *  - 金额校验：> 0、最多两位小数、单笔限额；
 *  - 关键节点/异常均记录 PAYMENT 日志（logger 独立命名）。
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger("PAYMENT");
    /** 单笔充值上限（元），防止演示环境误操作 */
    private static final BigDecimal MAX_RECHARGE = new BigDecimal("50000");
    private static final int MAX_LOG = 50;

    private final WalletMapper walletMapper;
    private final WalletLogMapper logMapper;

    public WalletService(WalletMapper walletMapper, WalletLogMapper logMapper) {
        this.walletMapper = walletMapper;
        this.logMapper = logMapper;
    }

    /** 获取或创建钱包（user_id 唯一） */
    @Transactional
    public Wallet getOrCreate(Long userId) {
        if (userId == null) throw new BusinessException("请先登录");
        Wallet w = walletMapper.selectOne(Wrappers.<Wallet>lambdaQuery().eq(Wallet::getUserId, userId));
        if (w == null) {
            w = new Wallet();
            w.setUserId(userId);
            w.setBalance(BigDecimal.ZERO);
            w.setUpdateTime(LocalDateTime.now());
            walletMapper.insert(w);
            log.info("[WALLET] 钱包初始化 userId={}", userId);
        }
        return w;
    }

    /** 余额查询（不创建钱包） */
    public BigDecimal balanceOf(Long userId) {
        if (userId == null) return BigDecimal.ZERO;
        Wallet w = walletMapper.selectOne(Wrappers.<Wallet>lambdaQuery().eq(Wallet::getUserId, userId));
        return w == null || w.getBalance() == null ? BigDecimal.ZERO : w.getBalance();
    }

    /** 我的钱包视图：余额 + 最近流水（对账入口） */
    public Map<String, Object> myView(Long userId) {
        Wallet w = getOrCreate(userId);
        List<WalletLog> logs = logMapper.selectList(Wrappers.<WalletLog>lambdaQuery()
                .eq(WalletLog::getUserId, userId)
                .orderByDesc(WalletLog::getId)
                .last("LIMIT " + MAX_LOG));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", w.getBalance());
        out.put("logs", logs);
        return out;
    }

    /**
     * 充值（模拟网关即时到账；真实渠道可扩展为充值单 + 渠道回调入账，见 PaymentService 渠道层）。
     */
    @Transactional
    public Wallet recharge(Long userId, BigDecimal amount, String remark) {
        checkAmount(amount);
        if (amount.compareTo(MAX_RECHARGE) > 0) {
            throw new BusinessException("单笔充值不可超过 " + MAX_RECHARGE.stripTrailingZeros().toPlainString() + " 元");
        }
        getOrCreate(userId);
        walletMapper.credit(userId, amount);
        Wallet after = getOrCreate(userId);
        writeLog(userId, "recharge", amount, after.getBalance(), null,
                remark == null || remark.isBlank() ? "钱包充值" : remark.trim());
        log.info("[WALLET] 充值成功 userId={} amount={} balance={}", userId, amount, after.getBalance());
        return after;
    }

    /**
     * 余额支付扣款：条件更新原子扣减，余额不足抛业务异常。
     * @return 钱包流水（含变动后余额，流水 ID 可作为渠道交易号）
     */
    @Transactional
    public WalletLog payFromWallet(Long userId, BigDecimal amount, String orderNo) {
        checkAmount(amount);
        getOrCreate(userId);
        int hit = walletMapper.deduct(userId, amount);
        if (hit == 0) {
            log.warn("[WALLET] 余额支付失败(余额不足) userId={} amount={} order={}", userId, amount, orderNo);
            throw new BusinessException("钱包余额不足，请充值或更换支付方式");
        }
        Wallet after = getOrCreate(userId);
        WalletLog wl = writeLog(userId, "pay", amount.negate(), after.getBalance(), orderNo, "订单余额支付");
        log.info("[WALLET] 余额支付成功 userId={} amount={} order={} balance={}", userId, amount, orderNo, after.getBalance());
        return wl;
    }

    /** 退款入账（订单退款时调用；幂等由调用方按订单退款状态保证） */
    @Transactional
    public WalletLog refundToWallet(Long userId, BigDecimal amount, String orderNo, String remark) {
        checkAmount(amount);
        getOrCreate(userId);
        walletMapper.credit(userId, amount);
        Wallet after = getOrCreate(userId);
        WalletLog wl = writeLog(userId, "refund", amount, after.getBalance(), orderNo,
                remark == null || remark.isBlank() ? "订单退款入账" : remark.trim());
        log.info("[WALLET] 退款入账 userId={} amount={} order={} balance={}", userId, amount, orderNo, after.getBalance());
        return wl;
    }

    private WalletLog writeLog(Long userId, String type, BigDecimal signedAmount,
                               BigDecimal balanceAfter, String orderNo, String remark) {
        WalletLog wl = new WalletLog();
        wl.setUserId(userId);
        wl.setChangeType(type);
        wl.setAmount(signedAmount);
        wl.setBalanceAfter(balanceAfter);
        wl.setRefOrderNo(orderNo);
        wl.setRemark(remark);
        wl.setCreateTime(LocalDateTime.now());
        logMapper.insert(wl);
        return wl;
    }

    private static void checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
        if (amount.scale() > 2) {
            throw new BusinessException("金额最多两位小数");
        }
    }
}
