package com.lanlink.shopping.module.rfq.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.rfq.dto.RfqCreateRequest;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.service.SequenceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFQ 买家发布/列表的准入与可见性。
 *
 * <p>发布侧硬约束：合规文案前置拦截、截止时间必须未来、同标题第 5 条起拒绝（对标 1688 重复询价规则，
 * 具体化到「同账号同标题 ≥5」）；详情侧必须按 user_id 收窄，别人的询价单只能得到 404。
 */
class RfqServiceTest {

    private RfqMapper rfqMapper;
    private SequenceService sequenceService;
    private TechComplianceGuard complianceGuard;
    private RfqService service;

    /** LambdaQueryWrapper 渲染 SQL 片段依赖 MP 的实体元数据缓存，纯单测需手动初始化 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Rfq.class);
    }

    @BeforeEach
    void setUp() {
        rfqMapper = mock(RfqMapper.class);
        sequenceService = mock(SequenceService.class);
        complianceGuard = mock(TechComplianceGuard.class);
        service = new RfqService(rfqMapper, sequenceService, complianceGuard);
    }

    private static RfqCreateRequest request(String title) {
        RfqCreateRequest r = new RfqCreateRequest();
        r.setTitle(title);
        r.setDescription("需要 20 吨螺纹钢，送货至浦东工地");
        r.setQuantity(new BigDecimal("20"));
        r.setUnit("吨");
        r.setQuoteDeadline(LocalDateTime.now().plusDays(3));
        return r;
    }

    @Test
    void createBuildsPublishedRfqWithSequenceNumber() {
        when(rfqMapper.countSameTitle(7L, "采购螺纹钢")).thenReturn(0L);
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        when(sequenceService.nextNo("rfq:" + day, 6)).thenReturn("000007");

        Rfq rfq = service.create(7L, request("  采购螺纹钢  "));

        assertEquals("RFQ" + day + "000007", rfq.getRfqNo());
        assertEquals(Long.valueOf(7L), rfq.getUserId());
        assertEquals("采购螺纹钢", rfq.getTitle());
        assertEquals("PUBLISHED", rfq.getStatus());
        assertEquals(Integer.valueOf(10), rfq.getMaxQuotes());
        assertEquals(0, new BigDecimal("20").compareTo(rfq.getQuantity()));
        ArgumentCaptor<Rfq> captor = ArgumentCaptor.forClass(Rfq.class);
        verify(rfqMapper).insert(captor.capture());
        assertEquals("RFQ" + day + "000007", captor.getValue().getRfqNo());
        verify(complianceGuard).validate(eq("询价标题"), eq("采购螺纹钢"));
        verify(complianceGuard).validate(eq("需求描述"), anyString());
    }

    @Test
    void createRejectsPastDeadlineBeforeTouchingSequence() {
        RfqCreateRequest r = request("采购螺纹钢");
        r.setQuoteDeadline(LocalDateTime.now().minusMinutes(1));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(7L, r));

        assertTrue(ex.getMessage().contains("报价截止时间必须晚于当前时间"));
        verify(sequenceService, never()).nextNo(anyString(), anyInt());
        verify(rfqMapper, never()).insert(any(Rfq.class));
    }

    @Test
    void fifthSameTitleIsRejectedAsRepeatAbuse() {
        // 已有 4 条同标题：第 5 条即拒绝（≥5 条即违规）
        when(rfqMapper.countSameTitle(7L, "采购螺纹钢")).thenReturn(4L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(7L, request("采购螺纹钢")));

        assertTrue(ex.getMessage().contains("重复询价"));
        verify(rfqMapper, never()).insert(any(Rfq.class));
        verify(sequenceService, never()).nextNo(anyString(), anyInt());
    }

    @Test
    void fourthSameTitleIsStillAllowed() {
        when(rfqMapper.countSameTitle(7L, "采购螺纹钢")).thenReturn(3L);
        when(sequenceService.nextNo(anyString(), eq(6))).thenReturn("000005");

        Rfq rfq = service.create(7L, request("采购螺纹钢"));

        assertEquals("PUBLISHED", rfq.getStatus());
        verify(rfqMapper).insert(any(Rfq.class));
    }

    @Test
    void complianceViolationPropagatesWithoutInsert() {
        when(rfqMapper.countSameTitle(anyLong(), anyString())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new BusinessException(400, "询价标题包含不合规表述"))
                .when(complianceGuard).validate(eq("询价标题"), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(7L, request("保证成交的钢材")));

        assertEquals(Integer.valueOf(400), ex.getCode());
        verify(rfqMapper, never()).insert(any(Rfq.class));
    }

    @Test
    void descriptionIsAlsoComplianceChecked() {
        org.mockito.Mockito.doThrow(new BusinessException(400, "需求描述包含不允许的内容"))
                .when(complianceGuard).validate(eq("需求描述"), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(7L, request("采购螺纹钢")));

        assertEquals(Integer.valueOf(400), ex.getCode());
        verify(rfqMapper, never()).insert(any(Rfq.class));
    }

    @Test
    void listMineNarrowsByRequestingUser() {
        Rfq row = new Rfq();
        row.setRfqNo("RFQ1");
        when(rfqMapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(1, service.listMine(7L).size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Rfq>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(rfqMapper).selectList(cap.capture());
        // 归属收窄必须落在查询条件上：删掉 eq(userId) 该断言即失败
        assertTrue(cap.getValue().getSqlSegment().contains("user_id"));
    }

    @Test
    void detailNarrowsByOwnerAnd404sForForeignRfq() {
        when(rfqMapper.selectOwned(7L, "RFQ1")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(7L, "RFQ1"));
        assertEquals(Integer.valueOf(404), ex.getCode());
        verify(rfqMapper).selectOwned(7L, "RFQ1");

        Rfq own = new Rfq();
        own.setRfqNo("RFQ1");
        when(rfqMapper.selectOwned(7L, "RFQ1")).thenReturn(own);
        assertEquals("RFQ1", service.detail(7L, "RFQ1").getRfqNo());
    }

    @Test
    void expireOverdueDelegatesToSingleAtomicUpdate() {
        when(rfqMapper.expireOverdue()).thenReturn(3);

        assertEquals(3, service.expireOverdue());
        verify(rfqMapper).expireOverdue();
    }
}