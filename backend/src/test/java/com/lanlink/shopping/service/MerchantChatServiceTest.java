package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.MerchantChat;
import com.lanlink.shopping.entity.MerchantChatMsg;
import com.lanlink.shopping.mapper.MerchantChatMapper;
import com.lanlink.shopping.mapper.MerchantChatMsgMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 聊天消息服务单测：文本消息原行为不回归 + 媒体消息（image/file/voice）字段与占位摘要。
 * 纯 JUnit5 + Mockito（无 Spring 上下文），范例同 UserServiceTest。
 */
class MerchantChatServiceTest {

    private MerchantChatMapper chatMapper;
    private MerchantChatMsgMapper msgMapper;
    private MerchantMapper merchantMapper;
    private MerchantChatService service;
    private MerchantChat chat;

    @BeforeEach
    void setUp() {
        chatMapper = mock(MerchantChatMapper.class);
        msgMapper = mock(MerchantChatMsgMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        service = new MerchantChatService(chatMapper, msgMapper, merchantMapper,
                mock(ProductMapper.class), mock(UserMapper.class),
                mock(com.lanlink.shopping.mapper.EnterpriseMapper.class));
        chat = new MerchantChat();
        chat.setId(100L);
        chat.setBuyerId(1L);
        chat.setMerId(10L);
        chat.setUnreadCount(0);
        when(chatMapper.selectById(100L)).thenReturn(chat);
        // 纯 mock 环境需初始化实体元信息（lambda 列名解析，参照 LiveServiceTest）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Merchant.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MerchantChatMsg.class);
    }

    // ==================== 文本消息（原行为不回归） ====================

    @Test
    void textMessageInsertsTrimmedContentAndUpdatesSummary() {
        Map<String, Object> body = new HashMap<>();
        body.put("content", "  你好，这个规格有现货吗？  ");
        Map<String, Object> out = service.send(1L, 100L, "buyer", body);

        ArgumentCaptor<MerchantChatMsg> captor = ArgumentCaptor.forClass(MerchantChatMsg.class);
        verify(msgMapper, times(1)).insert(captor.capture());
        MerchantChatMsg msg = captor.getValue();
        assertEquals("你好，这个规格有现货吗？", msg.getContent());
        assertEquals("text", msg.getMsgType());
        assertEquals("buyer", msg.getSenderRole());
        assertEquals(1L, msg.getSenderId());
        assertNull(msg.getMediaUrl());
        assertEquals(Integer.valueOf(0), msg.getReadFlag());

        // 会话摘要 + 对方未读 +1
        assertEquals("你好，这个规格有现货吗？", chat.getLastMsgContent());
        assertEquals("buyer", chat.getLastSenderRole());
        assertEquals(1, chat.getUnreadCount());
        verify(chatMapper, times(1)).updateById(chat);
        assertSame(msg, out.get("message"));
        assertEquals(100L, out.get("sessionId"));
    }

    @Test
    void emptyTextRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", Map.of("content", "   ")));
        assertTrue(ex.getMessage().contains("消息内容不能为空"));
        // 缺 content 字段同样视为空
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", new HashMap<>()));
        assertTrue(ex2.getMessage().contains("消息内容不能为空"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void textOver500Rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", Map.of("content", "字".repeat(501))));
        assertTrue(ex.getMessage().contains("消息最多 500 个字符"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    // ==================== 越权门禁（mustAccess） ====================

    @Test
    void sessionNotFoundRejected() {
        when(chatMapper.selectById(999L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 999L, "buyer", Map.of("content", "hi")));
        assertTrue(ex.getMessage().contains("会话不存在"));
    }

    @Test
    void buyerNotOwnerRejected() {
        chat.setBuyerId(2L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", Map.of("content", "hi")));
        assertTrue(ex.getMessage().contains("无权访问该会话"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void merchantWithoutOwnedMerchantRejected() {
        // 商户身份：merchantMapper 查无档案 → merId 无法推导 → 越权
        when(merchantMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(9L, 100L, "merchant", Map.of("content", "hi")));
        assertTrue(ex.getMessage().contains("无权访问该会话"));
    }

    @Test
    void merchantRoleAllowedWhenOwnsSession() {
        Merchant m = new Merchant();
        m.setMerId(10L);
        when(merchantMapper.selectOne(any())).thenReturn(m);
        Map<String, Object> out = service.send(9L, 100L, "merchant", Map.of("content", "有的，明天可发"));
        ArgumentCaptor<MerchantChatMsg> captor = ArgumentCaptor.forClass(MerchantChatMsg.class);
        verify(msgMapper).insert(captor.capture());
        assertEquals("merchant", captor.getValue().getSenderRole());
        assertEquals(9L, captor.getValue().getSenderId());
        assertEquals(1, chat.getUnreadCount());
        verify(chatMapper).updateById(chat);
        assertNotNull(out.get("message"));
    }

    // ==================== 媒体消息 ====================

    @Test
    void mediaWithoutUrlRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "image");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", body));
        assertTrue(ex.getMessage().contains("媒体地址不能为空"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void mediaUrlOutsideChatDirRejected() {
        // 媒体地址必须来自聊天上传目录，防止把任意路径写进消息
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "image");
        body.put("mediaUrl", "/api/uploads/licenses/1_1.png");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", body));
        assertTrue(ex.getMessage().contains("非法的媒体地址"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void illegalMsgTypeRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "video");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1.mp4");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", body));
        assertTrue(ex.getMessage().contains("不支持的消息类型"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void voiceSecondsMissingOrOutOfRangeRejected() {
        Map<String, Object> base = voiceBody();
        // 缺失
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", base));
        assertTrue(ex1.getMessage().contains("语音时长"));
        // 0 秒
        Map<String, Object> zero = voiceBody();
        zero.put("voiceSeconds", 0);
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", zero));
        assertTrue(ex2.getMessage().contains("语音时长"));
        // 301 秒
        Map<String, Object> over = voiceBody();
        over.put("voiceSeconds", 301);
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", over));
        assertTrue(ex3.getMessage().contains("语音时长"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void mediaNameOverLimitRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "file");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1.bin");
        body.put("mediaName", "名".repeat(201));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", body));
        assertTrue(ex.getMessage().contains("文件名最多 200 个字符"));
    }

    @Test
    void negativeMediaSizeRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "image");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1.jpg");
        body.put("mediaSize", -1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.send(1L, 100L, "buyer", body));
        assertTrue(ex.getMessage().contains("文件大小非法"));
        verify(msgMapper, never()).insert(any(MerchantChatMsg.class));
    }

    @Test
    void imageMessageInsertsWithPlaceholderSummary() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "image");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1730000000000.jpg");
        body.put("mediaName", "样品照片.jpg");
        body.put("mediaSize", 12345);
        service.send(1L, 100L, "buyer", body);

        ArgumentCaptor<MerchantChatMsg> captor = ArgumentCaptor.forClass(MerchantChatMsg.class);
        verify(msgMapper, times(1)).insert(captor.capture());
        MerchantChatMsg msg = captor.getValue();
        assertEquals("image", msg.getMsgType());
        assertEquals("/api/uploads/chat/202610/1_1730000000000.jpg", msg.getMediaUrl());
        assertEquals("样品照片.jpg", msg.getMediaName());
        assertEquals(12345L, msg.getMediaSize());
        assertEquals("[图片]", msg.getContent());
        assertNull(msg.getVoiceSeconds());
        assertEquals(Integer.valueOf(0), msg.getReadFlag());
        // 会话摘要用同一占位，未读 +1
        assertEquals("[图片]", chat.getLastMsgContent());
        assertEquals(1, chat.getUnreadCount());
        verify(chatMapper).updateById(chat);
    }

    @Test
    void fileMessagePlaceholderIncludesName() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "file");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1730000000001.bin");
        body.put("mediaName", "报价单.pdf");
        body.put("mediaSize", 204800);
        service.send(1L, 100L, "buyer", body);

        ArgumentCaptor<MerchantChatMsg> captor = ArgumentCaptor.forClass(MerchantChatMsg.class);
        verify(msgMapper).insert(captor.capture());
        assertEquals("file", captor.getValue().getMsgType());
        assertEquals("[文件] 报价单.pdf", captor.getValue().getContent());
        assertEquals("[文件] 报价单.pdf", chat.getLastMsgContent());
    }

    @Test
    void voiceMessageStoresSeconds() {
        Map<String, Object> body = voiceBody();
        body.put("voiceSeconds", 12);
        service.send(1L, 100L, "buyer", body);

        ArgumentCaptor<MerchantChatMsg> captor = ArgumentCaptor.forClass(MerchantChatMsg.class);
        verify(msgMapper).insert(captor.capture());
        MerchantChatMsg msg = captor.getValue();
        assertEquals("voice", msg.getMsgType());
        assertEquals(Integer.valueOf(12), msg.getVoiceSeconds());
        assertEquals("[语音]", msg.getContent());
        assertEquals("[语音]", chat.getLastMsgContent());
        assertEquals(1, chat.getUnreadCount());
    }

    private Map<String, Object> voiceBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("msgType", "voice");
        body.put("mediaUrl", "/api/uploads/chat/202610/1_1730000000002.webm");
        return body;
    }
}
