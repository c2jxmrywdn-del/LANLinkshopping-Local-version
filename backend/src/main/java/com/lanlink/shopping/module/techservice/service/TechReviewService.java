package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechReview;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import com.lanlink.shopping.module.techservice.mapper.TechReviewMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 履约评价（P1）：项目验收完成后，委托方对专家 / 技术经纪人 / 服务机构做五维评分。
 *
 * <p>评分只用于平台派单权重与机构分级，不对外发布排名，也不作为行政评价依据；
 * 同一项目、同一评价对象、同一评价人只允许提交一次（数据库唯一键兜底）。
 */
@Service
public class TechReviewService {

    private static final Set<String> TARGET_TYPES = Set.of("EXPERT", "BROKER", "ORG");
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final TechReviewMapper reviewMapper;
    private final TechProjectMapper projectMapper;
    private final TechExpertMapper expertMapper;
    private final TechOrganizationMapper organizationMapper;

    public TechReviewService(TechReviewMapper reviewMapper,
                             TechProjectMapper projectMapper,
                             TechExpertMapper expertMapper,
                             TechOrganizationMapper organizationMapper) {
        this.reviewMapper = reviewMapper;
        this.projectMapper = projectMapper;
        this.expertMapper = expertMapper;
        this.organizationMapper = organizationMapper;
    }

    @Transactional
    public TechReview submit(Long userId, Long projectId, TechP1Requests.ReviewCreate req) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (!Objects.equals(project.getUserId(), userId)) {
            throw new BusinessException(403, "无权评价该项目");
        }
        if (!"COMPLETED".equals(project.getStatus())) {
            throw new BusinessException(400, "项目验收完成后才能提交履约评价");
        }
        String targetType = text(req == null ? null : req.targetType());
        targetType = targetType == null ? "" : targetType.toUpperCase();
        if (!TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(400, "评价对象类型只能是 EXPERT / BROKER / ORG");
        }
        Long targetId = req.targetId();
        validateTarget(targetType, targetId);

        int response = score(req.scoreResponse(), "响应速度");
        int professional = score(req.scoreProfessional(), "专业能力");
        int delivery = score(req.scoreDelivery(), "交付质量");
        int communication = score(req.scoreCommunication(), "沟通协作");
        int result = score(req.scoreResult(), "结果达成");

        Long exists = reviewMapper.selectCount(Wrappers.<TechReview>lambdaQuery()
                .eq(TechReview::getProjectId, projectId)
                .eq(TechReview::getTargetType, targetType)
                .eq(TechReview::getTargetId, targetId)
                .eq(TechReview::getReviewerUserId, userId));
        if (exists != null && exists > 0) {
            throw new BusinessException(400, "该评价对象已评价过，请勿重复提交");
        }

        TechReview review = new TechReview();
        review.setReviewNo(TechNoGenerator.next("TR"));
        review.setTargetType(targetType);
        review.setTargetId(targetId);
        review.setProjectId(projectId);
        review.setReviewerUserId(userId);
        review.setScoreResponse(response);
        review.setScoreProfessional(professional);
        review.setScoreDelivery(delivery);
        review.setScoreCommunication(communication);
        review.setScoreResult(result);
        review.setOverall(average(response, professional, delivery, communication, result));
        String comment = text(req.comment());
        if (comment != null && comment.length() > 500) {
            throw new BusinessException(400, "评价内容不能超过 500 字");
        }
        review.setComment(comment);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(400, "该评价对象已评价过，请勿重复提交");
        }
        recomputeRating(targetType, targetId);
        review.setTargetName(targetName(targetType, targetId));
        review.setProjectTitle(project.getTitle());
        return review;
    }

    /** 我的项目评价（前台用于判断哪些对象已评价） */
    public List<TechReview> listMineByProject(Long userId, Long projectId) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (!Objects.equals(project.getUserId(), userId)) {
            throw new BusinessException(403, "无权查看该项目评价");
        }
        List<TechReview> list = reviewMapper.selectList(Wrappers.<TechReview>lambdaQuery()
                .eq(TechReview::getProjectId, projectId)
                .eq(TechReview::getReviewerUserId, userId)
                .orderByDesc(TechReview::getCreateTime));
        enrich(list);
        return list;
    }

    public Page<TechReview> adminPage(long page, long size, String targetType) {
        String type = text(targetType);
        Page<TechReview> result = reviewMapper.selectPage(
                TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE),
                Wrappers.<TechReview>lambdaQuery()
                        .eq(type != null, TechReview::getTargetType, type)
                        .orderByDesc(TechReview::getCreateTime));
        enrich(result.getRecords());
        return result;
    }

    private void validateTarget(String targetType, Long targetId) {
        if (targetId == null) {
            throw new BusinessException(400, "请选择评价对象");
        }
        if ("ORG".equals(targetType)) {
            if (organizationMapper.selectById(targetId) == null) {
                throw new BusinessException(404, "服务机构不存在");
            }
            return;
        }
        TechExpert expert = expertMapper.selectById(targetId);
        if (expert == null) {
            throw new BusinessException(404, "专家 / 经纪人不存在");
        }
        if (!targetType.equals(expert.getExpertType())) {
            throw new BusinessException(400, "评价对象类型与人员身份不匹配");
        }
    }

    private void recomputeRating(String targetType, Long targetId) {
        List<TechReview> reviews = reviewMapper.selectList(Wrappers.<TechReview>lambdaQuery()
                .eq(TechReview::getTargetType, targetType)
                .eq(TechReview::getTargetId, targetId));
        if (reviews.isEmpty()) return;
        BigDecimal total = reviews.stream().map(TechReview::getOverall)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = total.divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP);
        if ("ORG".equals(targetType)) {
            TechOrganization org = organizationMapper.selectById(targetId);
            if (org != null) {
                org.setRating(avg);
                organizationMapper.updateById(org);
            }
            return;
        }
        TechExpert expert = expertMapper.selectById(targetId);
        if (expert != null) {
            expert.setRating(avg);
            expertMapper.updateById(expert);
        }
    }

    private void enrich(List<TechReview> reviews) {
        if (reviews.isEmpty()) return;
        List<Long> expertIds = reviews.stream()
                .filter(r -> !"ORG".equals(r.getTargetType()))
                .map(TechReview::getTargetId).distinct().toList();
        List<Long> orgIds = reviews.stream()
                .filter(r -> "ORG".equals(r.getTargetType()))
                .map(TechReview::getTargetId).distinct().toList();
        List<Long> projectIds = reviews.stream().map(TechReview::getProjectId)
                .filter(Objects::nonNull).distinct().toList();

        Map<Long, TechExpert> experts = batch(expertIds, expertMapper::selectBatchIds, TechExpert::getExpertId);
        Map<Long, TechOrganization> orgs = batch(orgIds, organizationMapper::selectBatchIds,
                TechOrganization::getOrgId);
        Map<Long, TechProject> projects = batch(projectIds, projectMapper::selectBatchIds, TechProject::getProjectId);

        for (TechReview review : reviews) {
            if ("ORG".equals(review.getTargetType())) {
                review.setTargetName(orgs.containsKey(review.getTargetId())
                        ? orgs.get(review.getTargetId()).getName() : "机构 #" + review.getTargetId());
            } else {
                review.setTargetName(experts.containsKey(review.getTargetId())
                        ? experts.get(review.getTargetId()).getName() : "专家 #" + review.getTargetId());
            }
            TechProject project = projects.get(review.getProjectId());
            review.setProjectTitle(project == null ? null : project.getTitle());
        }
    }

    private String targetName(String targetType, Long targetId) {
        if ("ORG".equals(targetType)) {
            TechOrganization org = organizationMapper.selectById(targetId);
            return org == null ? "机构 #" + targetId : org.getName();
        }
        TechExpert expert = expertMapper.selectById(targetId);
        return expert == null ? "专家 #" + targetId : expert.getName();
    }

    private static <T> Map<Long, T> batch(List<Long> ids,
                                          Function<List<Long>, List<T>> loader,
                                          Function<T, Long> key) {
        if (ids.isEmpty()) return Map.of();
        return loader.apply(ids).stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }

    private static int score(Integer value, String label) {
        if (value == null || value < MIN_SCORE || value > MAX_SCORE) {
            throw new BusinessException(400, label + "评分需在 1-5 之间");
        }
        return value;
    }

    private static BigDecimal average(int... scores) {
        int sum = 0;
        for (int score : scores) sum += score;
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(scores.length), 2, RoundingMode.HALF_UP);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
