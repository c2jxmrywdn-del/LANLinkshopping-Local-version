package com.lanlink.shopping.module.techservice.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 技术服务对外响应契约（DTO）。
 *
 * <p>实体不离开模块：Controller 只返回本类的 record。字段与重构前实体序列化结果逐一对应，
 * 保证外部 API 字段名、类型与空值语义不变；新增字段属于契约变更，需要评审。
 */
public final class TechViews {

    private TechViews() {
    }

    public record Demand(Long demandId, String demandNo, Long userId, String title, String industry,
                         String techField, String description, String painPoints, String expectedOutcome,
                         BigDecimal budgetMin, BigDecimal budgetMax, LocalDate deadline, Integer anonymous,
                         String status, Integer viewCount, Integer matchCount,
                         LocalDateTime createTime, LocalDateTime updateTime, Integer deleted) {
    }

    public record Consultation(Long consultId, Long expertId, Long userId, Long projectId, String topic,
                               String description, String mode, LocalDateTime scheduledAt, Integer durationMin,
                               BigDecimal amount, String status, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Appraisal(Long appraisalId, String appraisalNo, Long userId, Long achievementId, Long projectId,
                            Long institutionId, String standardCode, Integer techScore, Integer economicScore,
                            Integer legalScore, Integer maturityScore, String conclusionLevel, String reportNo,
                            String status, String disclaimer, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Hosting(Long hostingId, String hostingNo, Long userId, Long projectId, String hostingType,
                          Long targetId, String targetName, Long providerOrgId, String agreementNo, String scope,
                          LocalDate startDate, LocalDate endDate, String feeMode, BigDecimal feeAmount,
                          BigDecimal commissionRate, String status, String activationPlan,
                          LocalDateTime lastServiceAt, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Deal(Long dealId, String dealNo, Long projectId, Long achievementId, Long buyerUserId,
                       Long sellerUserId, String dealType, BigDecimal amount, BigDecimal commissionRate,
                       BigDecimal commissionAmount, String paymentMode, String status, String contractNo,
                       String evidenceHash, String escrowRef, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Expert(Long expertId, Long userId, String expertType, String name, String avatarUrl, String title,
                         String organization, String industries, String domains, String bio, String credentials,
                         Integer verified, String inviteStatus, BigDecimal rating, Integer serviceCount,
                         BigDecimal consultFee, Integer responseHours, Integer sort, Integer status,
                         LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Project(Long projectId, String projectNo, Long userId, String projectType, String title,
                          String stage, String status, Integer progress, Long ownerExpertId, Long brokerId,
                          BigDecimal amount, BigDecimal commissionRate, String sourceType, Long sourceId,
                          String nextAction, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Milestone(Long milestoneId, Long projectId, Integer seq, String name, String description,
                            String status, LocalDate dueDate, LocalDateTime completedAt, String deliverable,
                            String note, LocalDateTime createTime) {
    }

    public record Achievement(Long achievementId, Long ownerUserId, String title, String achievementType,
                              String patentNo, String patentType, String legalStatus, String owners, String industry,
                              String techField, Integer maturityLevel, String summary, String highlights,
                              String applicationScenarios, String licensingMode, BigDecimal expectedPrice,
                              String priceMode, Integer dormant, Integer verified, String status,
                              Integer viewCount, LocalDateTime createTime, LocalDateTime updateTime, Integer deleted) {
    }

    public record ServiceProduct(Long serviceId, String providerType, Long providerId, String category, String title,
                                 String subtitle, String description, String deliverables, String priceMode,
                                 BigDecimal price, BigDecimal commissionRate, Integer durationDays, String tags,
                                 String icon, String highlight, Integer sort, Integer status,
                                 LocalDateTime createTime) {
    }

    public record Policy(Long policyId, String title, String issuer, String docNo, String region, String category,
                         LocalDate publishDate, String summary, String url, String highlight, Integer sort,
                         Integer status, LocalDateTime createTime) {
    }

    public record Organization(Long orgId, String name, String orgType, String licenseNo, String qualifications,
                               String logoText, String region, String intro, String website, Integer verified,
                               BigDecimal rating, String partnershipStatus, Integer sort, Integer status,
                               LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Post(Long postId, Long userId, String postType, String title, String content, String tags,
                       String industry, String authorName, String authorTitle, Integer viewCount, Integer likeCount,
                       Integer commentCount, Integer status, LocalDateTime createTime, LocalDateTime updateTime) {
    }

    // ---------- 映射 ----------

    /**
     * 公开需求视图（**默认安全**）。两处最小化：
     *  - anonymous=1 时不回传 userId，否则「匿名发布」只是前端文案，任何人都能反查发布者账号；
     *  - 逻辑删除标记属内部存储细节，不外泄。
     * 后台与发布者本人用 {@link #ofDemandFull}。
     *
     * <p>命名约定：`ofXxx` 一律是可以直接吐给匿名访问者的最小视图，`ofXxxFull` 才含内部字段。
     * 之前反过来命名，并行重写控制器时两次把公开端点写回全字段变体（编译与测试都不会报警）；
     * 反转之后，顺手写 `ofDemand` 得到的是安全版本。
     */
    public static Demand ofDemand(TechDemand e) {
        boolean anonymous = Integer.valueOf(1).equals(e.getAnonymous());
        return new Demand(e.getDemandId(), e.getDemandNo(), anonymous ? null : e.getUserId(), e.getTitle(),
                e.getIndustry(), e.getTechField(), e.getDescription(), e.getPainPoints(), e.getExpectedOutcome(),
                e.getBudgetMin(), e.getBudgetMax(), e.getDeadline(), e.getAnonymous(), e.getStatus(),
                e.getViewCount(), e.getMatchCount(), e.getCreateTime(), e.getUpdateTime(), null);
    }

    public static Demand ofDemandFull(TechDemand e) {
        return new Demand(e.getDemandId(), e.getDemandNo(), e.getUserId(), e.getTitle(), e.getIndustry(),
                e.getTechField(), e.getDescription(), e.getPainPoints(), e.getExpectedOutcome(),
                e.getBudgetMin(), e.getBudgetMax(), e.getDeadline(), e.getAnonymous(), e.getStatus(),
                e.getViewCount(), e.getMatchCount(), e.getCreateTime(), e.getUpdateTime(), e.getDeleted());
    }

    public static Consultation ofConsultation(TechConsultation e) {
        return new Consultation(e.getConsultId(), e.getExpertId(), e.getUserId(), e.getProjectId(), e.getTopic(),
                e.getDescription(), e.getMode(), e.getScheduledAt(), e.getDurationMin(), e.getAmount(),
                e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Appraisal ofAppraisal(TechAppraisal e) {
        return new Appraisal(e.getAppraisalId(), e.getAppraisalNo(), e.getUserId(), e.getAchievementId(),
                e.getProjectId(), e.getInstitutionId(), e.getStandardCode(), e.getTechScore(), e.getEconomicScore(),
                e.getLegalScore(), e.getMaturityScore(), e.getConclusionLevel(), e.getReportNo(), e.getStatus(),
                e.getDisclaimer(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Hosting ofHosting(TechHosting e) {
        return new Hosting(e.getHostingId(), e.getHostingNo(), e.getUserId(), e.getProjectId(), e.getHostingType(),
                e.getTargetId(), e.getTargetName(), e.getProviderOrgId(), e.getAgreementNo(), e.getScope(),
                e.getStartDate(), e.getEndDate(), e.getFeeMode(), e.getFeeAmount(), e.getCommissionRate(),
                e.getStatus(), e.getActivationPlan(), e.getLastServiceAt(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Deal ofDeal(TechDeal e) {
        return new Deal(e.getDealId(), e.getDealNo(), e.getProjectId(), e.getAchievementId(), e.getBuyerUserId(),
                e.getSellerUserId(), e.getDealType(), e.getAmount(), e.getCommissionRate(), e.getCommissionAmount(),
                e.getPaymentMode(), e.getStatus(), e.getContractNo(), e.getEvidenceHash(), e.getEscrowRef(),
                e.getCreateTime(), e.getUpdateTime());
    }

    /** 公开专家视图（**默认安全**）：内部账号标识不外传，避免游客枚举平台用户与发布者。 */
    public static Expert ofExpert(TechExpert e) {
        return new Expert(e.getExpertId(), null, e.getExpertType(), e.getName(), e.getAvatarUrl(),
                e.getTitle(), e.getOrganization(), e.getIndustries(), e.getDomains(), e.getBio(), e.getCredentials(),
                e.getVerified(), e.getInviteStatus(), e.getRating(), e.getServiceCount(), e.getConsultFee(),
                e.getResponseHours(), e.getSort(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }

    /** 后台与本人用完整专家视图 */
    public static Expert ofExpertFull(TechExpert e) {
        return new Expert(e.getExpertId(), e.getUserId(), e.getExpertType(), e.getName(), e.getAvatarUrl(),
                e.getTitle(), e.getOrganization(), e.getIndustries(), e.getDomains(), e.getBio(), e.getCredentials(),
                e.getVerified(), e.getInviteStatus(), e.getRating(), e.getServiceCount(), e.getConsultFee(),
                e.getResponseHours(), e.getSort(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Project ofProject(TechProject e) {
        return new Project(e.getProjectId(), e.getProjectNo(), e.getUserId(), e.getProjectType(), e.getTitle(),
                e.getStage(), e.getStatus(), e.getProgress(), e.getOwnerExpertId(), e.getBrokerId(), e.getAmount(),
                e.getCommissionRate(), e.getSourceType(), e.getSourceId(), e.getNextAction(),
                e.getCreateTime(), e.getUpdateTime());
    }

    /** 供 Controller 转换服务层 Map 中的对象，避免 Controller 直接引用实体类型 */
    public static Project projectOf(Object raw) {
        return ofProject((TechProject) raw);
    }

    public static Milestone ofMilestone(TechMilestone e) {
        return new Milestone(e.getMilestoneId(), e.getProjectId(), e.getSeq(), e.getName(), e.getDescription(),
                e.getStatus(), e.getDueDate(), e.getCompletedAt(), e.getDeliverable(), e.getNote(),
                e.getCreateTime());
    }

    /** 公开成果视图（**默认安全**）：权属人账号标识与逻辑删除标记不外泄。 */
    public static Achievement ofAchievement(TechAchievement e) {
        return new Achievement(e.getAchievementId(), null, e.getTitle(), e.getAchievementType(),
                e.getPatentNo(), e.getPatentType(), e.getLegalStatus(), e.getOwners(), e.getIndustry(),
                e.getTechField(), e.getMaturityLevel(), e.getSummary(), e.getHighlights(),
                e.getApplicationScenarios(), e.getLicensingMode(), e.getExpectedPrice(), e.getPriceMode(),
                e.getDormant(), e.getVerified(), e.getStatus(), e.getViewCount(), e.getCreateTime(),
                e.getUpdateTime(), null);
    }

    /** 本人成果列表用完整视图（需要 ownerUserId 判断归属） */
    public static Achievement ofAchievementFull(TechAchievement e) {
        return new Achievement(e.getAchievementId(), e.getOwnerUserId(), e.getTitle(), e.getAchievementType(),
                e.getPatentNo(), e.getPatentType(), e.getLegalStatus(), e.getOwners(), e.getIndustry(),
                e.getTechField(), e.getMaturityLevel(), e.getSummary(), e.getHighlights(),
                e.getApplicationScenarios(), e.getLicensingMode(), e.getExpectedPrice(), e.getPriceMode(),
                e.getDormant(), e.getVerified(), e.getStatus(), e.getViewCount(), e.getCreateTime(),
                e.getUpdateTime(), e.getDeleted());
    }

    public static ServiceProduct ofServiceProduct(TechServiceProduct e) {
        return new ServiceProduct(e.getServiceId(), e.getProviderType(), e.getProviderId(), e.getCategory(),
                e.getTitle(), e.getSubtitle(), e.getDescription(), e.getDeliverables(), e.getPriceMode(),
                e.getPrice(), e.getCommissionRate(), e.getDurationDays(), e.getTags(), e.getIcon(),
                e.getHighlight(), e.getSort(), e.getStatus(), e.getCreateTime());
    }

    public static Policy ofPolicy(TechPolicy e) {
        return new Policy(e.getPolicyId(), e.getTitle(), e.getIssuer(), e.getDocNo(), e.getRegion(),
                e.getCategory(), e.getPublishDate(), e.getSummary(), e.getUrl(), e.getHighlight(), e.getSort(),
                e.getStatus(), e.getCreateTime());
    }

    /** 公开机构视图（**默认安全**）：执业许可证号是平台核验材料，不对匿名访问者全文出示；运营内部字段不外泄。 */
    public static Organization ofOrganization(TechOrganization e) {
        return new Organization(e.getOrgId(), e.getName(), e.getOrgType(), maskLicense(e.getLicenseNo()),
                e.getQualifications(), e.getLogoText(), e.getRegion(), e.getIntro(), e.getWebsite(),
                e.getVerified(), e.getRating(), e.getPartnershipStatus(), null, null,
                e.getCreateTime(), e.getUpdateTime());
    }

    /** 后台用完整机构视图 */
    public static Organization ofOrganizationFull(TechOrganization e) {
        return new Organization(e.getOrgId(), e.getName(), e.getOrgType(), e.getLicenseNo(), e.getQualifications(),
                e.getLogoText(), e.getRegion(), e.getIntro(), e.getWebsite(), e.getVerified(), e.getRating(),
                e.getPartnershipStatus(), e.getSort(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }

    public static List<Organization> ofOrganizations(List<TechOrganization> list) {
        return list.stream().map(TechViews::ofOrganization).toList();
    }

    /** 许可证号保留前 4 后 2，中间掩码；空值与超短值原样返回，避免把「无」渲染成「已脱敏」 */
    private static String maskLicense(String licenseNo) {
        if (licenseNo == null || licenseNo.isBlank()) {
            return licenseNo;
        }
        String value = licenseNo.trim();
        if (value.length() <= 6) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 2);
    }

    public static Post ofPost(TechPost e) {
        return new Post(e.getPostId(), e.getUserId(), e.getPostType(), e.getTitle(), e.getContent(), e.getTags(),
                e.getIndustry(), e.getAuthorName(), e.getAuthorTitle(), e.getViewCount(), e.getLikeCount(),
                e.getCommentCount(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }

    public static List<Demand> ofDemands(List<TechDemand> list) {
        return list.stream().map(TechViews::ofDemand).toList();
    }

    public static List<Demand> ofDemandsFull(List<TechDemand> list) {
        return list.stream().map(TechViews::ofDemandFull).toList();
    }

    public static List<Consultation> ofConsultations(List<TechConsultation> list) {
        return list.stream().map(TechViews::ofConsultation).toList();
    }

    public static List<Appraisal> ofAppraisals(List<TechAppraisal> list) {
        return list.stream().map(TechViews::ofAppraisal).toList();
    }

    public static List<Hosting> ofHostings(List<TechHosting> list) {
        return list.stream().map(TechViews::ofHosting).toList();
    }

    public static List<Deal> ofDeals(List<TechDeal> list) {
        return list.stream().map(TechViews::ofDeal).toList();
    }

    public static List<Project> ofProjects(List<TechProject> list) {
        return list.stream().map(TechViews::ofProject).toList();
    }

    public static List<Milestone> ofMilestones(List<TechMilestone> list) {
        return list.stream().map(TechViews::ofMilestone).toList();
    }

    public static List<Achievement> ofAchievements(List<TechAchievement> list) {
        return list.stream().map(TechViews::ofAchievement).toList();
    }

    public static List<Achievement> ofAchievementsFull(List<TechAchievement> list) {
        return list.stream().map(TechViews::ofAchievementFull).toList();
    }

    public static List<Post> ofPosts(List<TechPost> list) {
        return list.stream().map(TechViews::ofPost).toList();
    }

    /** 常用分页快捷转换（避免 Controller 直接引用实体泛型） */
    public static Map<String, Object> expertPage(Page<TechExpert> page) {
        return page(page, TechViews::ofExpert);
    }

    public static Map<String, Object> demandPage(Page<TechDemand> page) {
        return page(page, TechViews::ofDemand);
    }

    public static Map<String, Object> achievementPage(Page<TechAchievement> page) {
        return page(page, TechViews::ofAchievement);
    }

    public static Map<String, Object> postPage(Page<TechPost> page) {
        return page(page, TechViews::ofPost);
    }

    /** 公开首页聚合视图：精选列表全部转 DTO */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> overview(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", raw.get("stats"));
        out.put("experts", ((List<TechExpert>) raw.get("experts")).stream().map(TechViews::ofExpert).toList());
        out.put("demands", ofDemands((List<TechDemand>) raw.get("demands")));
        out.put("achievements", ofAchievements((List<TechAchievement>) raw.get("achievements")));
        out.put("services", ((List<TechServiceProduct>) raw.get("services")).stream()
                .map(TechViews::ofServiceProduct).toList());
        out.put("policies", ((List<TechPolicy>) raw.get("policies")).stream()
                .map(TechViews::ofPolicy).toList());
        out.put("organizations", ofOrganizations((List<TechOrganization>) raw.get("organizations")));
        return out;
    }

    /** 专家详情视图：expert / services / completedConsultations */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> expertDetail(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expert", ofExpert((TechExpert) raw.get("expert")));
        out.put("services", ((List<TechServiceProduct>) raw.get("services")).stream()
                .map(TechViews::ofServiceProduct).toList());
        out.put("completedConsultations", raw.get("completedConsultations"));
        return out;
    }

    /** 成果详情视图：achievement / assessment / services / institutions */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> achievementDetail(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("achievement", ofAchievement((TechAchievement) raw.get("achievement")));
        out.put("assessment", raw.get("assessment"));
        out.put("services", ((List<TechServiceProduct>) raw.get("services")).stream()
                .map(TechViews::ofServiceProduct).toList());
        out.put("institutions", ofOrganizations((List<TechOrganization>) raw.get("institutions")));
        return out;
    }

    /** 分页视图：保留 MyBatis-Plus Page 的既有字段（records/total/size/current/pages/orders/
     * optimizeCountSql/searchCount/countId/maxLimit），并额外提供 page 以兼容后台既有契约。
     */
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

    /** 陪跑项目详情视图：project / milestones / expert / broker */
    public static Map<String, Object> projectDetail(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", ofProject((TechProject) raw.get("project")));
        out.put("milestones", ofMilestones((List<TechMilestone>) raw.get("milestones")));
        if (raw.get("expert") instanceof TechExpert expert) {
            out.put("expert", ofExpert(expert));
        }
        if (raw.get("broker") instanceof TechExpert broker) {
            out.put("broker", ofExpert(broker));
        }
        return out;
    }

    /** 工作台视图：counts / recentProjects / recentDeals */
    public static Map<String, Object> workspace(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", raw.get("counts"));
        out.put("recentProjects", ofProjects((List<TechProject>) raw.get("recentProjects")));
        out.put("recentDeals", ofDeals((List<TechDeal>) raw.get("recentDeals")));
        return out;
    }

    /** 后台总览视图：counts / 待审专家 / 待受理需求 / 进行中项目 */
    public static Map<String, Object> adminOverview(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", raw.get("counts"));
        out.put("pendingExperts", ((List<TechExpert>) raw.get("pendingExperts")).stream()
                .map(TechViews::ofExpert).toList());
        out.put("pendingDemands", ofDemands((List<TechDemand>) raw.get("pendingDemands")));
        out.put("activeProjects", ofProjects((List<TechProject>) raw.get("activeProjects")));
        return out;
    }
}
