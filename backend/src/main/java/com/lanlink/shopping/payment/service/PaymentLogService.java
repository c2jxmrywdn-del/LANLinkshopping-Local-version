package com.lanlink.shopping.payment.service;

import com.lanlink.shopping.payment.entity.PaymentLog;
import com.lanlink.shopping.payment.mapper.PaymentLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 支付日志：SLF4J 关键节点 + 落库审计。落库前对签名/密钥/openid 等做脱敏，
 * 保证日志与库中都不出现可直接复用的敏感原文。
 */
@Service
public class PaymentLogService {

    private static final Logger log = LoggerFactory.getLogger("PAYMENT");
    private static final int MAX_DETAIL = 1800;

    private final PaymentLogMapper mapper;

    public PaymentLogService(PaymentLogMapper mapper) {
        this.mapper = mapper;
    }

    public void info(String orderNo, String channel, String action, String direction, boolean success, String detail) {
        String safe = truncate(mask(detail));
        log.info("[PAYMENT] order={} channel={} action={} dir={} success={} detail={}", orderNo, channel, action, direction, success, safe);
        save(orderNo, channel, action, direction, success, safe);
    }

    public void error(String orderNo, String channel, String action, String detail, Throwable e) {
        String safe = truncate(mask(detail) + (e != null ? " | ex=" + e.getClass().getSimpleName() + ":" + e.getMessage() : ""));
        log.error("[PAYMENT] order={} channel={} action={} FAILED detail={}", orderNo, channel, action, safe);
        save(orderNo, channel, action, "ERROR", false, safe);
    }

    private void save(String orderNo, String channel, String action, String direction, boolean success, String detail) {
        try {
            PaymentLog pl = new PaymentLog();
            pl.setOrderNo(orderNo);
            pl.setChannel(channel);
            pl.setAction(action);
            pl.setDirection(direction);
            pl.setSuccess(success ? 1 : 0);
            pl.setDetail(detail);
            pl.setCreateTime(LocalDateTime.now());
            mapper.insert(pl);
        } catch (Exception ignore) {
            // 日志落库失败不影响主流程
            log.warn("支付日志落库失败 order={} action={}", orderNo, action);
        }
    }

    /** 脱敏：抹掉签名、密钥、openid、银行卡等敏感字段值（支持 JSON/XML/key=value 三种形态） */
    public static String mask(String s) {
        if (s == null) return "";
        String keys = "(?:sign|signType|api[_-]?key|private[_-]?key|app[_-]?secret|accessKeySecret"
                + "|openid|sub[_-]?openid|buyer[_-]?logon[_-]?id|buyer[_-]?id|bank[_-]?account|card[_-]?no)";
        // 1) JSON 双引号值： "key":"value"
        s = s.replaceAll("(?i)(\"" + keys + "\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
        // 2) XML 标签值： <key>value</key>
        s = s.replaceAll("(?i)(<(" + keys + ")>)[^<]*(</\\2>)", "$1***$3");
        // 3) 裸值： key=value 或 key: value
        s = s.replaceAll("(?i)(\\b" + keys + "\\b\\s*[=:]\\s*\"?)([^,&\"}\\s]+)", "$1***");
        return s;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_DETAIL ? s.substring(0, MAX_DETAIL) + "...(truncated)" : s;
    }
}
