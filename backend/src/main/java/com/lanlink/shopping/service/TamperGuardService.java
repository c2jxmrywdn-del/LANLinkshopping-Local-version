package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.TamperAlert;
import com.lanlink.shopping.mapper.TamperAlertMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 敏感字段篡改检测与告警（需求 1+4）。
 *
 * 后端在重算价格/金额/库存后，与客户端传入值比对；不一致时记录篡改告警并拒绝请求，
 * 便于追溯恶意攻击行为。所有告警落库 t_tamper_alert + 结构化日志 [tamper-alert]。
 */
@Service
public class TamperGuardService {

    private static final Logger log = LoggerFactory.getLogger(TamperGuardService.class);

    private final TamperAlertMapper mapper;
    private final AuditService auditService;

    public TamperGuardService(TamperAlertMapper mapper, AuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 数值一致性比对：客户端传入值与后端重算值不一致时记录告警并返回 false。
     *
     * @param bizType     业务类型（order/payment/product）
     * @param fieldName   敏感字段名（totalAmount/price/stock/payStatus...）
     * @param clientValue 客户端传入值（原始请求参数）
     * @param serverValue 后端重算/期望值
     */
    public boolean checkNumeric(Long userId, String bizType, String fieldName,
                                Number clientValue, Number serverValue,
                                HttpServletRequest request) {
        if (clientValue == null) return true; // 客户端未传该字段 → 以后端重算值为准，不算篡改
        if (serverValue == null) return true;
        boolean consistent = new java.math.BigDecimal(clientValue.toString())
                .compareTo(new java.math.BigDecimal(serverValue.toString())) == 0;
        if (!consistent) {
            report(userId, bizType, fieldName, clientValue.toString(), serverValue.toString(),
                    "客户端传入值与后端重算值不一致", request);
        }
        return consistent;
    }

    /** 状态字段合法性比对：客户端试图设置的状态与后端期望状态不一致时告警 */
    public boolean checkStatus(Long userId, String bizType, String fieldName,
                               String clientValue, String serverValue,
                               HttpServletRequest request) {
        if (clientValue == null) return true;
        boolean consistent = clientValue.equals(serverValue);
        if (!consistent) {
            report(userId, bizType, fieldName, clientValue, serverValue,
                    "客户端试图非法变更状态字段", request);
        }
        return consistent;
    }

    /** 非法业务操作直接告警（如越权改价、状态机非法流转、重复提交） */
    public void report(Long userId, String bizType, String fieldName,
                       String clientValue, String serverValue, String reason,
                       HttpServletRequest request) {
        TamperAlert alert = new TamperAlert();
        alert.setUserId(userId);
        alert.setBizType(bizType);
        alert.setFieldName(fieldName);
        alert.setClientValue(truncate(clientValue, 255));
        alert.setServerValue(truncate(serverValue, 255));
        alert.setReason(truncate(reason, 255));
        if (request != null) {
            String xff = request.getHeader("X-Forwarded-For");
            alert.setClientIp(xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr());
            alert.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        }
        alert.setCreateTime(LocalDateTime.now());
        mapper.insert(alert);
        log.warn("[tamper-alert] user={} biz={} field={} client={} server={} reason={}",
                userId, bizType, fieldName, clientValue, serverValue, reason);
        // 审计链留痕（需求 4：日志记录 + 告警可追溯）
        auditService.record(userId, "TAMPER_ALERT",
                bizType + "." + fieldName + " client=" + clientValue + " server=" + serverValue
                        + " reason=" + reason, request);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
