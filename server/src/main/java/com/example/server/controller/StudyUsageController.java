package com.example.server.controller;

import com.example.server.security.CurrentUserId;
import com.example.server.service.ai.AiCallLedger;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class StudyUsageController {
    private final AiCallLedger ledger;

    public StudyUsageController(AiCallLedger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/user/ai-usage")
    public Map<String, Object> usage(@CurrentUserId Long learner) {
        return ledger.studentUsage(learner);
    }
}
