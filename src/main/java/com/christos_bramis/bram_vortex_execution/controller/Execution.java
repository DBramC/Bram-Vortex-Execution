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

    @PostMapping("/execute")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> payload, Authentication auth) {
        String username = auth.getName(); // Από το JWT Filter[cite: 3]
        String jobId = payload.get("jobId").toString();
        String repoUrl = payload.get("repoUrl").toString();

        try {
            executionService.processDeployment(username, jobId, repoUrl);
            return ResponseEntity.ok("Success: Master ZIP deployed to root.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

}