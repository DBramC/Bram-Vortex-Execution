package com.christos_bramis.bram_vortex_execution.controller;

import com.christos_bramis.bram_vortex_execution.service.ExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/execution")
public class Execution {

    private final ExecutionService executionService;

    public Execution(ExecutionService executionService) {
        this.executionService = executionService;
    }

    // Στο Controller του Execution Service
    @PostMapping("/execute")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> payload, Authentication auth) {
        // 1. Log άμεσα μόλις φτάσει το αίτημα
        System.out.println("📥 [EXECUTOR] Request received for Job ID: " + payload.get("jobId"));

        try {
            String username = auth.getName();
            String jobId = payload.get("jobId").toString();
            String repoUrl = payload.get("repoUrl").toString();

            // 2. Κλήση της ασύγχρονης μεθόδου
            executionService.processDeployment(username, jobId, repoUrl);

            return ResponseEntity.accepted().body("Started");
        } catch (Exception e) {
            System.err.println("❌ [EXECUTOR ERROR] " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/analyze-costs")
    public ResponseEntity<Map<String, Double>> analyzeCosts(
            @RequestHeader("X-Target-Cloud") String targetCloud,
            @RequestBody String aiSkuResponse) {

        System.out.println("💸 [EXECUTOR] Cost Analysis requested for cloud: " + targetCloud);

        try {
            // Κλήση της συνάρτησης που φτιάξαμε στο ExecutionService
            Map<String, Double> costs = executionService.calculateCosts(targetCloud, aiSkuResponse);
            return ResponseEntity.ok(costs);

        } catch (Exception e) {
            System.err.println("❌ [EXECUTOR ERROR] Infracost failed: " + e.getMessage());
            // Επιστρέφουμε ένα άδειο Map σε περίπτωση λάθους για να μην "σκάσει" το Frontend
            return ResponseEntity.internalServerError().body(Map.of());
        }
    }

}