package com.christos_bramis.bram_vortex_pipeline_generator.controller;

import com.christos_bramis.bram_vortex_pipeline_generator.repository.PipelineJobRepository;
import com.christos_bramis.bram_vortex_pipeline_generator.service.PipelineService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/pipeline")
public class Pipeline {

    private final PipelineService pipelineService;
    private final PipelineJobRepository pipelineJobRepository;

    public Pipeline(PipelineService pipelineService, PipelineJobRepository pipelineJobRepository) {
        this.pipelineService = pipelineService;
        this.pipelineJobRepository = pipelineJobRepository;
    }

    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generatePipeline(
            @PathVariable String analysisJobId,
            Authentication auth) {

        String userId = (String) auth.getPrincipal();
        String token = (String) auth.getCredentials();
        System.out.println("🚀 [PIPELINE CONTROLLER] Webhook received. User: " + userId);

        try {
            String pipelineJobId = UUID.randomUUID().toString();

            // 2. Περνάμε το TOKEN στο Service
            pipelineService.generateAndSavePipeline(pipelineJobId, analysisJobId, userId, token);

            return ResponseEntity.ok(pipelineJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/download/by-analysis/{analysisJobId}")
    public ResponseEntity<byte[]> downloadPipelineByAnalysisId(
            @PathVariable String analysisJobId,
            Authentication auth) {

        String userId = auth.getName();
        System.out.println("📦 [PIPELINE] Download request for Analysis Job: " + analysisJobId + " by User: " + userId);

        return pipelineJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized access attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // ΕΔΩ Η ΔΙΟΡΘΩΣΗ: Προσθήκη ελέγχου length == 0
                    if (!"COMPLETED".equals(job.getStatus()) || job.getPipelineZip() == null || job.getPipelineZip().length == 0) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build(); // Επιστρέφει 202
                    }

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-pipeline-" + analysisJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getPipelineZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }


}