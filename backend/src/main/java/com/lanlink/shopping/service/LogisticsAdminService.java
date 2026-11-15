package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.dto.LogisticsProviderAdminDTO;
import com.lanlink.shopping.dto.LogisticsTemplateAdminDTO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 物流管理服务：服务商管理、运费模板管理、异常告警处理、物流售后处理。
 */
@Service
public class LogisticsAdminService {

    private static final DateTimeFormatter AFTER_SALE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /* ==================== 服务商管理（DTO 层，capabilityJson 读写打通） ==================== */

    public List<LogisticsProviderAdminDTO.ProviderVO> providerVOs() {
        return providers().stream().map(this::toProviderVO).toList();
    }

    public LogisticsProviderAdminDTO.ProviderVO toProviderVO(LogisticsProvider p) {
        LogisticsProviderAdminDTO.ProviderVO vo = new LogisticsProviderAdminDTO.ProviderVO();
        vo.setProviderId(p.getProviderId());
        vo.setProviderName(p.getProviderName());
        vo.setProviderCode(p.getProviderCode());
        vo.setContactName(p.getContactName());
        vo.setContactPhone(p.getContactPhone());
        vo.setApiType(p.getApiType());
        vo.setApiEndpoint(p.getApiEndpoint());
        vo.setCapabilityJson(p.getCapabilityJson());
        vo.setStatus(p.getStatus());
        vo.setSettleCycle(p.getSettleCycle());
        vo.setAvgEtaHours(p.getAvgEtaHours());
        vo.setServiceScore(p.getServiceScore());
        vo.setSlaRate(p.getSlaRate());
        vo.setSort(p.getSort());
        return vo;
    }

    public LogisticsProviderAdminDTO.ProviderVO saveProviderDTO(LogisticsProviderAdminDTO.ProviderSaveRequest req,
                                                                Long operatorId,
                                                                jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(req.getProviderId());
        p.setProviderName(req.getProviderName());
        p.setProviderCode(req.getProviderCode());
        p.setContactName(req.getContactName());
        p.setContactPhone(req.getContactPhone());
        p.setApiType(req.getApiType());
        p.setApiEndpoint(req.getApiEndpoint());
        p.setCapabilityJson(req.getCapabilityJson());
        p.setApiKey(req.getApiKey());
        p.setStatus(req.getStatus());
        p.setSettleCycle(req.getSettleCycle());
        p.setAvgEtaHours(req.getAvgEtaHours());
        p.setServiceScore(req.getServiceScore());
        p.setSlaRate(req.getSlaRate());
        p.setSort(req.getSort());
        saveProvider(p, operatorId, httpReq);
        return toProviderVO(p);
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
        validateTemplatePricing(t);
        if (t.getTemplateId() == null) {
            templateMapper.insert(t);
            auditService.record(operatorId, "LOGISTICS_TEMPLATE_CREATE", "新增运费模板 " + t.getTemplateName(), httpReq);
        } else {
            templateMapper.updateById(t);
            auditService.record(operatorId, "LOGISTICS_TEMPLATE_UPDATE", "更新运费模板 " + t.getTemplateName(), httpReq);
        }
        return t;
    }

    public LogisticsTemplateAdminDTO.TemplateVO saveTemplateDTO(LogisticsProviderAdminDTO.TemplateSaveRequest req,
                                                                Long operatorId,
                                                                jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setTemplateId(req.getTemplateId());
        t.setTemplateName(req.getTemplateName());
        t.setProviderId(req.getProviderId());
        t.setPricingType(req.getPricingType());
        t.setTierMode(req.getTierMode());
        t.setFirstWeight(req.getFirstWeight());
        t.setFirstPrice(req.getFirstPrice());
        t.setContinuedWeight(req.getContinuedWeight());
        t.setContinuedPrice(req.getContinuedPrice());
        t.setVolumeFactor(req.getVolumeFactor());
        t.setFreeThreshold(req.getFreeThreshold());
        t.setTierRuleJson(req.getTierRuleJson());
        t.setVasRuleJson(req.getVasRuleJson());
        t.setMinCharge(req.getMinCharge());
        t.setRemoteSurcharge(req.getRemoteSurcharge());
        t.setStatus(req.getStatus());
        saveTemplate(t, operatorId, httpReq);
        return toTemplateVO(t);
    }

    private LogisticsTemplateAdminDTO.TemplateVO toTemplateVO(LogisticsTemplate t) {
        LogisticsTemplateAdminDTO.TemplateVO vo = new LogisticsTemplateAdminDTO.TemplateVO();
        vo.setTemplateId(t.getTemplateId());
        vo.setTemplateName(t.getTemplateName());
        vo.setProviderId(t.getProviderId());
        vo.setPricingType(t.getPricingType());
        vo.setTierMode(t.getTierMode());
        vo.setFirstWeight(t.getFirstWeight());
        vo.setFirstPrice(t.getFirstPrice());
        vo.setContinuedWeight(t.getContinuedWeight());
        vo.setContinuedPrice(t.getContinuedPrice());
        vo.setVolumeFactor(t.getVolumeFactor());
        vo.setFreeThreshold(t.getFreeThreshold());
        vo.setTierRuleJson(t.getTierRuleJson());
        vo.setVasRuleJson(t.getVasRuleJson());
        vo.setMinCharge(t.getMinCharge());
        vo.setRemoteSurcharge(t.getRemoteSurcharge());
        vo.setStatus(t.getStatus());
        return vo;
    }

    public List<LogisticsTemplateAdminDTO.TemplateVO> templateVOs(Long providerId) {
        return templates(providerId).stream().map(this::toTemplateVO).toList();
    }

    /**
     * 计价配置解析校验（out/carrier-expansion-design.md §3）：
     *  - tier_mode='tiered' 时 tier_rule_json 必须可解析、区间连续覆盖 [0,∞)、min_charge>0
     *    （calculateTiered 对坏 JSON/区间外静默返 0，min_charge>0 是唯一兜底）；
     *  - vas_rule_json 存在时必须可解析为对象。
     */
    private void validateTemplatePricing(LogisticsTemplate t) {
        boolean tiered = "tiered".equalsIgnoreCase(t.getTierMode());
        if (tiered) {
            if (t.getTierRuleJson() == null || t.getTierRuleJson().isBlank()) {
                throw new BusinessException("阶梯模板必须配置计费区间 JSON");
            }
            List<Map<String, Object>> rules = parseTierRules(t.getTierRuleJson());
            if (rules.isEmpty()) {
                throw new BusinessException("计费区间 JSON 解析失败或为空数组");
            }
            double cursor = 0.0;
            for (Map<String, Object> rule : rules) {
                double min = toDouble(rule.get("minWeight"), Double.NaN);
                double max = toDouble(rule.get("maxWeight"), Double.NaN);
                if (Double.isNaN(min) || Double.isNaN(max) || min < cursor) {
                    throw new BusinessException("计费区间不连续：需按 minWeight 从 0 起无缝衔接");
                }
                cursor = max;
            }
            if (cursor < Double.MAX_VALUE && cursor <= 0) {
                throw new BusinessException("计费区间未覆盖大件重量段（末档 maxWeight 需大于 0）");
            }
            if (t.getMinCharge() == null || t.getMinCharge().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("阶梯模板必须配置最低起运价（>0），兜底区间外静默返 0");
            }
        }
        if (t.getVasRuleJson() != null && !t.getVasRuleJson().isBlank()) {
            try {
                JSON.readValue(t.getVasRuleJson(), new TypeReference<Map<String, Map<String, Object>>>() {});
            } catch (Exception e) {
                throw new BusinessException("增值服务费率 JSON 解析失败：" + e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> parseTierRules(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double toDouble(Object obj, double defaultVal) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj != null) {
            try {
                return Double.parseDouble(obj.toString());
            } catch (Exception ignored) {}
        }
        return defaultVal;
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
