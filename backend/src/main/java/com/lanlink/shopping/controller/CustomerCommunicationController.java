package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.support.CustomerCommunicationService;
import com.lanlink.shopping.support.CustomerCommunicationService.LearnRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin-only customer communication inbox and human-reviewed dialogue-learning lifecycle. */
@RestController
@RequestMapping("/admin/customer-communications")
public class CustomerCommunicationController {
    private final CustomerCommunicationService communications;

    public CustomerCommunicationController(CustomerCommunicationService communications) {
        this.communications = communications;
    }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(communications.overview());
    }

    @GetMapping("/conversations")
    public R<Map<String, Object>> conversations(
            @RequestParam(defaultValue = "all") String queue,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        User admin = UserContext.current(request);
        return R.ok(communications.conversationPage(queue, q, status, page, size,
                admin == null ? null : admin.getUserId()));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<R<Map<String, Object>>> conversation(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(R.ok(communications.conversationDetail(id)));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<R<Map<String, Object>>> reply(
            @PathVariable Long id, @RequestBody(required = false) ReplyRequest body,
            HttpServletRequest request) {
        try {
            if (body == null) throw new IllegalArgumentException("消息内容不能为空。");
            communications.sendAgentMessage(id, UserContext.current(request), body.content(),
                    Boolean.TRUE.equals(body.internalNote()), request);
            return ResponseEntity.ok(R.ok(Map.of("sent", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @PutMapping("/conversations/{id}/status")
    public ResponseEntity<R<Map<String, Object>>> status(
            @PathVariable Long id, @RequestBody(required = false) StatusRequest body,
            HttpServletRequest request) {
        try {
            if (body == null) throw new IllegalArgumentException("请提供会话状态。");
            communications.updateStatus(id, UserContext.current(request), body.status(), request);
            return ResponseEntity.ok(R.ok(Map.of("updated", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @PutMapping("/conversations/{id}/priority")
    public ResponseEntity<R<Map<String, Object>>> priority(
            @PathVariable Long id, @RequestBody(required = false) PriorityRequest body,
            HttpServletRequest request) {
        try {
            if (body == null) throw new IllegalArgumentException("请提供优先级。");
            communications.updatePriority(id, UserContext.current(request), body.priority(), request);
            return ResponseEntity.ok(R.ok(Map.of("updated", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @PostMapping("/conversations/{id}/assign")
    public ResponseEntity<R<Map<String, Object>>> assign(@PathVariable Long id, HttpServletRequest request) {
        try {
            communications.assignToMe(id, UserContext.current(request), request);
            return ResponseEntity.ok(R.ok(Map.of("assigned", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @DeleteMapping("/conversations/{id}/assign")
    public ResponseEntity<R<Map<String, Object>>> unassign(@PathVariable Long id, HttpServletRequest request) {
        try {
            communications.unassign(id, UserContext.current(request), request);
            return ResponseEntity.ok(R.ok(Map.of("assigned", false)));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话不存在或已被移除。"));
        }
    }

    @PostMapping("/conversations/{id}/learn")
    public ResponseEntity<R<Map<String, Object>>> createLearningCandidate(
            @PathVariable Long id, @RequestBody(required = false) LearnRequest body, HttpServletRequest request) {
        try {
            Long candidateId = communications.createLearningCandidate(id, UserContext.current(request), body, request);
            return ResponseEntity.ok(R.ok("学习候选已创建，仍需管理员审核。", Map.of("id", candidateId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "会话或所选消息不存在。"));
        }
    }

    @GetMapping("/learning-candidates")
    public R<Map<String, Object>> learningCandidates(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(communications.learningCandidatePage(status, q, page, size));
    }

    @GetMapping("/learning-candidates/{id}")
    public ResponseEntity<R<Map<String, Object>>> learningCandidate(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(R.ok(communications.learningCandidateDetail(id)));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "学习候选不存在。"));
        }
    }

    @PostMapping("/learning-candidates/{id}/review")
    public ResponseEntity<R<Map<String, Object>>> review(
            @PathVariable Long id, @RequestBody(required = false) ReviewRequest body, HttpServletRequest request) {
        try {
            if (body == null || !StringUtils.hasText(body.decision())) {
                throw new IllegalArgumentException("请选择审核通过或驳回。");
            }
            if (body.note() != null && body.note().length() > 500) {
                throw new IllegalArgumentException("审核备注不能超过 500 字。");
            }
            communications.reviewLearningCandidate(id, UserContext.current(request), body.decision(),
                    body.note(), request);
            return ResponseEntity.ok(R.ok(Map.of("reviewed", true,
                    "status", "publish".equals(body.decision()) ? "published" : "rejected")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "候选不存在或已审核。"));
        }
    }

    public record ReplyRequest(String content, Boolean internalNote) {}
    public record StatusRequest(String status) {}
    public record PriorityRequest(String priority) {}
    public record ReviewRequest(String decision, String note) {}
}
