package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MerchantApplyDTO;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Qualification;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.QualificationMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户入驻与筛选服务
 * 筛选机制(对应思维导图):
 *   条件A 工商主体: 公司类型注册, 或 个体工商户且注册资本 > 10万元
 *   条件B 纳税记录: 有稳定纳税记录(taxStatus=1)
 *   A 且 B 满足 -> 审核通过(1); 否则驳回(2)并给出原因
 */
@Service
public class MerchantService {

    private static final BigDecimal MIN_CAPITAL = new BigDecimal("100000"); // 10万元

    private final MerchantMapper merchantMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final QualificationMapper qualificationMapper;
    private final AuditService auditService;
    private final UserMapper userMapper;

    @Autowired
    public MerchantService(MerchantMapper merchantMapper, EnterpriseMapper enterpriseMapper,
                           QualificationMapper qualificationMapper, AuditService auditService,
                           UserMapper userMapper) {
        this.merchantMapper = merchantMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.qualificationMapper = qualificationMapper;
        this.auditService = auditService;
        this.userMapper = userMapper;
    }

    public MerchantService(MerchantMapper merchantMapper, EnterpriseMapper enterpriseMapper,
                           QualificationMapper qualificationMapper, AuditService auditService) {
        this(merchantMapper, enterpriseMapper, qualificationMapper, auditService, null);
    }

    @Transactional
    public Merchant apply(Long userId, Long entId, MerchantApplyDTO dto) {
        // 已有商户记录则不允许重复入驻
        if (merchantMapper.selectCount(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId)) > 0) {
            throw new BusinessException("您已提交过入驻申请");
        }
        // 说明：营业执照/税务证明等证照材料由入驻审核通过后的商户，
        // 通过受 merchant:manage 权限保护的 /merchant/license、/merchant/tax-proof、/merchant/tax-query 补传与绑定；
        // 申请阶段以 taxStatus 申报驱动筛选，不再强制前置上传。
        // 创建企业
        Enterprise ent = new Enterprise();
        ent.setName(dto.getEntName());
        ent.setCreditCode(dto.getCreditCode());
        ent.setMemberLevel(1);
        ent.setCreateTime(LocalDateTime.now());
        ent.setUpdateTime(LocalDateTime.now());
        enterpriseMapper.insert(ent);

        // 创建商户并执行筛选
        Merchant m = new Merchant();
        m.setEntId(ent.getEntId());
        m.setUserId(userId);
        m.setRegType(dto.getRegType());
        m.setRegCapital(dto.getRegCapital());
        m.setTaxStatus(dto.getTaxStatus());
        m.setJoinType(dto.getJoinType());
        m.setLicenseUrl(dto.getLicenseUrl());
        if (dto.getTaxProofUrls() != null && !dto.getTaxProofUrls().isEmpty()) {
            if (dto.getTaxProofUrls().size() > 3) {
                throw new BusinessException("纳税记录最多上传 3 份");
            }
            m.setTaxProofUrls(String.join(",", dto.getTaxProofUrls()));
        }
        if (StringUtils.hasText(dto.getTaxRegNo())) {
            m.setTaxRegNo(dto.getTaxRegNo().trim());
        }
        applyScreening(m);
        m.setCreateTime(LocalDateTime.now());
        m.setUpdateTime(LocalDateTime.now());
        merchantMapper.insert(m);

        // 记录已具备资质(可选)
        if (StringUtils.hasText(dto.getQNames())) {
            for (String q : dto.getQNames().split(",")) {
                if (!StringUtils.hasText(q)) continue;
                Qualification qe = new Qualification();
                qe.setMerId(m.getMerId());
                qe.setQName(q.trim());
                qe.setQType("行业资质");
                qe.setCreateTime(LocalDateTime.now());
                qualificationMapper.insert(qe);
            }
        }

        // 若初筛直接通过，同步更新用户角色为商户(2)并关联企业
        if (m.getReviewStatus() != null && m.getReviewStatus() == 1 && userId != null && userMapper != null) {
            User u = userMapper.selectById(userId);
            if (u != null) {
                u.setRoleId(2L);
                u.setEntId(ent.getEntId());
                u.setUpdateTime(LocalDateTime.now());
                userMapper.updateById(u);
            }
            auditService.record(userId, "IDENTITY_CHANGE", "身份升级: buyer -> merchant（商户初筛通过）", null);
        }
        return m;
    }

    /** 筛选规则引擎 */
    private void applyScreening(Merchant m) {
        boolean regOk = "公司".equals(m.getRegType())
                || ("个体工商户".equals(m.getRegType())
                    && m.getRegCapital() != null && m.getRegCapital().compareTo(MIN_CAPITAL) > 0);
        boolean taxOk = m.getTaxStatus() != null && m.getTaxStatus() == 1;
        if (regOk && taxOk) {
            m.setReviewStatus(1);
            m.setRejectReason(null);
        } else {
            m.setReviewStatus(2);
            StringBuilder reason = new StringBuilder();
            if (!regOk) {
                reason.append("注册资本或主体类型不符(需公司,或个体工商户注册资本>10万元); ");
            }
            if (!taxOk) {
                reason.append("缺少稳定纳税记录; ");
            }
            m.setRejectReason(reason.toString());
        }
    }

    public Merchant getByUser(Long userId) {
        return merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
    }

    /** 商户档案必须存在（证照管理仅限已入驻商户） */
    private Merchant mustMerchant(Long userId) {
        Merchant m = getByUser(userId);
        if (m == null) throw new BusinessException("请先完成入驻审核后再管理证照");
        return m;
    }

    /** 营业执照 URL 写入商户档案 */
    public void persistLicense(Long userId, String url) {
        Merchant m = mustMerchant(userId);
        m.setLicenseUrl(url);
        m.setUpdateTime(java.time.LocalDateTime.now());
        merchantMapper.updateById(m);
    }

    /** 税务证明 URL 追加写入商户档案（逗号分隔，最多 3 份） */
    public void persistTaxProof(Long userId, String url) {
        Merchant m = mustMerchant(userId);
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (StringUtils.hasText(m.getTaxProofUrls())) {
            for (String u : m.getTaxProofUrls().split(",")) {
                if (StringUtils.hasText(u)) urls.add(u.trim());
            }
        }
        if (urls.contains(url)) return;
        urls.add(url);
        if (urls.size() > 3) urls = urls.subList(urls.size() - 3, urls.size());
        m.setTaxProofUrls(String.join(",", urls));
        m.setUpdateTime(java.time.LocalDateTime.now());
        merchantMapper.updateById(m);
    }

    /** 税务登记号绑定至商户档案 */
    public void persistTaxRegNo(Long userId, String taxRegNo) {
        Merchant m = mustMerchant(userId);
        m.setTaxRegNo(taxRegNo == null ? null : taxRegNo.trim().toUpperCase());
        m.setUpdateTime(java.time.LocalDateTime.now());
        merchantMapper.updateById(m);
    }

    /** 税务登记号格式：15/18/20 位数字或大写字母 */
    private static final java.util.regex.Pattern TAX_REG_NO =
            java.util.regex.Pattern.compile("^[0-9A-Z]{15}$|^[0-9A-Z]{18}$|^[0-9A-Z]{20}$");

    /**
     * 通过税务登记号查询近 3 个月税务缴纳记录。
     * 演示环境：无真实税务数据源，基于登记号哈希稳定生成可复现的记录
     * （同一登记号多次查询结果一致），用于入驻流程的纳税记录核验演示。
     */
    public java.util.Map<String, Object> queryTaxRecords(String taxRegNo) {
        String no = taxRegNo == null ? "" : taxRegNo.trim().toUpperCase();
        if (!TAX_REG_NO.matcher(no).matches()) {
            throw new BusinessException("税务登记号格式不正确（应为 15/18/20 位数字或大写字母）");
        }
        java.util.Random r = new java.util.Random((long) no.hashCode() * 31 + no.length());
        java.util.List<java.util.Map<String, Object>> records = new java.util.ArrayList<>();
        java.time.LocalDate now = java.time.LocalDate.now();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (int i = 3; i >= 1; i--) {
            java.time.LocalDate month = now.minusMonths(i);
            java.math.BigDecimal amount = java.math.BigDecimal.valueOf(3000 + r.nextInt(47000)).setScale(2);
            total = total.add(amount);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("month", month.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
            row.put("taxType", "增值税");
            row.put("amount", amount);
            row.put("paidAt", month.withDayOfMonth(10 + r.nextInt(18)).toString());
            records.add(row);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("taxRegNo", no);
        out.put("records", records);
        out.put("totalAmount", total);
        out.put("stable", true);
        return out;
    }

    /** 运营端: 分页查询待审/全部商户 */
    public java.util.List<Merchant> listForAdmin(Integer reviewStatus) {
        return merchantMapper.selectList(Wrappers.<Merchant>lambdaQuery()
                .eq(reviewStatus != null, Merchant::getReviewStatus, reviewStatus)
                .orderByDesc(Merchant::getCreateTime));
    }

    /**
     * 运营端: 商户详情（资料维护/资质核验视图）。
     * 聚合商户档案、企业工商信息、已具备资质清单，并计算资质材料完整度。
     */
    public java.util.Map<String, Object> adminDetail(Long merId) {
        Merchant m = merchantMapper.selectById(merId);
        if (m == null) throw new BusinessException("商户不存在");
        Enterprise ent = m.getEntId() == null ? null : enterpriseMapper.selectById(m.getEntId());
        java.util.List<Qualification> quals = qualificationMapper.selectList(
                Wrappers.<Qualification>lambdaQuery().eq(Qualification::getMerId, merId));
        int taxProofCount = StringUtils.hasText(m.getTaxProofUrls()) ? m.getTaxProofUrls().split(",").length : 0;
        java.util.Map<String, Object> readiness = new java.util.LinkedHashMap<>();
        readiness.put("hasLicense", StringUtils.hasText(m.getLicenseUrl()));
        readiness.put("taxProofCount", taxProofCount);
        readiness.put("hasTaxRegNo", StringUtils.hasText(m.getTaxRegNo()));
        readiness.put("qualificationCount", quals.size());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("merchant", m);
        out.put("enterprise", ent);
        out.put("qualifications", quals);
        out.put("certReadiness", readiness);
        return out;
    }

    /** 运营端: 人工审核(通过/驳回)。驳回必须给出原因（供商户整改与流程跟踪） */
    public Merchant review(Long merId, Integer reviewStatus, String reason) {
        Merchant m = merchantMapper.selectById(merId);
        if (m == null) throw new BusinessException("商户不存在");
        if (reviewStatus == null || reviewStatus < 1 || reviewStatus > 2) {
            throw new BusinessException("审核状态不合法");
        }
        if (reviewStatus == 2 && !StringUtils.hasText(reason)) {
            throw new BusinessException("驳回时必须填写原因");
        }
        m.setReviewStatus(reviewStatus);
        m.setRejectReason(reviewStatus == 2 ? reason.trim() : null);
        m.setUpdateTime(LocalDateTime.now());
        merchantMapper.updateById(m);
        // 审核通过 → 商户身份生效（角色置为 2L，关联企业）
        if (reviewStatus == 1 && m.getUserId() != null) {
            if (userMapper != null) {
                User u = userMapper.selectById(m.getUserId());
                if (u != null) {
                    u.setRoleId(2L);
                    if (m.getEntId() != null) {
                        u.setEntId(m.getEntId());
                    }
                    u.setUpdateTime(LocalDateTime.now());
                    userMapper.updateById(u);
                }
            }
            auditService.record(m.getUserId(), "IDENTITY_CHANGE", "身份升级: buyer -> merchant（商户审核通过）", null);
        } else if (reviewStatus == 2 && m.getUserId() != null) {
            // 审核驳回 → 若之前为商户角色则重置为普通买家(1L)
            if (userMapper != null) {
                User u = userMapper.selectById(m.getUserId());
                if (u != null && Long.valueOf(2L).equals(u.getRoleId())) {
                    u.setRoleId(1L);
                    u.setUpdateTime(LocalDateTime.now());
                    userMapper.updateById(u);
                    auditService.record(m.getUserId(), "IDENTITY_CHANGE", "身份降级: merchant -> buyer（商户审核驳回）", null);
                }
            }
        }
        return m;
    }
}
