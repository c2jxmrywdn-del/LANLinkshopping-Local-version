package com.lanlink.shopping.module.techservice.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechOrgTask;
import com.lanlink.shopping.module.techservice.entity.TechPilotBooking;
import com.lanlink.shopping.module.techservice.entity.TechPilotResource;
import com.lanlink.shopping.module.techservice.entity.TechReview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** P1 机构工作台 / 履约评价 / 中试资源 / 开放许可 的对外契约。 */
public final class TechP1Views {

    private TechP1Views() {
    }

    public record OrgTask(Long taskId, String taskNo, Long orgId, Long projectId, Long assigneeUserId,
                          String taskType, String title, String scope, String status, LocalDate dueDate,
                          String deliverableText, String deliverableHash, LocalDateTime submittedAt,
                          LocalDateTime reviewedAt, String reviewNote, Long createdBy,
                          LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Review(Long reviewId, String reviewNo, String targetType, Long targetId, String targetName,
                         Long projectId, String projectTitle, Long reviewerUserId,
                         Integer scoreResponse, Integer scoreProfessional, Integer scoreDelivery,
                         Integer scoreCommunication, Integer scoreResult, BigDecimal overall,
                         String comment, LocalDateTime createTime) {
    }

    public record PilotResource(Long resourceId, String name, String orgName, String region, String resourceType,
                                String capabilities, String equipment, String priceMode, BigDecimal price,
                                String contact, Integer status, Integer sort, LocalDateTime createTime) {
    }

    public record PilotBooking(Long bookingId, String bookingNo, Long resourceId, String resourceName,
                               Long userId, Long projectId, String requirement, LocalDate scheduledDate,
                               String status, String adminNote, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    /** 开放许可材料包：声明要点、许可意向书、计价说明、备案材料清单由平台提供模板，备案由合作机构代办。 */
    public record OpenLicenseKit(Long achievementId, String title, String patentNo, String patentType,
                                 String owners, String industry, String techField, String summary,
                                 String highlights, String applicationScenarios, String licensingMode,
                                 BigDecimal expectedPrice, String priceMode, Integer verified,
                                 List<String> documents, List<String> process, String complianceNote) {
    }

    public static OrgTask ofOrgTask(TechOrgTask e) {
        return new OrgTask(e.getTaskId(), e.getTaskNo(), e.getOrgId(), e.getProjectId(), e.getAssigneeUserId(),
                e.getTaskType(), e.getTitle(), e.getScope(), e.getStatus(), e.getDueDate(),
                e.getDeliverableText(), e.getDeliverableHash(), e.getSubmittedAt(), e.getReviewedAt(),
                e.getReviewNote(), e.getCreatedBy(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Review ofReview(TechReview e) {
        return new Review(e.getReviewId(), e.getReviewNo(), e.getTargetType(), e.getTargetId(),
                e.getTargetName(), e.getProjectId(), e.getProjectTitle(), e.getReviewerUserId(),
                e.getScoreResponse(), e.getScoreProfessional(), e.getScoreDelivery(),
                e.getScoreCommunication(), e.getScoreResult(), e.getOverall(), e.getComment(),
                e.getCreateTime());
    }

    public static PilotResource ofPilotResource(TechPilotResource e) {
        return new PilotResource(e.getResourceId(), e.getName(), e.getOrgName(), e.getRegion(),
                e.getResourceType(), e.getCapabilities(), e.getEquipment(), e.getPriceMode(), e.getPrice(),
                e.getContact(), e.getStatus(), e.getSort(), e.getCreateTime());
    }

    public static PilotBooking ofPilotBooking(TechPilotBooking e) {
        return new PilotBooking(e.getBookingId(), e.getBookingNo(), e.getResourceId(), e.getResourceName(),
                e.getUserId(), e.getProjectId(), e.getRequirement(), e.getScheduledDate(), e.getStatus(),
                e.getAdminNote(), e.getCreateTime(), e.getUpdateTime());
    }

    public static OpenLicenseKit ofOpenLicenseKit(TechAchievement a) {
        return new OpenLicenseKit(a.getAchievementId(), a.getTitle(), a.getPatentNo(), a.getPatentType(),
                a.getOwners(), a.getIndustry(), a.getTechField(), a.getSummary(), a.getHighlights(),
                a.getApplicationScenarios(), a.getLicensingMode(), a.getExpectedPrice(), a.getPriceMode(),
                a.getVerified(), OPEN_LICENSE_DOCUMENTS, OPEN_LICENSE_PROCESS, OPEN_LICENSE_NOTE);
    }

    /** 材料包清单：平台提供模板与填写指引，不代替行政机关出具任何证明。 */
    public static final List<String> OPEN_LICENSE_DOCUMENTS = List.of(
            "专利开放许可声明（要点模板）",
            "许可意向书（被许可方填写）",
            "许可费计价说明（一次总付 / 提成 / 入门费+提成）",
            "实施情况与改进说明（年度更新）",
            "备案材料清单（由合作机构核验代办）");

    public static final List<String> OPEN_LICENSE_PROCESS = List.of(
            "权利人声明开放许可条件与许可费标准",
            "平台专区公示，意向方在线提交实施计划",
            "双方确认许可范围、期限、地域与费用",
            "签署许可合同并完成平台存证",
            "合作机构代办备案，平台跟踪实施反馈");

    public static final String OPEN_LICENSE_NOTE =
            "平台只做信息公示、材料模板与流程陪跑；开放许可备案由具备资质的知识产权服务机构代办，"
                    + "平台不出具备案证明，也不承诺许可成交结果。";

    public static <E, V> Map<String, Object> page(Page<E> page, Function<E, V> mapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", page.getRecords().stream().map(mapper).toList());
        out.put("total", page.getTotal());
        out.put("size", page.getSize());
        out.put("current", page.getCurrent());
        out.put("pages", page.getPages());
        out.put("orders", page.orders());
        out.put("optimizeCountSql", page.optimizeCountSql());
        out.put("searchCount", page.searchCount());
        out.put("countId", page.countId());
        out.put("maxLimit", page.maxLimit());
        out.put("page", page.getCurrent());
        return out;
    }
}
