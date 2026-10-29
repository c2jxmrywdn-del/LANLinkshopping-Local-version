package com.lanlink.shopping.module.aikb.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.aikb.AiModelRouter;
import com.lanlink.shopping.module.aikb.KnowledgeRagService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库查询 API（登录用户；供智能客服/前端问答组件调用）。
 *  - GET  /kb/search?q=&topK=5&source=   向量检索
 *  - POST /kb/ask {question, topK?, source?}  本地 qwen2.5 RAG 问答
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeQueryController {

    private final KnowledgeRagService ragService;

    public KnowledgeQueryController(KnowledgeRagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/search")
    public R<List<RedisVectorStore.Hit>> search(@RequestParam String q,
                                                @RequestParam(defaultValue = "5") int topK,
                                                @RequestParam(required = false) String source,
                                                HttpServletRequest request) {
        UserContext.currentUserId(request); // 需登录
        return R.ok(ragService.search(q, Math.min(Math.max(topK, 1), 20), source));
    }

    @PostMapping("/ask")
    public R<Map<String, Object>> ask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        UserContext.currentUserId(request); // 需登录
        String question = body == null ? null : (String) body.get("question");
        if (question == null || question.isBlank()) return R.fail("请输入问题");
        int topK = body.get("topK") == null ? 5 : ((Number) body.get("topK")).intValue();
        String source = body.get("source") == null ? null : String.valueOf(body.get("source"));
        // 意图路由目标：auto（默认，长上下文自动切文本模型）/ vl / text
        String model = body.get("model") == null ? AiModelRouter.AUTO : String.valueOf(body.get("model"));
        // 工具增强：本地库无命中时允许模型走联网搜索/网页抓取/Context7（网关自动路由）
        boolean enableTools = Boolean.TRUE.equals(body.get("enableTools"));
        return R.ok(ragService.ask(question.trim(), Math.min(Math.max(topK, 1), 10), source, model, enableTools));
    }
}
