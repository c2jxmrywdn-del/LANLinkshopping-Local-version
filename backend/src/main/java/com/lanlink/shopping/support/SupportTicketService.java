package com.lanlink.shopping.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.entity.SupportTicket;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.SupportTicketMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工客服工单服务：用户提交 → 站内信通知运营 → 运营回复/关闭 → 站内信通知用户。
 * 与 AI 客服互补：AI 处理知识库可答问题，工单承接需要人工跟进的诉求。
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);
    private static final Set<String> CATEGORIES = Set.of("account", "order", "payment", "merchant", "other");
    private static final Set<String> STATUSES = Set.of("OPEN", "PROCESSING", "REPLIED", "CLOSED");

    private final SupportTicketMapper mapper;
    private final UserMapper userMapper;
    private final MessageService messageService;

    public SupportTicketService(SupportTicketMapper mapper, UserMapper userMapper, MessageService messageService) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
    }

    @Transactional
    public SupportTicket submit(Long userId, String category, String title, String content, String contact) {
        if (userId == null) throw new BusinessException("请先登录");
        String t = safe(title);
        String c = safe(content);
        if (t == null || t.length() < 2 || t.length() > 100) throw new BusinessException("标题需 2-100 字");
        if (c == null || c.length() < 5 || c.length() > 2000) throw new BusinessException("问题描述需 5-2000 字");
        String cat = CATEGORIES.contains(category) ? category : "other";
        String ct = safe(contact);
        if (ct != null && ct.length() > 120) ct = ct.substring(0, 120);

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setCategory(cat);
        ticket.setTitle(t);
        ticket.setContent(c);
        ticket.setContact(ct);
        ticket.setStatus("OPEN");
        ticket.setCreateTime(LocalDateTime.now());
        ticket.setUpdateTime(LocalDateTime.now());
        mapper.insert(ticket);

        notifyAdmins(ticket);
        return ticket;
    }

    public List<SupportTicket> myTickets(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<SupportTicket>()
                .eq(SupportTicket::getUserId, userId)
                .orderByDesc(SupportTicket::getId));
    }

    public SupportTicket detail(Long userId, Long id) {
        SupportTicket ticket = mapper.selectById(id);
        if (ticket == null || !ticket.getUserId().equals(userId)) throw new BusinessException("工单不存在");
        return ticket;
    }

    public Map<String, Object> adminList(String status, long page, long size) {
        LambdaQueryWrapper<SupportTicket> qw = new LambdaQueryWrapper<SupportTicket>()
                .orderByDesc(SupportTicket::getId);
        if (StringUtils.hasText(status) && STATUSES.contains(status.trim().toUpperCase())) {
            qw.eq(SupportTicket::getStatus, status.trim().toUpperCase());
        }
        IPage<SupportTicket> result = mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), qw);
        Map<String, Object> out = new HashMap<>();
        out.put("records", result.getRecords());
        out.put("total", result.getTotal());
        out.put("page", result.getCurrent());
        out.put("size", result.getSize());
        // 各状态计数，供工作台概览
        Map<String, Long> counts = new HashMap<>();
        for (String s : STATUSES) {
            counts.put(s, mapper.selectCount(new LambdaQueryWrapper<SupportTicket>().eq(SupportTicket::getStatus, s)));
        }
        out.put("counts", counts);
        return out;
    }

    @Transactional
    public SupportTicket adminReply(Long id, String reply, Long adminId) {
        SupportTicket ticket = mapper.selectById(id);
        if (ticket == null) throw new BusinessException("工单不存在");
        String r = safe(reply);
        if (r == null || r.length() < 2 || r.length() > 2000) throw new BusinessException("回复内容需 2-2000 字");
        ticket.setReply(r);
        ticket.setReplyBy(adminId);
        ticket.setReplyTime(LocalDateTime.now());
        ticket.setStatus("REPLIED");
        ticket.setUpdateTime(LocalDateTime.now());
        mapper.updateById(ticket);
        messageService.send(ticket.getUserId(), "support", "人工客服已回复您的工单",
                "您的工单「" + ticket.getTitle() + "」已有新的回复，请前往「设置 → 人工客服」查看。", String.valueOf(ticket.getId()));
        return ticket;
    }

    @Transactional
    public SupportTicket adminUpdateStatus(Long id, String status, Long adminId) {
        SupportTicket ticket = mapper.selectById(id);
        if (ticket == null) throw new BusinessException("工单不存在");
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(s)) throw new BusinessException("状态非法");
        ticket.setStatus(s);
        ticket.setUpdateTime(LocalDateTime.now());
        mapper.updateById(ticket);
        if ("CLOSED".equals(s)) {
            messageService.send(ticket.getUserId(), "support", "人工客服工单已关闭",
                    "您的工单「" + ticket.getTitle() + "」已处理完成并关闭。", String.valueOf(ticket.getId()));
        }
        return ticket;
    }

    /** 通知所有运营（管理员角色）有新工单；失败不影响提交主流程。 */
    private void notifyAdmins(SupportTicket ticket) {
        try {
            List<User> admins = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .eq(User::getRoleId, UserRoles.ADMIN_ROLE_ID).select(User::getUserId));
            for (User a : admins) {
                if (a.getUserId() == null) continue;
                messageService.send(a.getUserId(), "support", "新的人工客服工单待处理",
                        "用户提交了工单「" + ticket.getTitle() + "」，请前往客服工作台处理。", String.valueOf(ticket.getId()));
            }
        } catch (Exception e) {
            log.warn("[support-ticket] notify admins failed: {}", e.getMessage());
        }
    }

    private static String safe(String v) {
        if (v == null) return null;
        String s = v.replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.isEmpty() ? null : s;
    }
}
