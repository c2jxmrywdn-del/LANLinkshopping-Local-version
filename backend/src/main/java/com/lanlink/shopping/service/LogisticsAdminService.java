package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.entity.LogisticsAftersale;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.mapper.LogisticsAftersaleMapper;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 物流管理服务：服务商管理、运费模板管理、异常告警处理、物流售后处理。
 */
@Service
public class LogisticsAdminService {

    private static final DateTimeFormatter AFTER_SALE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LogisticsProviderMapper providerMapper;
    private final LogisticsTemplateMapper templateMapper;
    private final LogisticsAlertMapper alertMapper;
    private final LogisticsAftersaleMapper aftersaleMapper;
    private final AuditService auditService;
    private final SequenceService sequenceService;

    public LogisticsAdminService(LogisticsProviderMapper providerMapper,
                                 LogisticsTemplateMapper templateMapper,
                                 LogisticsAlertMapper alertMapper,
                                 LogisticsAftersaleMapper aftersaleMapper,
                                 AuditService auditService,
                                 SequenceService sequenceService) {
        this.providerMapper = providerMapper;
        this.templateMapper = templateMapper;
        this.alertMapper = alertMapper;
        this.aftersaleMapper = aftersaleMapper;
        this.auditService = auditService;
        this.sequenceService = sequenceService;
    }

    /* ==================== 服务商管理 ==================== */

    public List<LogisticsProvider> providers() {
        List<LogisticsProvider> list = providerMapper.selectList(new LambdaQueryWrapper<LogisticsProvider>()
                .orderByAsc(LogisticsProvider::getSort));
        for (LogisticsProvider p : list) {
            p.setApiKey(DesensitizeUtil.maskApiKey(p.getApiKey()));
        }
        return list;
    }

    public LogisticsProvider saveProvider(LogisticsProvider p, Long operatorId,
                                          jakarta.servlet.http.HttpServletRequest httpReq) {
        if (p.getProviderName() == null || p.getProviderName().isBlank()) {
            throw new BusinessException("服务商名称不能为空");
        }
        if (p.getProviderId() == null) {
            providerMapper.insert(p);
            auditService.record(operatorId, "LOGISTICS_PROVIDER_CREATE", "新增服务商 " + p.getProviderName(), httpReq);
        } else {
            // 逆向一致性保护：若前端提交带星号的脱敏密钥，保留数据库原有真实密钥
            if (p.getApiKey() != null && DesensitizeUtil.isMasked(p.getApiKey())) {
                LogisticsProvider existing = providerMapper.selectById(p.getProviderId());
                if (existing != null) {
                    p.setApiKey(existing.getApiKey());
                }
            }
            providerMapper.updateById(p);
            auditService.record(operatorId, "LOGISTICS_PROVIDER_UPDATE", "更新服务商 " + p.getProviderName(), httpReq);
        }
        return p;
    }

    /* ==================== 运费模板管理 ==================== */

    public List<LogisticsTemplate> templates(Long providerId) {
        LambdaQueryWrapper<LogisticsTemplate> qw = new LambdaQueryWrapper<LogisticsTemplate>()
                .orderByDesc(LogisticsTemplate::getTemplateId);
        if (providerId != null) qw.eq(LogisticsTemplate::getProviderId, providerId);
        return templateMapper.selectList(qw);
    }

    public LogisticsTemplate saveTemplate(LogisticsTemplate t, Long operatorId,
                                          jakarta.servlet.http.HttpServletRequest httpReq) {
        if (t.getTemplateName() == null || t.getTemplateName().isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }
        if (t.getProviderId() == null) throw new BusinessException("请选择归属物流服务商");
        if (t.getTemplateId() == null) {
            templateMapper.insert(t);
            auditService.record(operatorId, "LOGISTICS_TEMPLATE_CREATE", "新增运费模板 " + t.getTemplateName(), httpReq);
        } else {
            templateMapper.updateById(t);
            auditService.record(operatorId, "LOGISTICS_TEMPLATE_UPDATE", "更新运费模板 " + t.getTemplateName(), httpReq);
        }
        return t;
    }

    /* ==================== 异常告警处理 ==================== */

    public Page<LogisticsAlert> alerts(long page, long size, String status) {
        LambdaQueryWrapper<LogisticsAlert> qw = new LambdaQueryWrapper<LogisticsAlert>()
                .orderByDesc(LogisticsAlert::getAlertId);
        if (status != null && !status.isBlank()) qw.eq(LogisticsAlert::getStatus, status);
        return alertMapper.selectPage(new Page<>(page, size), qw);
    }

    @Transactional
    public void handleAlert(Long alertId, String handleNote, Long operatorId,
                            jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsAlert alert = alertMapper.selectById(alertId);
        if (alert == null) throw new BusinessException("告警不存在");
        if ("resolved".equals(alert.getStatus())) throw new BusinessException("告警已处理");
        alert.setStatus("resolved");
        alert.setHandledBy(operatorId);
        alert.setHandledAt(LocalDateTime.now());
        alert.setHandleNote(handleNote);
        alertMapper.updateById(alert);
        auditService.record(operatorId, "LOGISTICS_ALERT_RESOLVED", "处理告警 #" + alertId + " " + handleNote, httpReq);
    }

    /* ==================== 物流售后处理 ==================== */

    public Page<LogisticsAftersale> aftersales(long page, long size, String status) {
        LambdaQueryWrapper<LogisticsAftersale> qw = new LambdaQueryWrapper<LogisticsAftersale>()
                .orderByDesc(LogisticsAftersale::getAftersaleId);
        if (status != null && !status.isBlank()) qw.eq(LogisticsAftersale::getStatus, status);
        return aftersaleMapper.selectPage(new Page<>(page, size), qw);
    }

    @Transactional
    public LogisticsAftersale createAftersale(LogisticsAftersale a, Long operatorId,
                                              jakarta.servlet.http.HttpServletRequest httpReq) {
        if (a.getWaybillId() == null) throw new BusinessException("请选择关联运单");
        if (a.getAftersaleType() == null || a.getAftersaleType().isBlank()) {
            throw new BusinessException("请选择售后类型");
        }
        a.setAftersaleNo(nextAftersaleNo());
        a.setStatus("pending");
        aftersaleMapper.insert(a);
        auditService.record(operatorId, "LOGISTICS_AFTERSALE_CREATE",
                "创建物流售后 " + a.getAftersaleNo() + " 类型=" + a.getAftersaleType(), httpReq);
        return a;
    }

    @Transactional
    public LogisticsAftersale processAftersale(Long aftersaleId, String action, String note,
                                               Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsAftersale a = aftersaleMapper.selectById(aftersaleId);
        if (a == null) throw new BusinessException("售后单不存在");
        String next = switch (action) {
            case "accept" -> {
                if (!"pending".equals(a.getStatus())) throw new BusinessException("仅待处理可受理");
                yield "processing";
            }
            case "approve" -> {
                if (!"processing".equals(a.getStatus())) throw new BusinessException("仅处理中可通过");
                yield "approved";
            }
            case "reject" -> {
                if (!"processing".equals(a.getStatus())) throw new BusinessException("仅处理中可驳回");
                yield "rejected";
            }
            case "done" -> {
                if (!"approved".equals(a.getStatus())) throw new BusinessException("仅已通过可完结");
                yield "done";
            }
            default -> throw new BusinessException("非法售后操作: " + action);
        };
        a.setStatus(next);
        a.setProcessorId(operatorId);
        a.setProcessNote(note);
        a.setProcessTime(LocalDateTime.now());
        aftersaleMapper.updateById(a);
        auditService.record(operatorId, "LOGISTICS_AFTERSALE_" + action.toUpperCase(),
                "售后单 " + a.getAftersaleNo() + " 操作 " + action, httpReq);
        return a;
    }

    private String nextAftersaleNo() {
        String day = LocalDate.now().format(AFTER_SALE_DAY);
        // 序列服务原子取号（防并发同序号撞 uk_aftersale_no 唯一约束），键含日期天然按日重置
        return "LS" + day + sequenceService.nextNo("aftersale:" + day, 4);
    }
}
