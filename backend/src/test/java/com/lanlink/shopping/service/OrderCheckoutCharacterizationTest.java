package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.LiveProduct;
import com.lanlink.shopping.entity.LiveRoom;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.LiveProductMapper;
import com.lanlink.shopping.mapper.LiveRoomMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.dto.CheckoutDTO;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * checkout 特征化测试（RFQ S2 门禁）：把现有下单行为的可观察结果钉死，
 * 供后续"购物车加载抽成 Line"重构对照——重构前后必须逐项一致。
 *
 * <p>覆盖的消费点：计价循环（阶梯/原价取优）、会员胜出回落原价单价、
 * 直播归因证据链、明细快照 + 原子扣库存 + 清购物车、积分抵现、账期账单。
 * 失败路径覆盖：空购物车、非法支付方式（先于加载购物车）、商品不可售、
 * 预校验库存不足、并发扣减冲突（扣减影响 0 行）。
 */
class OrderCheckoutCharacterizationTest {

    private CartMapper cartMapper;
    private ProductMapper productMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private UserMapper userMapper;
    private MessageService messageService;
    private MembershipService membershipService;
    private CreditTermService creditTermService;
    private IdempotentService idempotentService;
    private TamperGuardService tamperGuardService;
    private com.lanlink.shopping.module.campaign.service.CampaignService campaignService;
    private com.lanlink.shopping.module.risk.service.RiskEventService riskEventService;
    private PricingFacade pricingFacade;
    private LiveRoomMapper liveRoomMapper;
    private LiveProductMapper liveProductMapper;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        userMapper = mock(UserMapper.class);
        messageService = mock(MessageService.class);
        membershipService = mock(MembershipService.class);
        creditTermService = mock(CreditTermService.class);
        idempotentService = mock(IdempotentService.class);
        tamperGuardService = mock(TamperGuardService.class);
        campaignService = mock(com.lanlink.shopping.module.campaign.service.CampaignService.class);
        riskEventService = mock(com.lanlink.shopping.module.risk.service.RiskEventService.class);
        pricingFacade = mock(PricingFacade.class);
        liveRoomMapper = mock(LiveRoomMapper.class);
        liveProductMapper = mock(LiveProductMapper.class);
        orderService = new OrderService(
                cartMapper, productMapper, orderMapper, orderItemMapper, userMapper, messageService,
                mock(IdentityService.class), mock(AuditService.class), pricingFacade,
                mock(ApplicationEventPublisher.class), membershipService, creditTermService,
                idempotentService, tamperGuardService, campaignService, riskEventService);
        ReflectionTestUtils.setField(orderService, "liveRoomMapper", liveRoomMapper);
        ReflectionTestUtils.setField(orderService, "liveProductMapper", liveProductMapper);

        when(idempotentService.reserve(any(), any(), any(), any())).thenReturn(1L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        when(productMapper.deductStockAtomic(any(), anyInt())).thenReturn(1);
        // 无促销无会员：Quote 原样返回进入计价链路的货值
        when(pricingFacade.quote(any(), any(), any(), anyBoolean(), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), BigDecimal.ZERO, BigDecimal.ZERO,
                        PricingFacade.SOURCE_PROMOTION, null));
    }

    private static User user(long roleId) {
        User u = new User();
        u.setUserId(7L);
        u.setRoleId(roleId);
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

    private static Product product(long prodId, String price, int stock, int status) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(9L);
        p.setIndId(1L);
        p.setTitle("P-" + prodId);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setStatus(status);
        return p;
    }

    private static CheckoutDTO dto(String payType) {
        CheckoutDTO d = new CheckoutDTO();
        d.setPayType(payType);
        d.setReceiver("张三");
        d.setPhone("13900000001");
        d.setAddress("上海市浦东新区某路 1 号");
        d.setIdempotentKey("idem-test");
        return d;
    }

    private List<OrderItem> insertedItems(int expectedCount) {
        ArgumentCaptor<OrderItem> cap = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(expectedCount)).insert(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void baselineCorporateOrderSnapshotsItemStockAndClearsCart() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 2)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));

        Order order = orderService.checkout(7L, dto("corporate"));

        assertEquals(0, new BigDecimal("200.00").compareTo(order.getTotalAmount()));
        assertEquals(Integer.valueOf(0), order.getPayStatus());
        assertEquals(Integer.valueOf(0), order.getOrderStatus());
        List<OrderItem> items = insertedItems(1);
        assertEquals(Integer.valueOf(2), items.get(0).getQuantity());
        assertEquals(0, new BigDecimal("100.00").compareTo(items.get(0).getPrice()));
        assertEquals(0, new BigDecimal("200.00").compareTo(items.get(0).getSubtotal()));
        verify(productMapper).deductStockAtomic(101L, 2);
        verify(cartMapper).deleteById(11L);
        verify(messageService).send(eq(7L), eq("order"), eq("下单成功"), anyString(), eq(order.getOrderNo()));
        verify(idempotentService).bound(1L, order.getOrderNo());
    }

    @Test
    void multiLineOrderSumsTotalsAndWritesOneItemPerLine() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(
                cart(11L, 101L, 2), cart(12L, 102L, 3)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        when(productMapper.selectById(102L)).thenReturn(product(102L, "50.00", 10, 1));

        Order order = orderService.checkout(7L, dto("corporate"));

        assertEquals(0, new BigDecimal("350.00").compareTo(order.getTotalAmount()));
        List<OrderItem> items = insertedItems(2);
        // 逐行单价/小计必须与总额同源（不以合计掩盖单行快照错误）
        assertEquals(0, new BigDecimal("100.00").compareTo(items.get(0).getPrice()));
        assertEquals(0, new BigDecimal("200.00").compareTo(items.get(0).getSubtotal()));
        assertEquals(0, new BigDecimal("50.00").compareTo(items.get(1).getPrice()));
        assertEquals(0, new BigDecimal("150.00").compareTo(items.get(1).getSubtotal()));
        assertEquals(Integer.valueOf(2), items.get(0).getQuantity());
        assertEquals(Integer.valueOf(3), items.get(1).getQuantity());
        verify(productMapper).deductStockAtomic(101L, 2);
        verify(productMapper).deductStockAtomic(102L, 3);
        verify(cartMapper).deleteById(11L);
        verify(cartMapper).deleteById(12L);
    }

    @Test
    void selectedCartIdsBranchStillConsumesReturnedRows() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        CheckoutDTO d = dto("corporate");
        d.setCartIds(List.of(11L));

        Order order = orderService.checkout(7L, d);

        assertEquals(0, new BigDecimal("100.00").compareTo(order.getTotalAmount()));
        verify(cartMapper).deleteById(11L);
        verify(cartMapper, never()).deleteById(12L);
    }

    @Test
    void tierPriceWinsAndSnapshotsTierUnitPrice() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 5)));
        Product p = product(101L, "100.00", 10, 1);
        p.setTierPriceJson("[{\"qty\":3,\"price\":80}]");
        when(productMapper.selectById(101L)).thenReturn(p);

        Order order = orderService.checkout(7L, dto("corporate"));

        assertEquals(0, new BigDecimal("400.00").compareTo(order.getTotalAmount()));
        OrderItem item = insertedItems(1).get(0);
        assertEquals(0, new BigDecimal("80.00").compareTo(item.getPrice()));
        assertEquals(0, new BigDecimal("400.00").compareTo(item.getSubtotal()));
        verify(pricingFacade).quote(eq(7L), any(), any(), eq(false), anyList());
    }

    @Test
    void cartlessLineConsumesStockWithoutCartDeletion() {
        // RFQ 报价转单形态：cartId=null，绝不能调用 deleteById 误删别人的购物车行
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));

        Order order = orderService.doCheckoutWithLines(7L, dto("corporate"), user(2L),
                List.of(new OrderService.Line(101L, 2, null)));

        assertEquals(0, new BigDecimal("200.00").compareTo(order.getTotalAmount()));
        verify(productMapper).deductStockAtomic(101L, 2);
        verify(cartMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(orderItemMapper).insert(any(OrderItem.class));
    }

    @Test
    void emptyCartIsRejectedAndReleasesIdempotentToken() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("corporate")));
        assertTrue(ex.getMessage().contains("购物车没有可结算的商品"));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(productMapper, never()).deductStockAtomic(any(), anyInt());
        verify(idempotentService).release(1L);
    }

    @Test
    void invalidPayTypeIsRejectedBeforeCartIsLoaded() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("bitcoin")));
        assertTrue(ex.getMessage().contains("非法支付方式"));
        verify(cartMapper, never()).selectList(any());
        verify(tamperGuardService).report(eq(7L), eq("order"), eq("payType"), eq("bitcoin"), anyString(), anyString(), any());
        verify(idempotentService).release(1L);
    }

    @Test
    void negativePointsAreRejectedAsTamperBeforeCartLoad() {
        CheckoutDTO d = dto("corporate");
        d.setUsePoints(-1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, d));

        assertTrue(ex.getMessage().contains("使用积分数不能为负"));
        verify(tamperGuardService).report(eq(7L), eq("order"), eq("usePoints"), eq("-1"), eq(">=0"), anyString(), any());
        verify(cartMapper, never()).selectList(any());
    }

    @Test
    void belowMoqCheckoutIsRejectedWithReadableMessage() {
        // P1-17 MOQ：阶梯首档=起订量，下单数量不足直接拒绝且不落单
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 5)));
        Product p = product(101L, "100.00", 100, 1);
        p.setTierPriceJson("[{\"minQty\":10,\"price\":88.00}]");
        when(productMapper.selectById(101L)).thenReturn(p);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("corporate")));

        assertTrue(ex.getMessage().contains("起订量为 10 件"));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(productMapper, never()).deductStockAtomic(any(), anyInt());
        verify(idempotentService).release(1L);
    }

    @Test
    void atMoqCheckoutPassesAndSnapshotsTierPrice() {
        // P1-17 MOQ 边界：正好 minQty 可下单，明细快照命中档单价
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 10)));
        Product p = product(101L, "100.00", 100, 1);
        p.setTierPriceJson("[{\"minQty\":10,\"price\":88.00}]");
        when(productMapper.selectById(101L)).thenReturn(p);

        Order order = orderService.checkout(7L, dto("corporate"));

        assertEquals(0, new BigDecimal("880.00").compareTo(order.getTotalAmount()));
        OrderItem item = insertedItems(1).get(0);
        assertEquals(0, new BigDecimal("88.00").compareTo(item.getPrice()));
    }

    @Test
    void productNotOnSaleIsRejected() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("corporate")));
        assertTrue(ex.getMessage().contains("商品暂不可购买"));
        verify(productMapper, never()).deductStockAtomic(any(), anyInt());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void precheckStockShortageIsRejectedBeforeOrderInsert() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 5)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 4, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("corporate")));
        assertTrue(ex.getMessage().contains("库存不足"));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(productMapper, never()).deductStockAtomic(any(), anyInt());
    }

    @Test
    void atomicStockRaceLosingZeroRowsFailsTheWholeOrder() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        when(productMapper.deductStockAtomic(101L, 1)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkout(7L, dto("corporate")));
        assertTrue(ex.getMessage().contains("并发扣减冲突"));
        // 购物车行不得被删（事务回滚语义），幂等令牌释放
        verify(cartMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(idempotentService).release(1L);
    }

    @Test
    void memberWinningOverNoTierKeepsListUnitPriceOnItemSnapshot() {
        when(userMapper.selectById(7L)).thenReturn(user(1L));
        when(membershipService.levelDiscountRate(7L)).thenReturn(new BigDecimal("0.90"));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        when(pricingFacade.quote(any(), any(), any(), eq(true), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), BigDecimal.ZERO, new BigDecimal("10.00"),
                        PricingFacade.SOURCE_MEMBER, null));

        Order order = orderService.checkout(7L, dto("corporate"));

        assertEquals(0, new BigDecimal("90.00").compareTo(order.getTotalAmount()));
        // 会员胜出：明细必须回落到原价单价（不以阶梯价快照，避免双重让利口径分裂）
        assertEquals(0, new BigDecimal("100.00").compareTo(insertedItems(1).get(0).getPrice()));
        verify(pricingFacade).quote(eq(7L), any(), any(), eq(true), anyList());
    }

    @Test
    void pointsRedeemReducesTotalAndSnapshotsDeduction() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        when(membershipService.redeemPoints(eq(7L), eq(3000), any(), anyString()))
                .thenReturn(new BigDecimal("3.00"));
        CheckoutDTO d = dto("corporate");
        d.setUsePoints(3000);

        Order order = orderService.checkout(7L, d);

        assertEquals(0, new BigDecimal("97.00").compareTo(order.getTotalAmount()));
        assertEquals(0, new BigDecimal("3.00").compareTo(order.getPointDeductAmount()));
        assertEquals(Integer.valueOf(3000), order.getPointDeductPoints());
    }

    @Test
    void termPayTypeCreatesBillAndMarksPrePaid() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));

        Order order = orderService.checkout(7L, dto("term"));

        assertEquals(Integer.valueOf(1), order.getPayStatus());
        verify(creditTermService).createBillForOrder(7L, order.getOrderNo(), new BigDecimal("100.00"));
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(messageService).send(eq(7L), eq("order"), eq("下单成功"), msg.capture(), eq(order.getOrderNo()));
        assertTrue(msg.getValue().contains("账期"));
    }

    @Test
    void liveAttributionIsSetOnlyWhenProductIsMountedInLiveRoom() {
        when(userMapper.selectById(7L)).thenReturn(user(2L));
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00", 10, 1));
        LiveRoom room = new LiveRoom();
        room.setRoomId(55L);
        room.setStatus(1);
        when(liveRoomMapper.selectById(55L)).thenReturn(room);
        when(liveProductMapper.selectByRoomAndProduct(55L, 101L)).thenReturn(new LiveProduct());
        CheckoutDTO d = dto("corporate");
        d.setLiveRoomId(55L);

        Order order = orderService.checkout(7L, d);
        assertEquals(Long.valueOf(55L), order.getLiveId());

        // 未挂载该商品 → 静默不归因，不阻塞下单
        when(liveProductMapper.selectByRoomAndProduct(55L, 101L)).thenReturn(null);
        CheckoutDTO unmounted = dto("corporate");
        unmounted.setLiveRoomId(55L);
        Order second = orderService.checkout(7L, unmounted);
        assertNull(second.getLiveId());
        verify(liveProductMapper, times(2)).selectByRoomAndProduct(55L, 101L);
    }
}