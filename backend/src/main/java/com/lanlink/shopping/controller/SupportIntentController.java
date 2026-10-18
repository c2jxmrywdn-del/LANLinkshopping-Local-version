package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.support.IntentUnderstandingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/support/intent")
public class SupportIntentController {
    private final IntentUnderstandingService intents;
    public SupportIntentController(IntentUnderstandingService intents) { this.intents = intents; }

    @PostMapping("/analyze")
    public R<?> analyze(@RequestBody(required = false) Request body) {
        if (body == null || body.question() == null || body.question().isBlank() || body.question().length() > 300) {
            return R.fail(400, "问题不能为空且不能超过 300 字");
        }
        return R.ok(intents.analyze(body.question(), body.recentTurns()).data());
    }
    public record Request(String question, List<String> recentTurns) {}

    /** 意图统计（仅平台运营）：按意图/来源分组计数 */
    @GetMapping("/stats")
    @RequirePerm("admin:all")
    public R<?> stats() {
        return R.ok(intents.stats());
    }
}
