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
    public ResponseEntity<?> runExecution(@RequestBody Map<String, Object> request, Authentication authentication) {
        String username = authentication.getName(); // Το username έρχεται αυτόματα από το JWT[cite: 1]
        String repoUrl = (String) request.get("repoUrl");
        Map<String, String> generatedFiles = (Map<String, String>) request.get("files");

        try {
            executionService.executeCommit(username, repoUrl, generatedFiles);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Commit pushed and CI/CD triggered ( Provided by Bram-Vortex application \uD83C\uDF2A️ ) "));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

}