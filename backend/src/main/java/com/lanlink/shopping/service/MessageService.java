package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Message;
import com.lanlink.shopping.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 站内消息服务
 */
@Service
public class MessageService {

    private final MessageMapper mapper;

    public MessageService(MessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 分页查询（按类型可选筛选），同时返回该用户未读总数 */
    public Map<String, Object> page(Long userId, long page, long size, String type) {
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId)
                .orderByDesc(Message::getId);
        if (type != null && !type.isBlank()) qw.eq(Message::getType, type.trim());
        IPage<Message> result = mapper.selectPage(new Page<>(page, size), qw);
        Map<String, Object> out = new HashMap<>();
        out.put("records", result.getRecords());
        out.put("total", result.getTotal());
        out.put("page", result.getCurrent());
        out.put("size", result.getSize());
        out.put("unread", unreadCount(userId));
        return out;
    }

    public long unreadCount(Long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId).eq(Message::getReadFlag, 0));
    }

    /** 单条已读：校验归属 */
    public void read(Long userId, Long id) {
        Message m = mapper.selectById(id);
        if (m == null || !m.getUserId().equals(userId)) throw new BusinessException("消息不存在");
        if (m.getReadFlag() == null || m.getReadFlag() != 1) {
            m.setReadFlag(1);
            mapper.updateById(m);
        }
    }

    /** 全部已读 */
    @Transactional
    public void readAll(Long userId) {
        mapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getUserId, userId).eq(Message::getReadFlag, 0)
                .set(Message::getReadFlag, 1));
    }

    /** 发送一条消息（订单埋点等场景调用） */
    public void send(Long userId, String type, String title, String content, String relatedNo) {
        Message m = new Message();
        m.setUserId(userId);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setRelatedNo(relatedNo == null ? "" : relatedNo);
        m.setReadFlag(0);
        m.setCreateTime(LocalDateTime.now());
        mapper.insert(m);
    }
}