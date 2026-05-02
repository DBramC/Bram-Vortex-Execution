package com.christos_bramis.bram_vortex_pipeline_generator.service;

import com.christos_bramis.bram_vortex_pipeline_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_pipeline_generator.entity.PipelineJob;
import com.christos_bramis.bram_vortex_pipeline_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_pipeline_generator.repository.PipelineJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PipelineService {

    private final PipelineJobRepository pipelineJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineJobRepository pipelineJobRepository,
                           AnalysisJobRepository analysisJobRepository,
                           ChatModel chatModel) {
        this.pipelineJobRepository = pipelineJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    public void generateAndSavePipeline(String pipelineJobId, String analysisJobId, String userId, String token) {
        System.out.println("\n🚀 [VORTEX-PIPELINE] Starting Generation for Job: " + pipelineJobId);

        PipelineJob job = new PipelineJob();
        job.setId(pipelineJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        pipelineJobRepository.save(job);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Fetching Blueprint
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found"));

                String blueprintJson = analysisJob.getBlueprintJson() != null ?
                        analysisJob.getBlueprintJson() : "{}";

                // 🌟 ΕΞΥΠΝΗ ΕΞΑΓΩΓΗ: Παίρνουμε το computeCategory & targetPort απευθείας από το JSON
                Map<String, Object> blueprintMap = objectMapper.readValue(blueprintJson, new TypeReference<Map<String, Object>>() {});
                String computeType = (String) blueprintMap.get("computeCategory");

                // Δυναμική εξαγωγή της πόρτας (με fallback το 8080)
                int targetPort = 8080;
                if (blueprintMap.get("targetContainerPort") != null) {
                    try {
                        targetPort = Integer.parseInt(blueprintMap.get("targetContainerPort").toString());
                    } catch (NumberFormatException ignored) {}
                }

                // Αν δεν υπάρχει, ρίχνουμε Exception για να σταματήσει η διαδικασία (Fail-Fast)
                if (computeType == null || computeType.trim().isEmpty()) {
                    throw new RuntimeException("CRITICAL: 'computeCategory' is missing from the Blueprint. Cannot determine deployment target.");
                }

                System.out.println("🚀 [VORTEX-PIPELINE] Target detected from JSON: " + computeType);
                System.out.println("🔌 [VORTEX-PIPELINE] Target Port detected: " + targetPort);

                // 2. AI Dispatch - CI/CD Expert Prompt (ΑΤΟΦΙΟ & Docker-Ready)
                String prompt = String.format("""
                    You are a Principal CI/CD Engineer. Your task is to generate a PRODUCTION-READY GitHub Actions workflow (`.github/workflows/deploy.yml`) to build, push, and deploy a containerized application using GitHub Packages (GHCR).
                
                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------
                    
                    --- DEPLOYMENT TARGET ---
                    Compute Type: %s
                    -------------------------

                    ENGINEERING REQUIREMENTS & STRICT CONSTRAINTS:
                    1. **Trigger**: The pipeline MUST trigger on 'push' to the 'main' or 'master' branch.
                    
                    2. **Build & Push (GHCR EXCLUSIVELY)**:
                       - Examine the Blueprint's 'ciCdMetadata'.
                       - Log in to GitHub Container Registry (`ghcr.io`) using `${{ github.actor }}` and `${{ secrets.GITHUB_TOKEN }}`.
                       - IF 'hasDockerfile' is false: First, set up the build environment (e.g., JDK), execute the 'buildCommands' to generate the artifact, dynamically create a basic Dockerfile in the pipeline to package it, and then build/push.
                       - IF 'hasDockerfile' is true: Assume the project has a Dockerfile. Do NOT run separate 'buildCommands'. Just build the Docker image and push it to `ghcr.io/${{ github.repository }}:latest`. Do NOT use Docker Hub.

                    3. **Deployment Step (Branching by Compute Type)**:
                       - **IF Compute Type is 'VM' or 'Virtual Machine'**:
                         - Use `appleboy/ssh-action@v1.0.3` to connect to the target Virtual Machine.
                         - Authenticate using EXACTLY these secrets: `${{ secrets.VM_HOST }}`, `${{ secrets.VM_USER }}`, and `${{ secrets.VM_SSH_KEY }}`.
                         - The SSH script MUST:
                           a) Log in to `ghcr.io` with `${{ secrets.GITHUB_TOKEN }}`.
                           b) Pull the latest image.
                           c) Stop and remove the existing container gracefully (`docker stop app || true` and `docker rm app || true`).
                           d) Run the new container in detached mode (`-d`) with restart policy `unless-stopped`.
                           e) Map port %d (Host) to port %d (Container).
                           f) Inject ALL sensitive database configurations from 'configurationSettings' as environment variables using GitHub Secrets (e.g., `-e SPRING_DATASOURCE_URL='${{ secrets.DB_URL }}'`).

                       - **IF Compute Type is 'Kubernetes' or 'K8S'**:
                         - Authenticate to the cluster using `${{ secrets.KUBECONFIG }}`.
                         - Apply configurations or run `kubectl rollout restart deployment/<app-name>`.

                       - **IF Compute Type is 'Managed Container'**:
                         - Use standard cloud provider actions to update the target service/task definition.

                    OUTPUT FORMAT (CRITICAL):
                    - Respond ONLY with a SINGLE, VALID JSON object.
                    - NO markdown blocks (e.g., no ```json).
                    - NO conversational text.
    
                    JSON STRUCTURE:
                    {
                      ".github/workflows/deploy.yml": "<raw yaml content here>"
                    }
                    """, blueprintJson, computeType, targetPort, targetPort);

                System.out.println("🧠 [PIPELINE] Calling AI...");
                String aiResponse = chatModel.call(prompt);

                System.out.println("DEBUG AI RAW RESPONSE length: " + (aiResponse != null ? aiResponse.length() : 0));

                // 3. Robust Parsing
                Map<String, String> asFiles = parseResponse(aiResponse);

                if (asFiles == null || asFiles.isEmpty()) {
                    throw new RuntimeException("AI returned empty file set or invalid JSON format");
                }

                // 4. Zipping
                System.out.println("🤐 [PIPELINE] Creating ZIP for " + asFiles.size() + " files...");
                byte[] zipBytes = createZipInMemory(asFiles);

                if (zipBytes == null || zipBytes.length < 100) {
                    throw new RuntimeException("Generated ZIP is suspiciously small (" + (zipBytes != null ? zipBytes.length : 0) + " bytes)");
                }

                // 5. Finalize
                job.setPipelineZip(zipBytes);
                job.setStatus("COMPLETED");
                pipelineJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "PIPELINE", "COMPLETED", token);
                System.out.println("✅ [PIPELINE] Success! ZIP size: " + zipBytes.length + " bytes.");

            } catch (Exception e) {
                System.err.println("❌ [PIPELINE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                pipelineJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "PIPELINE", "FAILED", token);
            }
        });
    }

    private Map<String, String> parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            System.err.println("⚠️ [PARSING ERROR] AI response is null or empty");
            return new HashMap<>();
        }

        try {
            String clean = response.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
            }

            return objectMapper.readValue(clean, new TypeReference<HashMap<String, String>>() {});
        } catch (Exception e) {
            System.err.println("⚠️ [PARSING ERROR] Failed to convert AI response to Map: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void notifyOrchestrator(String jobId, String service, String status, String token) {
        String url = String.format("http://repo-analyzer-svc/dashboard/internal/callback/%s?service=%s&status=%s",
                jobId, service, status);

        RestClient internalClient = RestClient.create();
        internalClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private byte[] createZipInMemory(Map<String, String> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;

                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        }
        return baos.toByteArray();
    }
}