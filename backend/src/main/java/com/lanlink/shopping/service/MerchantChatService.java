package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.MerchantChat;
import com.lanlink.shopping.entity.MerchantChatMsg;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.MerchantChatMapper;
import com.lanlink.shopping.mapper.MerchantChatMsgMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家↔商家 对话服务（商品详情页「联系商家」闭环）。
 *
 * 身份与越权：
 *  - 买家侧：会话归属 chat.buyer_id == 当前用户；
 *  - 商户侧：会话归属 chat.mer_id == mustMerchant(userId).getMerId()（merId 永远由登录态推导）；
 *  - sender_role 由身份强制写入，不透传前端。
 * 未读：unread_count 存"对方"未读数；进入会话（显式 /read）时按角色清零并回执消息已读。
 */
@Service
public class MerchantChatService {

    private static final int MAX_CONTENT = 500;
    /** 媒体消息文件名上限（对齐 media_name varchar(200)） */
    private static final int MAX_MEDIA_NAME = 200;
    /** 语音时长上限（秒，发送端校验 1-300） */
    private static final int MAX_VOICE_SECONDS = 300;
    private static final String ROLE_BUYER = "buyer";
    private static final String ROLE_MERCHANT = "merchant";

    private final MerchantChatMapper chatMapper;
    private final MerchantChatMsgMapper msgMapper;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final com.lanlink.shopping.mapper.EnterpriseMapper enterpriseMapper;

    public MerchantChatService(MerchantChatMapper chatMapper, MerchantChatMsgMapper msgMapper,
                               MerchantMapper merchantMapper, ProductMapper productMapper,
                               UserMapper userMapper, com.lanlink.shopping.mapper.EnterpriseMapper enterpriseMapper) {
        this.chatMapper = chatMapper;
        this.msgMapper = msgMapper;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.userMapper = userMapper;
        this.enterpriseMapper = enterpriseMapper;
    }

    // ==================== 买家侧 ====================

    /** 从商品详情页发起：校验商品归属商家后 find-or-create，返回会话（含商家名/商品名供聊天页展示） */
    @Transactional
    public Map<String, Object> startFromProduct(Long userId, Long merId, Long prodId) {
        if (merId == null) throw new BusinessException("缺少商家标识");
        Product p = prodId == null ? null : productMapper.selectById(prodId);
        if (p == null || !merId.equals(p.getMerId())) throw new BusinessException("商品不存在或不属于该商家");
        chatMapper.findOrCreate(userId, merId, prodId);
        MerchantChat chat = chatMapper.selectByBuyerAndMer(userId, merId);
        return sessionView(chat, ROLE_BUYER);
    }

    /** 买家会话列表（含对方商家名 + 未读） */
    public List<Map<String, Object>> buyerSessions(Long userId) {
        List<MerchantChat> chats = chatMapper.selectList(Wrappers.<MerchantChat>lambdaQuery()
                .eq(MerchantChat::getBuyerId, userId)
                .eq(MerchantChat::getStatus, "active")
                .orderByDesc(MerchantChat::getUpdateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MerchantChat c : chats) {
            Map<String, Object> row = baseRow(c, ROLE_BUYER);
            Merchant m = merchantMapper.selectById(c.getMerId());
            row.put("peerName", peerNameOfMerchant(m, enterpriseMapper));
            out.add(row);
        }
        return out;
    }

    /** 买家未读总数（顶栏角标 P1 预留） */
    public long buyerUnread(Long userId) {
        return chatMapper.buyerUnreadTotal(userId);
    }

    // ==================== 商户侧 ====================

    /** 商户会话分页（含买家昵称 + 未读） */
    public Map<String, Object> merchantSessions(Long userId, long page, long size) {
        Merchant m = mustMerchant(userId);
        Page<MerchantChat> po = chatMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                Wrappers.<MerchantChat>lambdaQuery()
                        .eq(MerchantChat::getMerId, m.getMerId())
                        .eq(MerchantChat::getStatus, "active")
                        .orderByDesc(MerchantChat::getUpdateTime));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MerchantChat c : po.getRecords()) {
            Map<String, Object> row = baseRow(c, ROLE_MERCHANT);
            User buyer = c.getBuyerId() == null ? null : userMapper.selectById(c.getBuyerId());
            row.put("peerName", buyer == null ? "买家" : (buyer.getNickname() == null ? "买家" : buyer.getNickname()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", po.getTotal());
        out.put("records", rows);
        return out;
    }

    /** 商户未读总数（商户中心侧栏角标） */
    public long merchantUnread(Long userId) {
        Merchant m = mustMerchant(userId);
        return chatMapper.merchantUnreadTotal(m.getMerId());
    }

    // ==================== 会话与消息（双端共用，归属校验后按角色操作） ====================

    /** 会话消息（增量 afterId；进入即视为已读：清会话未读 + 消息回执） */
    public Map<String, Object> messages(Long userId, Long sessionId, long afterId, String role) {
        MerchantChat chat = mustAccess(userId, sessionId, role);
        List<MerchantChatMsg> list = msgMapper.selectList(Wrappers.<MerchantChatMsg>lambdaQuery()
                .eq(MerchantChatMsg::getSessionId, sessionId)
                .gt(afterId > 0, MerchantChatMsg::getId, afterId)
                .orderByAsc(MerchantChatMsg::getId)
                .last("LIMIT 200"));
        String opposite = ROLE_BUYER.equals(role) ? ROLE_MERCHANT : ROLE_BUYER;
        chatMapper.clearUnread(sessionId, opposite);
        msgMapper.markRead(sessionId, opposite);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionView(chat, role));
        out.put("messages", list);
        out.put("serverTime", LocalDateTime.now());
        return out;
    }

    /**
     * 发送消息：sender_role 由身份强制写入；维护会话摘要与对方未读。
     * body 兼容两种形态：{ content } 文本消息（原行为不变）；
     * { msgType: image/file/voice, mediaUrl, mediaName?, mediaSize?, voiceSeconds?(仅 voice) } 媒体消息，
     * 媒体 URL 必须来自本端上传目录 /api/uploads/chat/（由 ChatMediaService 产生），content 存占位文本。
     */
    @Transactional
    public Map<String, Object> send(Long userId, Long sessionId, String role, Map<String, Object> body) {
        MerchantChat chat = mustAccess(userId, sessionId, role);
        String msgType = body == null || body.get("msgType") == null || String.valueOf(body.get("msgType")).isBlank()
                ? "text" : String.valueOf(body.get("msgType")).trim();

        MerchantChatMsg msg = new MerchantChatMsg();
        msg.setSessionId(sessionId);
        msg.setSenderRole(role);
        msg.setSenderId(userId);
        msg.setReadFlag(0);
        msg.setCreateTime(LocalDateTime.now());

        String summary;
        if ("text".equals(msgType)) {
            Object contentObj = body == null ? null : body.get("content");
            String text = contentObj == null ? "" : String.valueOf(contentObj).trim();
            if (text.isEmpty()) throw new BusinessException("消息内容不能为空");
            if (text.length() > MAX_CONTENT) throw new BusinessException("消息最多 " + MAX_CONTENT + " 个字符");
            msg.setMsgType("text");
            msg.setContent(text);
            summary = text;
        } else {
            if (!"image".equals(msgType) && !"file".equals(msgType) && !"voice".equals(msgType)) {
                throw new BusinessException("不支持的消息类型");
            }
            String mediaUrl = body.get("mediaUrl") == null ? "" : String.valueOf(body.get("mediaUrl")).trim();
            if (mediaUrl.isEmpty()) throw new BusinessException("媒体地址不能为空");
            if (!mediaUrl.startsWith("/api/uploads/chat/")) throw new BusinessException("非法的媒体地址");
            String mediaName = body.get("mediaName") == null ? null : String.valueOf(body.get("mediaName")).trim();
            if (mediaName != null && mediaName.length() > MAX_MEDIA_NAME) {
                throw new BusinessException("文件名最多 " + MAX_MEDIA_NAME + " 个字符");
            }
            Long mediaSize = toLong(body.get("mediaSize"));
            if (mediaSize != null && mediaSize < 0) throw new BusinessException("文件大小非法");
            Long voiceSeconds = toLong(body.get("voiceSeconds"));
            if ("voice".equals(msgType) && (voiceSeconds == null || voiceSeconds < 1 || voiceSeconds > MAX_VOICE_SECONDS)) {
                throw new BusinessException("语音时长需在 1-" + MAX_VOICE_SECONDS + " 秒之间");
            }

            msg.setMsgType(msgType);
            msg.setMediaUrl(mediaUrl);
            msg.setMediaName(mediaName);
            msg.setMediaSize(mediaSize);
            if ("voice".equals(msgType)) msg.setVoiceSeconds(voiceSeconds.intValue());
            summary = placeholder(msgType, mediaName);
            msg.setContent(summary);
        }

        msgMapper.insert(msg);

        chat.setLastMsgContent(summary.length() > 200 ? summary.substring(0, 200) : summary);
        chat.setLastSenderRole(role);
        chat.setUnreadCount((chat.getUnreadCount() == null ? 0 : chat.getUnreadCount()) + 1);
        chat.setUpdateTime(LocalDateTime.now());
        chatMapper.updateById(chat);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", msg);
        out.put("sessionId", sessionId);
        return out;
    }

    /** 媒体消息占位文本（同时作为会话 lastMsgContent 摘要）：image→[图片]、file→[文件] 名、voice→[语音] */
    private static String placeholder(String msgType, String mediaName) {
        return switch (msgType) {
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            default -> (mediaName == null || mediaName.isBlank()) ? "[文件]" : "[文件] " + mediaName;
        };
    }

    /** body 数值字段宽容解析：Number 直取，数字字符串转换，其余按缺失处理 */
    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 内部 ====================

    /** 会话视图（双端字段一致：peer 名称由调用方补充） */
    private Map<String, Object> sessionView(MerchantChat c, String role) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sessionId", c.getId());
        s.put("merId", c.getMerId());
        s.put("buyerId", c.getBuyerId());
        s.put("prodId", c.getProdId());
        s.put("lastMsg", c.getLastMsgContent());
        s.put("unreadCount", unreadFor(c, role));
        s.put("updateTime", c.getUpdateTime());
        if (c.getProdId() != null) {
            Product p = productMapper.selectById(c.getProdId());
            if (p != null) {
                s.put("prodTitle", p.getTitle());
                s.put("prodCover", p.getCoverUrl());
                s.put("prodPrice", p.getPrice());
            }
        }
        Merchant m = merchantMapper.selectById(c.getMerId());
        s.put("merchantName", peerNameOfMerchant(m, enterpriseMapper));
        return s;
    }

    private Map<String, Object> baseRow(MerchantChat c, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", c.getId());
        row.put("prodId", c.getProdId());
        row.put("lastMsg", c.getLastMsgContent());
        row.put("lastSenderRole", c.getLastSenderRole());
        row.put("unreadCount", unreadFor(c, role));
        row.put("updateTime", c.getUpdateTime());
        if (c.getProdId() != null) {
            Product p = productMapper.selectById(c.getProdId());
            row.put("prodTitle", p == null ? "商品已下架" : p.getTitle());
            row.put("prodCover", p == null ? null : p.getCoverUrl());
        }
        return row;
    }

    /** 未读归属：最后发送方为对方时，当前角色才计未读 */
    private static long unreadFor(MerchantChat c, String role) {
        if (c.getUnreadCount() == null || c.getUnreadCount() <= 0) return 0;
        String last = c.getLastSenderRole() == null ? "" : c.getLastSenderRole();
        boolean mine = (ROLE_BUYER.equals(role) && ROLE_BUYER.equals(last))
                || (ROLE_MERCHANT.equals(role) && ROLE_MERCHANT.equals(last));
        return mine ? 0 : c.getUnreadCount();
    }

    /** 商家显示名：优先所属企业名（与商户中心侧栏口径一致），兜底 "商家 #id" */
    private static String peerNameOfMerchant(Merchant m, com.lanlink.shopping.mapper.EnterpriseMapper enterpriseMapper) {
        if (m == null) return "商家";
        if (m.getEntId() != null && enterpriseMapper != null) {
            com.lanlink.shopping.entity.Enterprise ent = enterpriseMapper.selectById(m.getEntId());
            if (ent != null && ent.getName() != null && !ent.getName().isBlank()) return ent.getName();
        }
        return "商家 #" + m.getMerId();
    }

    /** 越权门禁：买家校验 buyer_id、商户校验 mer_id（商户 merId 由登录态推导） */
    private MerchantChat mustAccess(Long userId, Long sessionId, String role) {
        MerchantChat c = chatMapper.selectById(sessionId);
        if (c == null) throw new BusinessException("会话不存在");
        if (ROLE_BUYER.equals(role)) {
            if (!userId.equals(c.getBuyerId())) throw new BusinessException("无权访问该会话");
        } else {
            Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                    .eq(Merchant::getUserId, userId));
            if (m == null || !m.getMerId().equals(c.getMerId())) throw new BusinessException("无权访问该会话");
        }
        return c;
    }

    private Merchant mustMerchant(Long userId) {
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getUserId, userId));
        if (m == null) throw new BusinessException("仅入驻商户可查看客户消息");
        return m;
    }
}
