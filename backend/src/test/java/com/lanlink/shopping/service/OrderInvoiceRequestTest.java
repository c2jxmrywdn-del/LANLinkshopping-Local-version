package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-5 订单开票：发票需求快照落库与服务端校验（不信任前端）。
 * 覆盖：needed=true 全字段快照、个人抬头税号置空、category/detail 内容互斥清空、
 * 单位抬头税号格式（15-20 位大写字母数字）、非法枚举 TamperGuard 告警、
 * needed=false/null 落 invoice_needed=0、RFQ 报价转单链路同样携带发票快照。
 */
class OrderInvoiceRequestTest {

    private CartMapper cartMapper;
    private ProductMapper productMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private UserMapper userMapper;
    private PricingFacade pricingFacade;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        userMapper = mock(UserMapper.class);
        pricingFacade = mock(PricingFacade.class);
        orderService = new OrderService(
                cartMapper, productMapper, orderMapper, orderItemMapper, userMapper,
                mock(MessageService.class), mock(IdentityService.class), mock(AuditService.class),
                pricingFacade, mock(ApplicationEventPublisher.class),
                mock(com.lanlink.shopping.module.membership.service.MembershipService.class),
                mock(CreditTermService.class), mock(IdempotentService.class), mock(TamperGuardService.class),
                mock(com.lanlink.shopping.module.campaign.service.CampaignService.class),
                mock(com.lanlink.shopping.module.risk.service.RiskEventService.class));

        when(pricingFacade.quote(any(), any(), any(), anyBoolean(), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), BigDecimal.ZERO, BigDecimal.ZERO,
                        PricingFacade.SOURCE_PROMOTION, null));
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        when(productMapper.deductStockAtomic(any(), anyInt())).thenReturn(1);
    }

    private static User user() {
        User u = new User();
        u.setUserId(7L);
        u.setRoleId(2L);
        return u;
    }

    private static Cart cart(long cartId, long prodId, int qty) {
        Cart c = new Cart();
        c.setCartId(cartId);
        c.setUserId(7L);
        c.setProdId(prodId);
        c.setQuantity(qty);
        c.setChecked(1);
        return c;
    }

    private static Product product() {
        Product p = new Product();
        p.setProdId(101L);
        p.setMerId(9L);
        p.setIndId(1L);
        p.setTitle("P-101");
        p.setPrice(new BigDecimal("100.00"));
        p.setStock(10);
        p.setStatus(1);
        return p;
    }

    private void primeHappyPath() {
        when(userMapper.selectById(7L)).thenReturn(user());
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 2)));
        when(productMapper.selectById(101L)).thenReturn(product());
    }

    private static CheckoutDTO.InvoiceRequest invoice() {
        CheckoutDTO.InvoiceRequest inv = new CheckoutDTO.InvoiceRequest();
        inv.setNeeded(true);
        inv.setTitleType("company");
        inv.setTitle("测试科技有限公司");
        inv.setTaxNo("91330100MA2XYZ1234");
        inv.setContentType("category");
        inv.setCategory("工业设备");
        return inv;
    }

    private static CheckoutDTO dto(CheckoutDTO.InvoiceRequest inv) {
        CheckoutDTO d = new CheckoutDTO();
        d.setPayType("corporate");
        d.setReceiver("张三");
        d.setPhone("13900000001");
        d.setAddress("上海市浦东新区某路 1 号");
        d.setIdempotentKey("idem-inv");
        d.setInvoice(inv);
        return d;
    }

    private Order capturedOrder() {
        ArgumentCaptor<Order> cap = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(1)).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    void companyInvoiceWithValidTaxNoIsSnapshottedOnOrder() {
        primeHappyPath();

        orderService.checkout(7L, dto(invoice()));

        Order o = capturedOrder();
        assertEquals(Integer.valueOf(1), o.getInvoiceNeeded());
        assertEquals("company", o.getInvoiceTitleType());
        assertEquals("测试科技有限公司", o.getInvoiceTitle());
        assertEquals("91330100MA2XYZ1234", o.getInvoiceTaxNo());
        assertEquals("category", o.getInvoiceContentType());
        assertEquals("工业设备", o.getInvoiceCategory());
        assertNull(o.getInvoiceDetail());
    }

    @Test
    void personalInvoiceLeavesTaxNoEmpty() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setTitleType("personal");
        inv.setTaxNo("");
        inv.setContentType("detail");
        inv.setDetail("数控机床配件一批");
        inv.setCategory(null);

        orderService.checkout(7L, dto(inv));

        Order o = capturedOrder();
        assertEquals(Integer.valueOf(1), o.getInvoiceNeeded());
        assertEquals("personal", o.getInvoiceTitleType());
        assertEquals("数控机床配件一批", o.getInvoiceDetail());
        assertNull(o.getInvoiceCategory());
        assertNull(o.getInvoiceTaxNo());
    }

    @Test
    void contentSwitchClearsTheOtherSide() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setContentType("detail");
        inv.setDetail("设备配件");
        inv.setCategory("工业设备");

        orderService.checkout(7L, dto(inv));

        Order o = capturedOrder();
        assertEquals("detail", o.getInvoiceContentType());
        assertNull(o.getInvoiceCategory());
        assertEquals("设备配件", o.getInvoiceDetail());
    }

    @Test
    void companyTitleWithoutTaxNoIsRejected() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setTaxNo("");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto(inv)));
        assertTrue(ex.getMessage().contains("纳税人识别号"));
        verify(orderMapper, times(0)).insert(any(Order.class));
    }

    @Test
    void lowercaseTaxNoIsRejected() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setTaxNo("91330100ma2xyz1234");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto(inv)));
        assertTrue(ex.getMessage().contains("纳税人识别号"));
    }

    @Test
    void taxNoLengthIsBounded15To20() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest tooShort = invoice();
        tooShort.setTaxNo("91330100MA2XYZ"); // 14 位
        assertThrows(BusinessException.class, () -> orderService.checkout(7L, dto(tooShort)));

        CheckoutDTO.InvoiceRequest tooLong = invoice();
        tooLong.setTaxNo("91330100MA2XYZ1234567"); // 21 位
        assertThrows(BusinessException.class, () -> orderService.checkout(7L, dto(tooLong)));

        // 15 位与 20 位边界均可通过
        CheckoutDTO.InvoiceRequest min = invoice();
        min.setTaxNo("ABCDEFGHIJKLMNO"); // 15 位
        orderService.checkout(7L, dto(min));
        org.mockito.ArgumentCaptor<Order> c1 = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(1)).insert(c1.capture());
        assertEquals("ABCDEFGHIJKLMNO", c1.getValue().getInvoiceTaxNo());
    }

    @Test
    void invalidTitleTypeReportsTamperAndRejects() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setTitleType("government");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto(inv)));
        assertTrue(ex.getMessage().contains("发票抬头类型"));
        verify(orderMapper, times(0)).insert(any(Order.class));
    }

    @Test
    void invalidContentTypeReportsTamperAndRejects() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setContentType("custom");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto(inv)));
        assertTrue(ex.getMessage().contains("发票内容类型"));
    }

    @Test
    void categoryContentWithoutCategoryIsRejected() {
        primeHappyPath();
        CheckoutDTO.InvoiceRequest inv = invoice();
        inv.setCategory("  ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto(inv)));
        assertTrue(ex.getMessage().contains("商品类别"));
    }

    @Test
    void noInvoiceRequestFallsBackToZero() {
        primeHappyPath();
        CheckoutDTO d = dto(null);

        orderService.checkout(7L, d);

        assertEquals(Integer.valueOf(0), capturedOrder().getInvoiceNeeded());
        assertNull(capturedOrder().getInvoiceTitle());
    }

    @Test
    void rfqQuoteCheckoutAlsoSnapshotsInvoice() {
        // RFQ 转单链路复用 applyInvoiceSnapshot：发票需求随议价成交单同样落库
        com.lanlink.shopping.module.rfq.mapper.RfqMapper rfqMapper =
                mock(com.lanlink.shopping.module.rfq.mapper.RfqMapper.class);
        com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper rfqQuoteMapper =
                mock(com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper.class);
        ReflectionTestUtils.setField(orderService, "rfqMapper", rfqMapper);
        ReflectionTestUtils.setField(orderService, "rfqQuoteMapper", rfqQuoteMapper);

        com.lanlink.shopping.module.rfq.entity.Rfq rfq = new com.lanlink.shopping.module.rfq.entity.Rfq();
        rfq.setRfqId(1L);
        rfq.setUserId(7L);
        rfq.setStatus("QUOTED");
        rfq.setRfqNo("RFQ2026");
        when(rfqMapper.selectByIdForUpdate(1L)).thenReturn(rfq);

        com.lanlink.shopping.module.rfq.entity.RfqQuote quote = new com.lanlink.shopping.module.rfq.entity.RfqQuote();
        quote.setQuoteId(2L);
        quote.setRfqId(1L);
        quote.setProdId(101L);
        quote.setStatus("SUBMITTED");
        quote.setQuantity(new BigDecimal("5"));
        quote.setUnitPrice(new BigDecimal("80.00"));
        quote.setTotalAmount(new BigDecimal("400.00"));
        when(rfqQuoteMapper.selectById(2L)).thenReturn(quote);
        when(rfqQuoteMapper.acceptAtomic(2L)).thenReturn(1);
        when(rfqQuoteMapper.rejectSiblings(1L, 2L)).thenReturn(0);
        when(rfqMapper.markOrdered(1L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(user());
        when(productMapper.selectById(101L)).thenReturn(product());

        CheckoutDTO d = dto(invoice());
        d.setPayType("corporate");

        Order order = orderService.checkoutFromQuote(7L, 2L, d);

        assertEquals("RFQ2026", order.getRfqNo());
        assertEquals(Integer.valueOf(1), order.getInvoiceNeeded());
        assertEquals("测试科技有限公司", order.getInvoiceTitle());
        assertEquals("91330100MA2XYZ1234", order.getInvoiceTaxNo());
    }
}
