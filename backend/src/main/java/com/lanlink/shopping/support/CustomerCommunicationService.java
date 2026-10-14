package com.lanlink.shopping.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persists the customer-service conversation workspace.
 * Only a random visitor token is used for anonymous web visitors; IPs, cookies,
 * order data and account profiles are never copied into conversation records.
 */
@Service
public class CustomerCommunicationService {
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d{1,3}[ -]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{12,}(?!\\d)");
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|密码|token|secret|api[_ -]?key|access[_ -]?token)\\s*[:=：]\\s*[^\\s,;，；]+");
    private static final Pattern PERSONAL_FIELD_VALUE = Pattern.compile(
            "(?i)(手机号|联系电话|电话号码|电子邮箱|邮箱|身份证号码|身份证号|身份证|银行卡号|银行卡|订单号|收件人|联系人|客户姓名|姓名|账号|账户)(?:\\s*(?:是|为|[:：=])\\s*|\\s+)[^\\s，,。；;]{3,100}");
    private static final Pattern ADDRESS_FIELD_VALUE = Pattern.compile(
            "(?i)(收货地址|详细地址|公司地址|家庭住址|家庭地址|住址|我的地址)(?:\\s*(?:是|为|[:：=])\\s*|\\s+)[^，,。；;\\n]{3,100}");
    private static final Pattern PERSON_NAME_VALUE = Pattern.compile(
            "(?i)(我叫|我的名字是|开户名|收件人姓名)\\s*(?:是|为|[:：=])?\\s*[\\p{IsHan}A-Za-z·.]{2,32}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{16,}|Bearer\\s+[A-Za-z0-9._-]{16,}");
    private static final Pattern ENTRY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SupportAnswerService answers;
    private final AuditService auditService;

    public CustomerCommunicationService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                        SupportAnswerService answers, AuditService auditService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.answers = answers;
        this.auditService = auditService;
    }

    /** Record the user question and the exact knowledge-base answer, after PII redaction. */
    @Transactional
    public TurnResult recordCustomerTurn(String incomingToken, String rawQuestion, String rawAnswer) {
        String token = normalizeVisitorToken(incomingToken);
        String question = sanitize(rawQuestion, 300);
        String answer = sanitize(rawAnswer, 1000);
        if (!StringUtils.hasText(question)) question = "（空问题）";
        if (!StringUtils.hasText(answer)) answer = "客服暂时无法响应，请稍后重试。";
        String category = classify(question);
        String conversationNo = "CS" + LocalDateTime.now().format(NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

        jdbc.update("""
                INSERT INTO t_support_conversation
                  (visitor_token, conversation_no, customer_label, channel, subject, category,
                   status, priority, unread_count, last_message_preview, last_message_at, created_at, updated_at)
                VALUES (?, ?, '网站访客', 'web', ?, ?, 'open', 'normal', 0, '', NOW(), NOW(), NOW())
                ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), updated_at = NOW()
                """, token, conversationNo, trim(question, 160), category);
        Long conversationId = jdbc.queryForObject(
                "SELECT id FROM t_support_conversation WHERE visitor_token = ?", Long.class, token);
        if (conversationId == null) throw new IllegalStateException("Cannot create support conversation");

        insertMessage(conversationId, "customer", null, "网站访客", question, false);
        insertMessage(conversationId, "bot", null, "LANLinkshopping 客服", answer, false);
        jdbc.update("""
                UPDATE t_support_conversation
                   SET status = 'open', unread_count = unread_count + 1,
                       last_message_preview = ?, last_message_at = NOW(), updated_at = NOW()
                 WHERE id = ?
                """, trim("客户：" + question, 500), conversationId);
        Long lastMessageId = jdbc.queryForObject(
                "SELECT MAX(id) FROM t_support_message WHERE conversation_id = ?", Long.class, conversationId);
        return new TurnResult(token, lastMessageId == null ? 0L : lastMessageId);
    }

    /** Only visible messages for the same random visitor token are returned; internal notes are excluded. */
    public List<Map<String, Object>> visitorHistory(String visitorToken, long afterMessageId) {
        if (!UUID_TOKEN.matcher(visitorToken == null ? "" : visitorToken).matches()) return List.of();
        return jdbc.queryForList("""
                SELECT m.id AS id, m.sender_type AS senderType, m.sender_name AS senderName,
                       m.content AS content,
                       DATE_FORMAT(m.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM t_support_message m
                  JOIN t_support_conversation c ON c.id = m.conversation_id
                 WHERE c.visitor_token = ? AND m.id > ? AND m.sender_type <> 'note'
                 ORDER BY m.id ASC LIMIT 100
                """, visitorToken, Math.max(0L, afterMessageId));
    }

    public Map<String, Object> overview() {
        Map<String, Object> out = new HashMap<>();
        out.put("needsReply", count("status IN ('open', 'waiting_agent')"));
        out.put("waitingCustomer", count("status = 'waiting_customer'"));
        out.put("resolved", count("status = 'resolved'"));
        out.put("todayConversations", jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_support_conversation WHERE created_at >= CURDATE()", Long.class));
        out.put("unassigned", jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_support_conversation WHERE assigned_admin_user_id IS NULL AND status <> 'resolved'", Long.class));
        out.put("pendingLearning", jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_support_learning_candidate WHERE status = 'pending'", Long.class));
        out.put("publishedLearning", jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_support_learning_candidate WHERE status = 'published'", Long.class));
        return out;
    }

    public Map<String, Object> conversationPage(String queue, String query, String status,
                                               long page, long size, Long adminUserId) {
        long safePage = Math.max(1L, page);
        long safeSize = Math.max(1L, Math.min(50L, size));
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        String safeQueue = StringUtils.hasText(queue) ? queue.trim() : "all";
        switch (safeQueue) {
            case "unassigned" -> where.append(" AND assigned_admin_user_id IS NULL AND status <> 'resolved' ");
            case "mine" -> {
                where.append(" AND assigned_admin_user_id = ? ");
                args.add(adminUserId == null ? -1L : adminUserId);
            }
            case "waiting" -> where.append(" AND status = 'waiting_customer' ");
            case "open" -> where.append(" AND status IN ('open', 'waiting_agent') ");
            case "resolved" -> where.append(" AND status = 'resolved' ");
            default -> { /* all */ }
        }
        if (StringUtils.hasText(status) && List.of("open", "waiting_agent", "waiting_customer", "resolved").contains(status)) {
            where.append(" AND status = ? ");
            args.add(status);
        }
        if (StringUtils.hasText(query)) {
            String needle = "%" + trim(sanitize(query, 80), 80) + "%";
            where.append(" AND (conversation_no LIKE ? OR subject LIKE ? OR last_message_preview LIKE ?) ");
            args.add(needle);
            args.add(needle);
            args.add(needle);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_support_conversation" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT id AS id, conversation_no AS conversationNo, customer_label AS customerLabel,
                       channel AS channel, subject AS subject, category AS category, priority AS priority,
                       status AS status, assigned_admin_user_id AS assignedAdminUserId,
                       assigned_admin_name AS assignedAdminName, unread_count AS unreadCount,
                       last_message_preview AS lastMessagePreview,
                       DATE_FORMAT(last_message_at, '%Y-%m-%d %H:%i:%s') AS lastMessageAt,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM t_support_conversation
                """ + where + " ORDER BY last_message_at DESC, id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, Object> out = new HashMap<>();
        out.put("records", records);
        out.put("total", total == null ? 0L : total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    public Map<String, Object> conversationDetail(Long id) {
        List<Map<String, Object>> conversations = jdbc.queryForList("""
                SELECT id AS id, conversation_no AS conversationNo,
                       customer_label AS customerLabel, channel AS channel, subject AS subject,
                       category AS category, priority AS priority, status AS status,
                       assigned_admin_user_id AS assignedAdminUserId, assigned_admin_name AS assignedAdminName,
                       unread_count AS unreadCount, last_message_preview AS lastMessagePreview,
                       DATE_FORMAT(last_message_at, '%Y-%m-%d %H:%i:%s') AS lastMessageAt,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM t_support_conversation WHERE id = ?
                """, id);
        if (conversations.isEmpty()) throw new EmptyResultDataAccessException(1);
        jdbc.update("UPDATE t_support_conversation SET unread_count = 0 WHERE id = ?", id);
        List<Map<String, Object>> messages = jdbc.queryForList("""
                SELECT id AS id, sender_type AS senderType, sender_user_id AS senderUserId,
                       sender_name AS senderName, content AS content, internal_note AS internalNote,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM t_support_message WHERE conversation_id = ? ORDER BY id ASC LIMIT 500
                """, id);
        Map<String, Object> out = new HashMap<>();
        out.put("conversation", conversations.get(0));
        out.put("messages", messages);
        return out;
    }

    @Transactional
    public void sendAgentMessage(Long id, User admin, String rawContent, boolean internalNote, HttpServletRequest request) {
        if (admin == null || admin.getUserId() == null) throw new IllegalArgumentException("管理员登录状态已失效，请重新登录。");
        String content = sanitize(rawContent, 2000);
        if (!StringUtils.hasText(content)) throw new IllegalArgumentException("消息内容不能为空。");
        if (rawContent != null && rawContent.length() > 2000) throw new IllegalArgumentException("单条消息不能超过 2000 字。");
        ensureConversationExists(id);
        String senderName = StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : "平台运营";
        insertMessage(id, internalNote ? "note" : "agent", admin.getUserId(), senderName, content, internalNote);
        jdbc.update("""
                UPDATE t_support_conversation
                   SET last_message_preview = ?, last_message_at = NOW(), updated_at = NOW(),
                       status = CASE WHEN ? = 1 THEN status ELSE 'waiting_customer' END
                 WHERE id = ?
                """, trim((internalNote ? "内部备注：" : "客服：") + content, 500), internalNote ? 1 : 0, id);
        auditService.record(admin.getUserId(), internalNote ? "SUPPORT_NOTE_ADDED" : "SUPPORT_REPLY_SENT",
                "客户沟通会话 ID " + id, request);
    }

    @Transactional
    public void updateStatus(Long id, User admin, String status, HttpServletRequest request) {
        if (!List.of("open", "waiting_agent", "waiting_customer", "resolved").contains(status == null ? "" : status)) {
            throw new IllegalArgumentException("会话状态不合法。");
        }
        ensureConversationExists(id);
        jdbc.update("UPDATE t_support_conversation SET status = ?, updated_at = NOW() WHERE id = ?", status, id);
        auditService.record(admin.getUserId(), "SUPPORT_STATUS_CHANGED", "会话 ID " + id + " 状态更新为 " + status, request);
    }

    @Transactional
    public void updatePriority(Long id, User admin, String priority, HttpServletRequest request) {
        if (!List.of("low", "normal", "high", "urgent").contains(priority == null ? "" : priority)) {
            throw new IllegalArgumentException("优先级不合法。");
        }
        ensureConversationExists(id);
        jdbc.update("UPDATE t_support_conversation SET priority = ?, updated_at = NOW() WHERE id = ?", priority, id);
        auditService.record(admin.getUserId(), "SUPPORT_PRIORITY_CHANGED", "会话 ID " + id + " 优先级更新为 " + priority, request);
    }

    @Transactional
    public void assignToMe(Long id, User admin, HttpServletRequest request) {
        if (admin == null || admin.getUserId() == null) throw new IllegalArgumentException("管理员登录状态已失效，请重新登录。");
        ensureConversationExists(id);
        String name = StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : "平台运营";
        jdbc.update("UPDATE t_support_conversation SET assigned_admin_user_id = ?, assigned_admin_name = ?, updated_at = NOW() WHERE id = ?",
                admin.getUserId(), name, id);
        auditService.record(admin.getUserId(), "SUPPORT_ASSIGNED", "会话 ID " + id + " 分配给当前运营", request);
    }

    @Transactional
    public void unassign(Long id, User admin, HttpServletRequest request) {
        ensureConversationExists(id);
        jdbc.update("UPDATE t_support_conversation SET assigned_admin_user_id = NULL, assigned_admin_name = NULL, updated_at = NOW() WHERE id = ?", id);
        auditService.record(admin.getUserId(), "SUPPORT_UNASSIGNED", "会话 ID " + id + " 取消分配", request);
    }

    @Transactional
    public Long createLearningCandidate(Long conversationId, User admin, LearnRequest request, HttpServletRequest httpRequest) {
        if (request == null) throw new IllegalArgumentException("学习候选参数缺失。");
        if (!StringUtils.hasText(request.entryId()) || request.entryId().length() > 64
                || !ENTRY_ID.matcher(request.entryId().trim()).matches()) {
            throw new IllegalArgumentException("候选条目 ID 仅支持小写字母、数字和连字符。");
        }
        if (request.questionMessageId() == null || request.answerMessageId() == null
                || request.keywords() == null || request.keywords().isEmpty() || request.keywords().size() > 16) {
            throw new IllegalArgumentException("请选择一个客户问题、一个客服回答，并提供 1 至 16 个关键词。");
        }
        ensureConversationExists(conversationId);
        Map<String, Object> question = getMessage(conversationId, request.questionMessageId());
        Map<String, Object> answer = getMessage(conversationId, request.answerMessageId());
        if (!"customer".equals(question.get("senderType")) || !"agent".equals(answer.get("senderType"))) {
            throw new IllegalArgumentException("学习样本必须由同一会话中的客户问题与人工客服回答组成。");
        }

        String safeQuestion = sanitize(String.valueOf(question.get("content")), 300);
        String safeAnswer = sanitize(String.valueOf(answer.get("content")), 1000);
        if (!answers.isEligibleForKnowledgeLearning(safeQuestion)) {
            throw new IllegalArgumentException("该问题未通过电商范围或安全检查，不能进入学习候选。");
        }
        List<String> keywords = request.keywords().stream()
                .filter(StringUtils::hasText).map(k -> sanitize(k.trim(), 80))
                .filter(StringUtils::hasText).distinct().toList();
        if (keywords.isEmpty() || keywords.size() > 16 || keywords.stream().anyMatch(k -> k.length() > 80 || k.contains("<") || k.contains(">"))) {
            throw new IllegalArgumentException("关键词不符合安全要求。");
        }
        if (!answers.candidateKeywordsMatch(safeQuestion, keywords)) {
            throw new IllegalArgumentException("至少一个关键词必须能匹配脱敏后的客户问题。");
        }
        if (answers.hasKeywordConflict(keywords)) {
            throw new IllegalArgumentException("候选关键词与现有知识条目存在完全重复项，请缩小关键词并在沙盒中重新测试。");
        }
        if (!StringUtils.hasText(safeAnswer) || PRIVATE_KEY.matcher(safeAnswer).find()
                || CREDENTIAL_VALUE.matcher(safeAnswer).find()) {
            throw new IllegalArgumentException("候选回答为空或包含疑似凭据，无法创建学习候选。");
        }
        if (answers.hasStaticEntryId(request.entryId().trim())) {
            throw new IllegalArgumentException("该条目 ID 已存在于正式静态知识库，请使用新的 ID。");
        }
        Long duplicateId = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_support_learning_candidate WHERE entry_id = ?",
                Long.class, request.entryId().trim());
        if (duplicateId != null && duplicateId > 0) {
            throw new IllegalArgumentException("该候选条目 ID 已存在，请先检查对应候选记录。");
        }
        final String keywordsJson;
        try {
            keywordsJson = objectMapper.writeValueAsString(keywords);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法生成候选关键词。");
        }

        KeyHolder holder = new GeneratedKeyHolder();
        String insert = """
                INSERT INTO t_support_learning_candidate
                  (conversation_id, source_question_message_id, source_answer_message_id,
                   entry_id, question_text, keywords_json, answer_text, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NOW())
                """;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId);
            ps.setLong(2, request.questionMessageId());
            ps.setLong(3, request.answerMessageId());
            ps.setString(4, request.entryId().trim());
            ps.setString(5, safeQuestion);
            ps.setString(6, keywordsJson);
            ps.setString(7, trim(safeAnswer, 1000));
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("创建学习候选失败。");
        auditService.record(admin.getUserId(), "SUPPORT_LEARNING_CANDIDATE_CREATED",
                "从会话 ID " + conversationId + " 创建候选知识 ID " + key.longValue(), httpRequest);
        return key.longValue();
    }

    public Map<String, Object> learningCandidatePage(String status, String query, long page, long size) {
        long safePage = Math.max(1L, page);
        long safeSize = Math.max(1L, Math.min(50L, size));
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status) && List.of("pending", "published", "rejected").contains(status)) {
            where.append(" AND status = ? ");
            args.add(status);
        }
        if (StringUtils.hasText(query)) {
            String needle = "%" + trim(sanitize(query, 80), 80) + "%";
            where.append(" AND (entry_id LIKE ? OR question_text LIKE ? OR answer_text LIKE ?) ");
            args.add(needle); args.add(needle); args.add(needle);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_support_learning_candidate" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize); pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT id AS id, conversation_id AS conversationId, entry_id AS entryId,
                       question_text AS questionText, keywords_json AS keywordsJson, answer_text AS answerText,
                       status AS status, reviewer_name AS reviewerName, review_note AS reviewNote,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
                       DATE_FORMAT(reviewed_at, '%Y-%m-%d %H:%i:%s') AS reviewedAt,
                       DATE_FORMAT(published_at, '%Y-%m-%d %H:%i:%s') AS publishedAt
                  FROM t_support_learning_candidate
                """ + where + " ORDER BY FIELD(status, 'pending', 'published', 'rejected'), id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, Object> out = new HashMap<>();
        out.put("records", records);
        out.put("total", total == null ? 0L : total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    public Map<String, Object> learningCandidateDetail(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id AS id, conversation_id AS conversationId,
                       source_question_message_id AS sourceQuestionMessageId,
                       source_answer_message_id AS sourceAnswerMessageId,
                       entry_id AS entryId, question_text AS questionText, keywords_json AS keywordsJson,
                       answer_text AS answerText, status AS status, reviewer_user_id AS reviewerUserId,
                       reviewer_name AS reviewerName, review_note AS reviewNote,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
                       DATE_FORMAT(reviewed_at, '%Y-%m-%d %H:%i:%s') AS reviewedAt,
                       DATE_FORMAT(published_at, '%Y-%m-%d %H:%i:%s') AS publishedAt
                  FROM t_support_learning_candidate WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw new EmptyResultDataAccessException(1);
        Map<String, Object> candidate = rows.get(0);
        Map<String, Object> question = messageSummary(((Number) candidate.get("sourceQuestionMessageId")).longValue());
        Map<String, Object> answer = messageSummary(((Number) candidate.get("sourceAnswerMessageId")).longValue());
        Map<String, Object> out = new HashMap<>();
        out.put("candidate", candidate);
        out.put("sourceQuestion", question);
        out.put("sourceAnswer", answer);
        out.put("sourceConversationId", candidate.get("conversationId"));
        return out;
    }

    @Transactional
    public void reviewLearningCandidate(Long id, User admin, String decision, String note, HttpServletRequest request) {
        if (admin == null || admin.getUserId() == null) throw new IllegalArgumentException("管理员登录状态已失效，请重新登录。");
        if (!"publish".equals(decision) && !"reject".equals(decision)) {
            throw new IllegalArgumentException("审核操作不合法。");
        }
        String reviewNote = sanitize(note, 500);
        List<Map<String, Object>> candidateRows = jdbc.queryForList(
                "SELECT id, entry_id FROM t_support_learning_candidate WHERE id = ? AND status = 'pending'", id);
        if (candidateRows.isEmpty()) throw new IllegalArgumentException("候选不存在或已审核，请刷新列表。");
        String newStatus = "publish".equals(decision) ? "published" : "rejected";
        String reviewerName = StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : "平台运营";
        int updated = jdbc.update("""
                UPDATE t_support_learning_candidate
                   SET status = ?, reviewer_user_id = ?, reviewer_name = ?, review_note = ?,
                       reviewed_at = NOW(), published_at = CASE WHEN ? = 'published' THEN NOW() ELSE NULL END
                 WHERE id = ? AND status = 'pending'
                """, newStatus, admin.getUserId(), reviewerName, reviewNote, newStatus, id);
        // Compare-and-set the pending state. A second administrator can win the race
        // after the initial SELECT; never report success or audit a transition that did not occur.
        if (updated != 1) {
            throw new IllegalArgumentException("候选已被其他管理员处理，请刷新列表后确认最新状态。");
        }
        auditService.record(admin.getUserId(), "published".equals(newStatus) ? "SUPPORT_KNOWLEDGE_PUBLISHED" : "SUPPORT_KNOWLEDGE_REJECTED",
                "候选知识 ID " + id + "（" + String.valueOf(candidateRows.get(0).get("entry_id")) + "）审核结果：" + newStatus, request);
    }

    public boolean isSafeCandidateEntryId(String entryId) {
        return StringUtils.hasText(entryId) && entryId.length() <= 64 && ENTRY_ID.matcher(entryId).matches();
    }

    private Long count(String clause) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_support_conversation WHERE " + clause, Long.class);
    }

    private void insertMessage(Long conversationId, String type, Long senderUserId,
                               String senderName, String content, boolean internalNote) {
        jdbc.update("""
                INSERT INTO t_support_message
                  (conversation_id, sender_type, sender_user_id, sender_name, content, internal_note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                """, conversationId, type, senderUserId, trim(senderName, 80), content, internalNote ? 1 : 0);
    }

    private Map<String, Object> getMessage(Long conversationId, Long messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id AS id, sender_type AS senderType, sender_name AS senderName,
                       content AS content, internal_note AS internalNote
                  FROM t_support_message WHERE conversation_id = ? AND id = ?
                """, conversationId, messageId);
        if (rows.isEmpty()) throw new IllegalArgumentException("所选消息不属于当前会话或已不存在。");
        return rows.get(0);
    }

    private Map<String, Object> messageSummary(long messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id AS id, sender_type AS senderType, sender_name AS senderName,
                       content AS content, internal_note AS internalNote,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM t_support_message WHERE id = ?
                """, messageId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void ensureConversationExists(Long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_support_conversation WHERE id = ?", Long.class, id);
        if (count == null || count == 0) throw new EmptyResultDataAccessException(1);
    }

    private String normalizeVisitorToken(String incoming) {
        return UUID_TOKEN.matcher(incoming == null ? "" : incoming).matches() ? incoming : UUID.randomUUID().toString();
    }

    private String classify(String question) {
        String q = question.toLowerCase();
        if (contains(q, "商户", "供应商", "入驻", "开店")) return "商户入驻";
        if (contains(q, "账期", "钱包", "充值", "提现")) return "钱包与账期";
        if (contains(q, "会员", "积分", "活动", "促销", "优惠")) return "会员与活动";
        if (contains(q, "购物车", "结算", "订单", "下单")) return "购物车与订单";
        if (contains(q, "商品", "产品", "商城", "分类", "行业")) return "商品浏览";
        if (contains(q, "账号", "注册", "登录", "登陆", "验证码", "密码")) return "账号注册与登录";
        if (contains(q, "消息", "通知", "cookie", "隐私")) return "消息与隐私";
        return "其他";
    }

    private boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word.toLowerCase())) return true;
        return false;
    }

    /** Remove common PII/credential forms before transcript persistence and knowledge extraction. */
    private String sanitize(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replace("<", "").replace(">", "");
        clean = CREDENTIAL_VALUE.matcher(clean).replaceAll("$1=[敏感值已脱敏]");
        clean = PRIVATE_KEY.matcher(clean).replaceAll("[凭据内容已脱敏]");
        clean = ADDRESS_FIELD_VALUE.matcher(clean).replaceAll("$1=[地址信息已脱敏]");
        clean = PERSONAL_FIELD_VALUE.matcher(clean).replaceAll("$1=[个人信息已脱敏]");
        clean = PERSON_NAME_VALUE.matcher(clean).replaceAll("$1=[姓名已脱敏]");
        clean = EMAIL.matcher(clean).replaceAll("[邮箱已脱敏]");
        clean = PHONE.matcher(clean).replaceAll("[手机号已脱敏]");
        clean = LONG_NUMBER.matcher(clean).replaceAll("[长数字串已脱敏]");
        return trim(clean.replaceAll("[ \\t]+", " ").trim(), max);
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    public record TurnResult(String visitorToken, long lastMessageId) {}
    public record LearnRequest(Long questionMessageId, Long answerMessageId, String entryId, List<String> keywords) {}
}
