package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.techservice.dto.TechAcceptanceRequest;
import com.lanlink.shopping.module.techservice.dto.TechContractCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechEscrowCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechServiceFeeCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechAcceptance;
import com.lanlink.shopping.module.techservice.entity.TechContract;
import com.lanlink.shopping.module.techservice.entity.TechEscrowLedger;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechServiceFee;
import com.lanlink.shopping.module.techservice.mapper.TechAcceptanceMapper;
import com.lanlink.shopping.module.techservice.mapper.TechContractMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import com.lanlink.shopping.module.techservice.mapper.TechEscrowLedgerMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechMilestoneMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceFeeMapper;
import com.lanlink.shopping.module.techservice.service.TechAcceptanceService;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechContractService;
import com.lanlink.shopping.module.techservice.service.TechProjectService;
import com.lanlink.shopping.module.techservice.service.TechSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** P0：六类合同、服务费台账、资金监管台账、验收归档。 */
class TechContractP0Test {

    private TechContractMapper contractMapper;
    private TechProjectMapper projectMapper;
    private TechDealMapper dealMapper;
    private UserMapper userMapper;
    private TechServiceFeeMapper feeMapper;
    private TechEscrowLedgerMapper escrowMapper;
    private TechAcceptanceMapper acceptanceMapper;
    private TechMilestoneMapper milestoneMapper;
    private TechExpertMapper expertMapper;
    private TechContractService contractService;
    private TechSettlementService settlementService;
    private TechProjectService projectService;
    private TechAcceptanceService acceptanceService;

    @BeforeEach
    void setUp() {
        contractMapper = mock(TechContractMapper.class);
        projectMapper = mock(TechProjectMapper.class);
        dealMapper = mock(TechDealMapper.class);
        userMapper = mock(UserMapper.class);
        feeMapper = mock(TechServiceFeeMapper.class);
        escrowMapper = mock(TechEscrowLedgerMapper.class);
        acceptanceMapper = mock(TechAcceptanceMapper.class);
        milestoneMapper = mock(TechMilestoneMapper.class);
        expertMapper = mock(TechExpertMapper.class);
        contractService = new TechContractService(contractMapper, projectMapper, dealMapper,
                userMapper, new TechComplianceGuard());
        settlementService = new TechSettlementService(feeMapper, escrowMapper, contractMapper);
        projectService = new TechProjectService(projectMapper, milestoneMapper, expertMapper);
        acceptanceService = new TechAcceptanceService(acceptanceMapper, contractService, projectService);
    }

    private TechProject project(Long ownerId) {
        TechProject p = new TechProject();
        p.setProjectId(1L);
        p.setProjectNo("TP-001");
        p.setUserId(ownerId);
        p.setTitle("技术需求陪跑");
        p.setAmount(new BigDecimal("100000"));
        p.setStatus("ACTIVE");
        return p;
    }

    @Test
    void templatesCoverSixContractTypes() {
        assertEquals(6, contractService.templates().size());
        assertEquals("DEV", contractService.templates().get(0).code());
        assertTrue(contractService.templates().stream().anyMatch(t -> "INTERMEDIARY".equals(t.code())));
    }

    @Test
    void createContractRequiresProjectOwnership() {
        TechProject p = project(1L);
        when(projectMapper.selectById(1L)).thenReturn(p);
        User user = new User();
        user.setUserId(1L);
        user.setNickname("采购经理老陈");
        when(userMapper.selectById(1L)).thenReturn(user);

        TechContractCreateRequest req = new TechContractCreateRequest(1L, null, "SERVICE", null, null, null);
        TechContract created = contractService.create(1L, req);
        assertNotNull(created.getContractNo());
        assertEquals("SERVICE", created.getTemplateCode());
        assertEquals("技术服务", created.getContractType());
        assertEquals("采购经理老陈", created.getPartyA());
        assertEquals("DRAFT", created.getStatus());
        assertEquals(new BigDecimal("100000"), created.getAmount());

        assertThrows(BusinessException.class, () -> contractService.create(2L, req));
    }

    @Test
    void createContractRejectsUnknownTemplate() {
        assertThrows(BusinessException.class, () -> contractService.create(1L,
                new TechContractCreateRequest(1L, null, "UNKNOWN", null, null, null)));
    }

    @Test
    void signTransitionsDraftToSignedWithEvidence() {
        TechContract contract = new TechContract();
        contract.setContractNo("TC-001");
        contract.setCreatedBy(1L);
        contract.setStatus("DRAFT");
        contract.setPartyA("甲方");
        contract.setPartyB("乙方");
        contract.setAmount(new BigDecimal("100000"));
        when(contractMapper.selectOne(any())).thenReturn(contract);

        TechContract signed = contractService.sign(1L, "TC-001");
        assertEquals("SIGNED", signed.getStatus());
        assertNotNull(signed.getFileHash());
        assertNotNull(signed.getSignedAt());
        assertEquals("local-stub", signed.getSignProvider());
        verify(contractMapper).updateById(contract);

        assertThrows(BusinessException.class, () -> contractService.sign(2L, "TC-001"));
    }

    @Test
    void createFeeValidatesOwnershipTypeAndAmount() {
        TechContract contract = new TechContract();
        contract.setContractId(9L);
        contract.setContractNo("TC-009");
        contract.setCreatedBy(1L);
        contract.setProjectId(1L);
        when(contractMapper.selectOne(any())).thenReturn(contract);

        TechServiceFee fee = settlementService.createFee(1L, "TC-009",
                new TechServiceFeeCreateRequest("SERVICE", new BigDecimal("5000")));
        assertEquals("PENDING", fee.getStatus());
        assertEquals(1L, fee.getPayerUserId());
        verify(feeMapper).insert(any(TechServiceFee.class));

        assertThrows(BusinessException.class, () -> settlementService.createFee(2L, "TC-009",
                new TechServiceFeeCreateRequest("SERVICE", new BigDecimal("5000"))));
        assertThrows(BusinessException.class, () -> settlementService.createFee(1L, "TC-009",
                new TechServiceFeeCreateRequest("UNKNOWN", new BigDecimal("1"))));
        assertThrows(BusinessException.class, () -> settlementService.createFee(1L, "TC-009",
                new TechServiceFeeCreateRequest("SERVICE", BigDecimal.ZERO)));
    }

    @Test
    void markPaidIsIdempotentViaConditionalUpdate() {
        TechServiceFee fee = new TechServiceFee();
        fee.setFeeId(1L);
        fee.setStatus("PENDING");
        when(feeMapper.selectById(1L)).thenReturn(fee);
        when(feeMapper.update(any(), any())).thenReturn(1);
        assertDoesNotThrow(() -> settlementService.markPaid(1L, "bank", "BANK-001", "INV-001"));

        fee.setStatus("PAID");
        assertThrows(BusinessException.class,
                () -> settlementService.markPaid(1L, "bank", "BANK-002", "INV-002"));
    }

    @Test
    void escrowLedgerValidatesDirectionAndAmount() {
        assertThrows(BusinessException.class, () -> settlementService.recordEscrow(
                new TechEscrowCreateRequest(1L, null, new BigDecimal("100"), "SIDEWAYS", "bank", "R1")));
        assertThrows(BusinessException.class, () -> settlementService.recordEscrow(
                new TechEscrowCreateRequest(1L, null, BigDecimal.ZERO, "IN", "bank", "R1")));
        assertThrows(BusinessException.class, () -> settlementService.recordEscrow(
                new TechEscrowCreateRequest(1L, null, new BigDecimal("100"), "IN", " ", "R1")));

        TechEscrowLedger ledger = assertDoesNotThrow(() -> settlementService.recordEscrow(
                new TechEscrowCreateRequest(1L, null, new BigDecimal("100"), "IN", "bank", "R1")));
        assertEquals("CONFIRMED", ledger.getStatus());
        assertNotNull(ledger.getEscrowNo());
        verify(escrowMapper).insert(any(TechEscrowLedger.class));
    }

    @Test
    void acceptanceRequiresSignedContract() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(contractMapper.selectCount(any())).thenReturn(0L);

        assertThrows(BusinessException.class, () -> acceptanceService.accept(1L, 1L,
                new TechAcceptanceRequest("PASS", 5, "满意")));
        verify(acceptanceMapper, never()).insert(any(TechAcceptance.class));
    }

    @Test
    void acceptancePassCompletesProjectAndRecordsEvidence() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(contractMapper.selectCount(any())).thenReturn(1L);

        Map<String, Object> result = acceptanceService.accept(1L, 1L,
                new TechAcceptanceRequest("PASS", 4, "按期交付"));
        TechAcceptance acceptance = (TechAcceptance) result.get("acceptance");
        assertEquals("PASS", acceptance.getResult());
        assertNotNull(acceptance.getEvidenceHash());
        verify(acceptanceMapper).insert(any(TechAcceptance.class));
    }

    @Test
    void completeProjectMarksAllMilestonesDone() {
        TechProject p = project(1L);
        when(projectMapper.selectById(1L)).thenReturn(p);
        TechMilestone m1 = new TechMilestone();
        m1.setMilestoneId(11L);
        m1.setStatus("IN_PROGRESS");
        TechMilestone m2 = new TechMilestone();
        m2.setMilestoneId(12L);
        m2.setStatus("PENDING");
        when(milestoneMapper.selectList(any())).thenReturn(List.of(m1, m2));

        Map<String, Object> view = projectService.completeProject(1L, 1L, false);
        assertEquals("COMPLETED", p.getStatus());
        assertEquals("CLOSED", p.getStage());
        assertEquals(100, p.getProgress());
        assertEquals("DONE", m1.getStatus());
        assertEquals("DONE", m2.getStatus());
        verify(milestoneMapper, times(2)).updateById(any(TechMilestone.class));
    }
}
