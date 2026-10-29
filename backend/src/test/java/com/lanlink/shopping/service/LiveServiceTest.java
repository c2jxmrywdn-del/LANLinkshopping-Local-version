package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LiveProduct;
import com.lanlink.shopping.entity.LiveRoom;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.LiveCommentMapper;
import com.lanlink.shopping.mapper.LiveProductMapper;
import com.lanlink.shopping.mapper.LiveRoomMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.service.impl.LiveServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 直播商业闭环单测：创建归属校验/真实计数/进出场峰值/点击埋点/复盘聚合/强制关播。
 */
class LiveServiceTest {

    private LiveRoomMapper liveRoomMapper;
    private LiveProductMapper liveProductMapper;
    private LiveCommentMapper liveCommentMapper;
    private ProductMapper productMapper;
    private com.lanlink.shopping.mapper.EnterpriseMapper enterpriseMapper;
    private com.lanlink.shopping.service.MessageService messageService;
    private LiveServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        liveRoomMapper = mock(LiveRoomMapper.class);
        liveProductMapper = mock(LiveProductMapper.class);
        liveCommentMapper = mock(LiveCommentMapper.class);
        productMapper = mock(ProductMapper.class);
        enterpriseMapper = mock(com.lanlink.shopping.mapper.EnterpriseMapper.class);
        messageService = mock(com.lanlink.shopping.service.MessageService.class);
        service = new LiveServiceImpl(liveRoomMapper, liveProductMapper, liveCommentMapper,
                productMapper, merchantMapper(), enterpriseMapper);
        // messageService 是字段可选注入（@Autowired required=false），测试用反射注入 mock
        java.lang.reflect.Field f = LiveServiceImpl.class.getDeclaredField("messageService");
        f.setAccessible(true);
        f.set(service, messageService);
        // 纯 mock 环境需初始化实体元信息（lambda 列名解析）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LiveRoom.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LiveProduct.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
    }

    private com.lanlink.shopping.mapper.MerchantMapper merchantMapper() {
        return mock(com.lanlink.shopping.mapper.MerchantMapper.class);
    }

    private Product product(long prodId, long merId) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        p.setTitle("测试商品");
        p.setPrice(new BigDecimal("100.00"));
        return p;
    }

    private LiveRoom room(long roomId, long merId, int status) {
        LiveRoom r = new LiveRoom();
        r.setRoomId(roomId);
        r.setMerId(merId);
        r.setStatus(status);
        r.setViewerCount(5);
        r.setPeakViewerCount(8);
        return r;
    }

    // ===== 创建：归属校验 + 真实计数 =====

    @Test
    void createRoomRejectsForeignProduct() {
        when(productMapper.selectById(99L)).thenReturn(product(99L, 999L)); // 别家商品

        LiveRoom room = new LiveRoom();
        LiveProduct lp = new LiveProduct();
        lp.setProdId(99L);
        assertThrows(BusinessException.class, () -> service.createRoom(1L, 3L, room, List.of(lp)));
        verify(liveRoomMapper, never()).insert(any(LiveRoom.class));
    }

    @Test
    void createRoomStartsWithRealViewerCount() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, 1L));

        LiveRoom room = new LiveRoom();
        room.setTitle("测试直播");
        LiveProduct lp = new LiveProduct();
        lp.setProdId(1L);
        service.createRoom(1L, 3L, room, List.of(lp));

        assertEquals(1, room.getViewerCount(), "开播真实在线=主播本人，不再伪造 68");
        assertEquals(1, room.getPeakViewerCount());
        assertEquals(0, lp.getClickCount());
        verify(liveRoomMapper).insert(room);
        verify(liveProductMapper).insert(lp);
    }

    // ===== 进出场：峰值只增不减 =====

    @Test
    void joinAndLeaveDelegateToAtomicAdjust() {
        service.joinRoom(1L);
        verify(liveRoomMapper).adjustViewers(1L, 1);
        service.leaveRoom(1L);
        verify(liveRoomMapper).adjustViewers(1L, -1);
        // null 房间不炸
        service.joinRoom(null);
        service.leaveRoom(null);
        verify(liveRoomMapper, times(2)).adjustViewers(any(), anyInt());
    }

    // ===== 点击埋点 =====

    @Test
    void trackProductClickIncrementsAndReturns() {
        LiveProduct lp = new LiveProduct();
        lp.setId(7L);
        lp.setProdId(1L);
        lp.setClickCount(4);
        when(liveProductMapper.selectByRoomAndProduct(1L, 1L)).thenReturn(lp);
        when(productMapper.selectById(1L)).thenReturn(product(1L, 1L));

        LiveProduct out = service.trackProductClick(1L, 1L);

        verify(liveProductMapper).incrementClick(7L);
        assertEquals(5, out.getClickCount());
        assertNotNull(out.getProduct());
    }

    @Test
    void trackProductClickReturnsNullWhenNotMounted() {
        when(liveProductMapper.selectByRoomAndProduct(1L, 99L)).thenReturn(null);
        assertNull(service.trackProductClick(1L, 99L));
        verify(liveProductMapper, never()).incrementClick(any());
    }

    // ===== 状态机：关播写 end_time 由 SQL CASE 承担；越界状态拒绝 =====

    @Test
    void updateRoomStatusRejectsInvalidState() {
        assertFalse(service.updateRoomStatus(1L, 5));
        assertFalse(service.updateRoomStatus(1L, null));
        verify(liveRoomMapper, never()).updateRoomStatus(any(), any());
    }

    @Test
    void updateRoomStatusDelegatesValidState() {
        when(liveRoomMapper.updateRoomStatus(1L, 2)).thenReturn(1);
        assertTrue(service.updateRoomStatus(1L, 2));
    }

    @Test
    void startUsesConditionalApprovedUpdate() {
        // 开播走 startIfApproved 条件更新（TOCTOU 防护：与平台驳回并发时 UPDATE 侧再校验）
        LiveRoom approved = room(2L, 1L, 0);
        approved.setReviewStatus(1);
        when(liveRoomMapper.selectById(2L)).thenReturn(approved);
        when(liveRoomMapper.startIfApproved(2L)).thenReturn(1);
        assertTrue(service.updateRoomStatus(2L, 1));
        verify(liveRoomMapper).startIfApproved(2L);
        verify(liveRoomMapper, never()).updateRoomStatus(2L, 1);
    }

    // ===== 管理端强制关播 =====

    @Test
    void adminForceCloseAlsoRevokesApproval() {
        // 强关必须同时撤销开播资格（review_status=2），否则商户可凭既有 approval 立即复播
        when(liveRoomMapper.selectById(1L)).thenReturn(room(1L, 1L, 1));
        when(liveRoomMapper.updateRoomStatus(1L, 2)).thenReturn(1);
        assertTrue(service.adminForceClose(1L));
        ArgumentCaptor<LiveRoom> cap = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomMapper).updateById(cap.capture());
        assertEquals(2, cap.getValue().getReviewStatus());

        when(liveRoomMapper.selectById(2L)).thenReturn(room(2L, 1L, 2)); // 已结束
        assertFalse(service.adminForceClose(2L));

        when(liveRoomMapper.selectById(3L)).thenReturn(null);
        assertFalse(service.adminForceClose(3L));
    }

    // ===== 讲解置顶：完整校验（房间直播中 + 商品已挂载） =====

    @Test
    void pinProductValidatesRoomStatusAndMounting() {
        // 房间不存在
        when(liveRoomMapper.selectById(9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.pinProduct(9L, 1L));

        // 已结束房间不可置顶
        LiveRoom ended = room(2L, 1L, 2);
        when(liveRoomMapper.selectById(2L)).thenReturn(ended);
        assertThrows(BusinessException.class, () -> service.pinProduct(2L, 1L));

        // 直播中但商品未挂载
        LiveRoom live = room(1L, 1L, 1);
        when(liveRoomMapper.selectById(1L)).thenReturn(live);
        when(liveProductMapper.selectByRoomAndProduct(1L, 99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.pinProduct(1L, 99L));

        // 正常置顶
        when(liveProductMapper.selectByRoomAndProduct(1L, 1L)).thenReturn(new LiveProduct());
        when(liveRoomMapper.updatePinnedProduct(1L, 1L)).thenReturn(1);
        assertTrue(service.pinProduct(1L, 1L));
    }

    // ===== 弹幕：msgType 白名单 + 房间存在 + 长度 =====

    @Test
    void addCommentWhitelistsMsgTypeAndValidatesRoom() {
        LiveRoom live = room(1L, 1L, 1);
        when(liveRoomMapper.selectById(1L)).thenReturn(live);

        // 伪造 system 公告被降级为 chat
        service.addComment(1L, 7L, "路人", "", "大家好", "system");
        ArgumentCaptor<com.lanlink.shopping.entity.LiveComment> cap =
                ArgumentCaptor.forClass(com.lanlink.shopping.entity.LiveComment.class);
        verify(liveCommentMapper).insert(cap.capture());
        assertEquals("chat", cap.getValue().getMsgType());

        // 房间不存在
        when(liveRoomMapper.selectById(9L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.addComment(9L, 7L, "路人", "", "hi", "chat"));
    }

    // ===== 平台审核：开播准入门槛 =====

    @Test
    void startRequiresPlatformApproval() {
        LiveRoom pending = room(1L, 1L, 0);
        pending.setReviewStatus(0); // 待审核
        when(liveRoomMapper.selectById(1L)).thenReturn(pending);
        assertThrows(BusinessException.class, () -> service.updateRoomStatus(1L, 1));
        verify(liveRoomMapper, never()).startIfApproved(any());

        LiveRoom approved = room(2L, 1L, 0);
        approved.setReviewStatus(1);
        when(liveRoomMapper.selectById(2L)).thenReturn(approved);
        when(liveRoomMapper.startIfApproved(2L)).thenReturn(1);
        assertTrue(service.updateRoomStatus(2L, 1));
    }

    @Test
    void reviewApprovesOrRejectsAndNotifies() {
        LiveRoom r = room(1L, 1L, 0);
        r.setUserId(3L);
        r.setTitle("测试直播");
        when(liveRoomMapper.selectById(1L)).thenReturn(r);

        // 批准：review_status=1，无驳回原因
        assertTrue(service.reviewRoom(1L, true, null, 9L));
        ArgumentCaptor<LiveRoom> cap = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getReviewStatus());
        assertNull(cap.getValue().getRejectReason());
        verify(messageService).send(eq(3L), eq("live"), contains("通过"), anyString(), anyString());

        // 驳回：review_status=2 + 原因 + 直播中房间下线
        LiveRoom live = room(2L, 1L, 1);
        live.setUserId(3L);
        live.setTitle("违规直播");
        when(liveRoomMapper.selectById(2L)).thenReturn(live);
        assertTrue(service.reviewRoom(2L, false, "内容违规", 9L));
        cap = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomMapper, atLeast(1)).updateById(cap.capture());
        assertEquals(2, cap.getValue().getReviewStatus());
        assertEquals("内容违规", cap.getValue().getRejectReason());
        verify(liveRoomMapper).updateRoomStatus(2L, 2); // 下线处置
    }

    @Test
    void createRoomDefaultsToPendingReview() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, 1L));

        LiveRoom room = new LiveRoom();
        room.setTitle("新直播间");
        LiveProduct lp = new LiveProduct();
        lp.setProdId(1L);
        service.createRoom(1L, 3L, room, List.of(lp));

        // 平台准入：新房间一律待审核 + 预告态，批准后方可开播
        assertEquals(0, room.getReviewStatus());
        assertEquals(0, room.getStatus());
    }
}
