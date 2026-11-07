package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.dto.TechConsultationCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechConsultationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 专家咨询聚合：预约、我的咨询；预约成功后创建陪跑项目。 */
@Service
public class TechConsultationService {

    private static final Set<String> CONSULT_MODES = Set.of("VIDEO", "PHONE", "OFFLINE");

    private final TechConsultationMapper consultationMapper;
    private final TechExpertMapper expertMapper;
    private final TechProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    public TechConsultationService(TechConsultationMapper consultationMapper,
                                   TechExpertMapper expertMapper,
                                   TechProjectService projectService,
                                   ApplicationEventPublisher eventPublisher) {
        this.consultationMapper = consultationMapper;
        this.expertMapper = expertMapper;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechConsultation createConsultation(Long userId, TechConsultationCreateRequest req) {
        TechExpert expert = expertMapper.selectById(req.getExpertId());
        if (expert == null || !Integer.valueOf(1).equals(expert.getStatus())) {
            throw new BusinessException(404, "专家或技术经纪人不存在");
        }
        String mode = req.getMode() == null || req.getMode().isBlank() ? "VIDEO" : req.getMode().trim();
        if (!CONSULT_MODES.contains(mode)) {
            throw new BusinessException(400, "不支持的咨询方式");
        }
        TechConsultation c = new TechConsultation();
        c.setExpertId(expert.getExpertId());
        c.setUserId(userId);
        c.setTopic(req.getTopic().trim());
        c.setDescription(req.getDescription());
        c.setMode(mode);
        c.setScheduledAt(req.getScheduledAt() == null
                ? LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0)
                : req.getScheduledAt());
        c.setDurationMin(req.getDurationMin() == null ? 60 : req.getDurationMin());
        c.setAmount(req.getAmount() == null ? expert.getConsultFee() : req.getAmount());
        c.setStatus("PENDING");
        consultationMapper.insert(c);

        var project = projectService.createProject(userId, "CONSULT", "专家咨询 · " + c.getTopic(), "MATCHING",
                "等待专家确认咨询时间", c.getAmount() == null ? BigDecimal.ZERO : c.getAmount(), BigDecimal.ZERO,
                "CONSULTATION", c.getConsultId(), expert.getExpertId(), null,
                new String[]{"需求确认", "专家匹配", "咨询执行", "结果交付"});
        c.setProjectId(project.getProjectId());
        consultationMapper.updateById(c);

        eventPublisher.publishEvent(new TechSideEffectEvent(userId, "咨询预约已提交",
                "已为您预约 " + expert.getName() + "，" + c.getScheduledAt()
                        + " 进行" + modeLabel(mode) + "咨询。陪跑项目 " + project.getProjectNo() + " 已创建。",
                String.valueOf(c.getConsultId())));
        return c;
    }

    public List<TechConsultation> myConsultations(Long userId) {
        return consultationMapper.selectList(Wrappers.<TechConsultation>lambdaQuery()
                .eq(TechConsultation::getUserId, userId).orderByDesc(TechConsultation::getCreateTime));
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "PHONE" -> "电话";
            case "OFFLINE" -> "线下";
            default -> "视频";
        };
    }
}
