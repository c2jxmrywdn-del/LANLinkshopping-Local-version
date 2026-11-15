package com.lanlink.shopping.module.rfq.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.rfq.dto.RfqQuoteCreateRequest;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import com.lanlink.shopping.module.rfq.entity.RfqQuote;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商户报价的准入与金额口径。
 *
 * <p>硬约束：报价必须锚定属于本商户的真实目录商品（否则接受后订单商户侧 JOIN 不到）、
 * 一户一报价（UNIQUE(rfq_id, mer_id)）、总额由 unit_price × rfq.quantity 落库（不信任客户端）、
 * 已截止/已成交询价不可报价、报价户数受 max_quotes 限制、竞对报价不可见（无接口暴露）。
 */
class RfqQuoteServiceTest {

    private RfqMapper rfqMapper;
    private RfqQuoteMapper quoteMapper;
    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private RfqQuoteService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Rfq.class);
    }

    @BeforeEach
    void setUp() {
        rfqMapper = mock(RfqMapper.class);
        quoteMapper = mock(RfqQuoteMapper.class);
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        service = new RfqQuoteService(rfqMapper, quoteMapper, productMapper, merchantMapper);
    }

    private static Merchant merchant(long merId) {
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(7L);
        m.setReviewStatus(1);
        return m;
    }

    private static Rfq rfq(String status) {
        Rfq r = new Rfq();
        r.setRfqId(100L);
        r.setRfqNo("RFQ20261109000001");
        r.setUserId(4L);
        r.setTitle("采购螺纹钢");
        r.setQuantity(new BigDecimal("20"));
        r.setUnit("吨");
        r.setQuoteDeadline(LocalDateTime.now().plusDays(3));
        r.setMaxQuotes(10);
        r.setStatus(status);
        return r;
    }

    private static Product product(long merId, int status) {
        Product p = new Product();
        p.setProdId(101L);
        p.setMerId(merId);
        p.setTitle("HRB400 Φ20");
        p.setPrice(new BigDecimal("100.00"));
        p.setStock(100);
        p.setStatus(status);
        return p;
    }

    private static RfqQuoteCreateRequest request(String unitPrice) {
        RfqQuoteCreateRequest r = new RfqQuoteCreateRequest();
        r.setProdId(101L);
        r.setUnitPrice(new BigDecimal(unitPrice));
        r.setRemark("含税含运费");
        return r;
    }

    @Test
    void submitComputesTotalFromUnitPriceTimesRfqQuantity() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));
        when(quoteMapper.selectByRfqAndMer(100L, 8L)).thenReturn(null);
        when(quoteMapper.countByRfq(100L)).thenReturn(0L);

        RfqQuote quote = service.submitQuote(7L, "RFQ20261109000001", request("98.50"));

        // 20 × 98.50 = 1970.00，scale 2
        assertEquals(0, new BigDecimal("1970.00").compareTo(quote.getTotalAmount()));
        assertEquals(0, new BigDecimal("98.50").compareTo(quote.getUnitPrice()));
        assertEquals(0, new BigDecimal("20").compareTo(quote.getQuantity()));
        assertEquals("SUBMITTED", quote.getStatus());
        assertEquals(Long.valueOf(100L), quote.getRfqId());
        assertEquals(Long.valueOf(8L), quote.getMerId());
        ArgumentCaptor<RfqQuote> captor = ArgumentCaptor.forClass(RfqQuote.class);
        verify(quoteMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("1970.00").compareTo(captor.getValue().getTotalAmount()));
        verify(quoteMapper).markQuoted(100L);
    }

    @Test
    void nonMerchantAccountIsRejected() {
        when(merchantMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50")));

        assertEquals(Integer.valueOf(403), ex.getCode());
        assertTrue(ex.getMessage().contains("尚未入驻商户"));
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void unpaidMerchantRecordIsRejected() {
        Merchant rejected = merchant(8L);
        rejected.setReviewStatus(2);
        when(merchantMapper.selectOne(any())).thenReturn(rejected);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50")));

        assertEquals(Integer.valueOf(403), ex.getCode());
        assertTrue(ex.getMessage().contains("未通过审核"));
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void overflowTotalIsRejectedBeforeInsert() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("999999999999.99")));

        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("总额"));
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void requestDtoExposesNoClientControlledTotalOrQuantity() {
        // 契约级防回归：客户端无法传总额/数量，恶意客户端连字段都没有
        assertThrows(NoSuchMethodException.class,
                () -> RfqQuoteCreateRequest.class.getMethod("setTotalAmount", BigDecimal.class));
        assertThrows(NoSuchMethodException.class,
                () -> RfqQuoteCreateRequest.class.getMethod("setQuantity", BigDecimal.class));
    }

    @Test
    void quoteMustAnchorOwnCatalogProduct() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        when(productMapper.selectById(101L)).thenReturn(product(9L, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50")));

        assertEquals(Integer.valueOf(403), ex.getCode());
        assertTrue(ex.getMessage().contains("自有商品"));
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
        verify(quoteMapper, never()).markQuoted(anyLong());
    }

    @Test
    void missingOrUnsellableProductIsRejected() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));

        when(productMapper.selectById(101L)).thenReturn(null);
        assertEquals(Integer.valueOf(404), assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50"))).getCode());

        when(productMapper.selectById(101L)).thenReturn(product(8L, 0));
        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50"))).getCode());
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void closedRfqCannotBeQuoted() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        Rfq expired = rfq("EXPIRED");
        when(rfqMapper.selectOne(any())).thenReturn(expired);
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(expired);

        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50"))).getCode());

        Rfq overdue = rfq("PUBLISHED");
        overdue.setQuoteDeadline(LocalDateTime.now().minusMinutes(1));
        when(rfqMapper.selectOne(any())).thenReturn(overdue);
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(overdue);
        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50"))).getCode());
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void existingSubmittedQuoteIsUpdatedWithRecomputedTotal() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("QUOTED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("QUOTED"));
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));
        RfqQuote existing = new RfqQuote();
        existing.setQuoteId(55L);
        existing.setRfqId(100L);
        existing.setMerId(8L);
        existing.setStatus("SUBMITTED");
        existing.setTotalAmount(new BigDecimal("1800.00"));
        when(quoteMapper.selectByRfqAndMer(100L, 8L)).thenReturn(existing);

        RfqQuote quote = service.submitQuote(7L, "RFQ20261109000001", request("100.00"));

        assertEquals(0, new BigDecimal("2000.00").compareTo(quote.getTotalAmount()));
        verify(quoteMapper).updateById(existing);
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
        verify(quoteMapper, never()).markQuoted(anyLong());
    }

    @Test
    void acceptedQuoteCannotBeModified() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("QUOTED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("QUOTED"));
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));
        RfqQuote accepted = new RfqQuote();
        accepted.setQuoteId(55L);
        accepted.setStatus("ACCEPTED");
        when(quoteMapper.selectByRfqAndMer(100L, 8L)).thenReturn(accepted);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("100.00")));
        assertEquals(Integer.valueOf(409), ex.getCode());
        verify(quoteMapper, never()).updateById(any(RfqQuote.class));
    }

    @Test
    void maxQuotesCapBlocksNewMerchant() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        Rfq r = rfq("QUOTED");
        r.setMaxQuotes(10);
        when(rfqMapper.selectOne(any())).thenReturn(r);
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(r);
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));
        when(quoteMapper.selectByRfqAndMer(anyLong(), anyLong())).thenReturn(null);
        when(quoteMapper.countByRfq(100L)).thenReturn(10L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50")));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("报价户数"));
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void concurrentFirstQuoteUniqueKeyCollisionMapsTo409() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        when(productMapper.selectById(101L)).thenReturn(product(8L, 1));
        when(quoteMapper.selectByRfqAndMer(anyLong(), anyLong())).thenReturn(null);
        when(quoteMapper.countByRfq(100L)).thenReturn(0L);
        when(quoteMapper.insert(any(RfqQuote.class))).thenThrow(new DuplicateKeyException("uk_quote_rfq_mer"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", request("98.50")));
        assertEquals(Integer.valueOf(409), ex.getCode());
        verify(quoteMapper, never()).markQuoted(anyLong());
    }

    @Test
    void pastValidUntilIsRejected() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        RfqQuoteCreateRequest r = request("98.50");
        r.setValidUntil(LocalDateTime.now().minusHours(1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuote(7L, "RFQ20261109000001", r));
        assertEquals(Integer.valueOf(400), ex.getCode());
        verify(quoteMapper, never()).insert(any(RfqQuote.class));
    }

    @Test
    void openListNarrowsStatusAndDeadlineAndMyQuoteNarrowsByMerchant() {
        when(rfqMapper.selectList(any())).thenReturn(List.of(rfq("PUBLISHED")));
        assertEquals(1, service.openRfqs().size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Rfq>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(rfqMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("status"));
        assertTrue(sql.contains("quote_deadline"));

        when(merchantMapper.selectOne(any())).thenReturn(merchant(8L));
        when(rfqMapper.selectOne(any())).thenReturn(rfq("PUBLISHED"));
        when(rfqMapper.selectByNoForUpdate(any())).thenReturn(rfq("PUBLISHED"));
        RfqQuote own = new RfqQuote();
        own.setQuoteId(55L);
        when(quoteMapper.selectByRfqAndMer(100L, 8L)).thenReturn(own);
        assertEquals(Long.valueOf(55L), service.myQuote(7L, "RFQ20261109000001").getQuoteId());
        verify(quoteMapper).selectByRfqAndMer(100L, 8L);
    }

    @Test
    void buyerQuotesNarrowByOwner() {
        when(rfqMapper.selectOwned(7L, "RFQ1")).thenReturn(null);
        assertEquals(Integer.valueOf(404), assertThrows(BusinessException.class,
                () -> service.buyerQuotes(7L, "RFQ1")).getCode());

        Rfq own = rfq("QUOTED");
        own.setRfqNo("RFQ1");
        when(rfqMapper.selectOwned(7L, "RFQ1")).thenReturn(own);
        RfqQuote q = new RfqQuote();
        q.setQuoteId(55L);
        when(quoteMapper.selectList(any())).thenReturn(List.of(q));

        assertEquals(1, service.buyerQuotes(7L, "RFQ1").size());
        verify(rfqMapper, org.mockito.Mockito.times(2)).selectOwned(7L, "RFQ1");
    }
}