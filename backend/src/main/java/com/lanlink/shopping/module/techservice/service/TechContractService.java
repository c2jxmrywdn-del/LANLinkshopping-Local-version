package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.techservice.domain.TechContractTemplates;
import com.lanlink.shopping.module.techservice.domain.TechEvidenceHasher;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechContractCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechContract;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechContractMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 技术服务合同聚合：六类技术合同 + 平台中介合同的创建与签署。
 *
 * <p>签署为本地演示实现（内容哈希 + 签署编号），生产环境须替换为持牌电子签服务；
 * 平台不替代律师审核，技术合同认定登记由登记机构办理。
 */
@Service
public class TechContractService {

    private static final String DEFAULT_PARTY_B = "LANLink 平台运营方";

    private final TechContractMapper contractMapper;
    private final TechProjectMapper projectMapper;
    private final TechDealMapper dealMapper;
    private final UserMapper userMapper;
    private final TechComplianceGuard complianceGuard;

    public TechContractService(TechContractMapper contractMapper,
                               TechProjectMapper projectMapper,
                               TechDealMapper dealMapper,
                               UserMapper userMapper,
                               TechComplianceGuard complianceGuard) {
        this.contractMapper = contractMapper;
        this.projectMapper = projectMapper;
        this.dealMapper = dealMapper;
        this.userMapper = userMapper;
        this.complianceGuard = complianceGuard;
    }

    public List<TechContractTemplates.Template> templates() {
        return TechContractTemplates.all();
    }

    @Transactional
    public TechContract create(Long userId, TechContractCreateRequest req) {
        TechContractTemplates.Template template = TechContractTemplates.find(req.templateCode())
                .orElseThrow(() -> new BusinessException(400, "不支持的技术合同类型"));
        if (req.projectId() == null && req.dealId() == null) {
            throw new BusinessException(400, "请选择关联的陪跑项目或交易");
        }
        String title = template.name();
        BigDecimal amount = req.amount();
        Long projectId = req.projectId();
        Long dealId = req.dealId();
        String partyB = req.partyB();

        if (projectId != null) {
            TechProject project = projectMapper.selectById(projectId);
            if (project == null) throw new BusinessException(404, "陪跑项目不存在");
            if (!Objects.equals(project.getUserId(), userId)) {
                throw new BusinessException(403, "无权为该笔业务创建合同");
            }
            title = template.name() + " · " + project.getTitle();
            if (amount == null) amount = project.getAmount();
        }
        if (dealId != null) {
            TechDeal deal = dealMapper.selectById(dealId);
            if (deal == null) throw new BusinessException(404, "交易不存在");
            if (!Objects.equals(deal.getBuyerUserId(), userId)) {
                throw new BusinessException(403, "无权为该交易创建合同");
            }
            if (amount == null) amount = deal.getAmount();
            if (partyB == null || partyB.isBlank()) {
                partyB = nicknameOf(deal.getSellerUserId());
            }
        }
        if (partyB == null || partyB.isBlank()) partyB = DEFAULT_PARTY_B;
        complianceGuard.validate("合同相对方", partyB);

        TechContract contract = new TechContract();
        contract.setContractNo(TechNoGenerator.next("TC"));
        contract.setProjectId(projectId);
        contract.setDealId(dealId);
        contract.setTemplateCode(template.code());
        contract.setContractType(template.contractType());
        contract.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        contract.setPartyA(nicknameOf(userId));
        contract.setPartyB(partyB.trim());
        contract.setAmount(amount == null ? BigDecimal.ZERO : amount);
        contract.setCommissionRate(req.commissionRate() == null
                ? new BigDecimal(template.defaultCommissionRate()) : req.commissionRate());
        contract.setStatus("DRAFT");
        contract.setCreatedBy(userId);
        contractMapper.insert(contract);
        return contract;
    }

    /**
     * 本地签署（演示）：生成内容哈希与签署编号。
     * 生产：调用持牌电子签服务并回写 signProvider/signRef/文件哈希。
     */
    @Transactional
    public TechContract sign(Long userId, String contractNo) {
        TechContract contract = loadForOwner(userId, contractNo);
        if (!"DRAFT".equals(contract.getStatus()) && !"PENDING_SIGN".equals(contract.getStatus())) {
            throw new BusinessException(400, "当前合同状态不可签署");
        }
        LocalDateTime signedAt = LocalDateTime.now();
        String hash = TechEvidenceHasher.sha256(contract.getContractNo() + "|" + contract.getPartyA() + "|"
                + contract.getPartyB() + "|" + contract.getAmount() + "|" + signedAt);
        contract.setStatus("SIGNED");
        contract.setFileHash(hash);
        contract.setSignProvider("local-stub");
        contract.setSignRef("ESIGN-" + hash.substring(0, 16));
        contract.setSignedAt(signedAt);
        contractMapper.updateById(contract);
        return contract;
    }

    public List<TechContract> myContracts(Long userId) {
        return contractMapper.selectList(Wrappers.<TechContract>lambdaQuery()
                .eq(TechContract::getCreatedBy, userId).orderByDesc(TechContract::getCreateTime));
    }

    public TechContract detail(Long userId, String contractNo) {
        return loadForOwner(userId, contractNo);
    }

    public TechContract adminDetail(String contractNo) {
        TechContract contract = contractMapper.selectOne(Wrappers.<TechContract>lambdaQuery()
                .eq(TechContract::getContractNo, contractNo).last("LIMIT 1"));
        if (contract == null) throw new BusinessException(404, "合同不存在");
        return contract;
    }

    public Page<TechContract> adminPage(long page, long size, String status, String keyword) {
        String st = text(status);
        String kw = text(keyword);
        return contractMapper.selectPage(TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE), Wrappers.<TechContract>lambdaQuery()
                .eq(st != null, TechContract::getStatus, st)
                .and(kw != null, w -> w.like(TechContract::getContractNo, kw)
                        .or().like(TechContract::getTitle, kw)
                        .or().like(TechContract::getPartyA, kw)
                        .or().like(TechContract::getPartyB, kw))
                .orderByDesc(TechContract::getCreateTime));
    }

    public long countSignedByProject(Long projectId) {
        Long value = contractMapper.selectCount(Wrappers.<TechContract>lambdaQuery()
                .eq(TechContract::getProjectId, projectId).eq(TechContract::getStatus, "SIGNED"));
        return value == null ? 0 : value;
    }

    private TechContract loadForOwner(Long userId, String contractNo) {
        TechContract contract = contractMapper.selectOne(Wrappers.<TechContract>lambdaQuery()
                .eq(TechContract::getContractNo, contractNo).last("LIMIT 1"));
        if (contract == null) throw new BusinessException(404, "合同不存在");
        if (!Objects.equals(contract.getCreatedBy(), userId)) {
            throw new BusinessException(403, "无权操作该合同");
        }
        return contract;
    }

    private String nicknameOf(Long userId) {
        if (userId == null) return "平台用户";
        User user = userMapper.selectById(userId);
        if (user == null || user.getNickname() == null || user.getNickname().isBlank()) return "平台用户";
        return user.getNickname();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
