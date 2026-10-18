package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 自主学习自动沉淀：未命中知识库的合格问题 → pending 候选；
 * question_hash 去重、命中/超范围/PROHIBITED 不建、异常不影响主链路。
 */
class AutoLearningCandidateTest {

    private JdbcTemplate jdbc;
    private CustomerCommunicationService service;
    private SupportAnswerService answers;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        answers = new SupportAnswerService(new ObjectMapper(),
                new ClassPathResource("knowledge-base/customer-service-zh-CN.json"));
        answers.loadKnowledgeBase();
        service = new CustomerCommunicationService(jdbc, new ObjectMapper(), answers, mock(AuditService.class));
    }

    @Test
    void eligibleUnmatchedQuestionCreatesPendingCandidate() {
        // 无 pending 候选（去重查询空）、entry_id 无撞车、插入返回 key=42
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenReturn(null);
        when(jdbc.queryForObject(contains("entry_id"), eq(Long.class), any())).thenReturn(0L);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(inv -> {
                    KeyHolder holder = inv.getArgument(1);
                    holder.getKeyList().add(java.util.Map.of("id", 42L));
                    return 1;
                });

        // 电商范围（含域信号"积分"）但未命中知识库的低频问法
        Long id = service.createAutoCandidate("积分有效期是多久呀");
        assertEquals(42L, id, "合格未命中问题应创建候选");
        verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void hashDedupReturnsExistingPendingId() {
        // 模拟已有 pending 候选（hash 命中去重）
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenReturn(7L);
        Long id = service.createAutoCandidate("积分有效期是多久呀");
        assertEquals(7L, id, "重复问题应返回已有 pending 候选 ID");
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void outOfScopeQuestionSkipped() {
        // 非电商范围（实时价格查询）不建候选
        Long id = service.createAutoCandidate("今天大盘走势怎么样");
        assertNull(id, "超范围问题不进入学习候选");
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void blankQuestionSkipped() {
        assertNull(service.createAutoCandidate("   "));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void sha256IsStableAcrossWhitespaceAndCase() {
        String a = CustomerCommunicationService.sha256("采购发票 什么时候");
        String b = CustomerCommunicationService.sha256("采购发票   什么时候".toLowerCase());
        assertEquals(a, b, "规范化后哈希应一致（去多余空白+小写）");
        assertEquals(64, a.length(), "SHA-256 hex 长度 64");
    }

    @Test
    void extractMatchedKeywordsReturnsKnowledgeKeywords() {
        List<String> kws = answers.extractMatchedKeywords("怎么注册账号啊");
        assertFalse(kws.isEmpty(), "命中知识库的问题应提取到关键词");
        assertTrue(kws.stream().anyMatch(k -> k.contains("注册") || k.contains("账号")),
                "应包含命中关键词: " + kws);
    }
}
