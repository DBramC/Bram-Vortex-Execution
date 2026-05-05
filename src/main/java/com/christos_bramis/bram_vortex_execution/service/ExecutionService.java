package com.christos_bramis.bram_vortex_execution.service;

import com.christos_bramis.bram_vortex_execution.entity.ValidatorJob;
import com.christos_bramis.bram_vortex_execution.repository.ValidatorJobsRepository;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ExecutionService {

    private final VaultService vaultService;
    private final ValidatorJobsRepository validatorJobsRepository;

    public ExecutionService(VaultService vaultService, ValidatorJobsRepository validatorJobsRepository) {
        this.vaultService = vaultService;
        this.validatorJobsRepository = validatorJobsRepository;
    }

    @Async // Εξασφαλίζει ότι τρέχει στο background
    public void processDeployment(String username, String jobId, String repoUrl) {
        System.out.println("🛠️ [EXECUTOR] Starting Deployment Sequence for Job: " + jobId);

        try {
            // 1. Τραβάμε το Job από τη βάση
            System.out.println("🔍 [EXECUTOR] Step 1: Fetching master_zip from database...");
            ValidatorJob job = validatorJobsRepository.findByAnalysisJobId(jobId)
                    .orElseThrow(() -> new RuntimeException("Job ID " + jobId + " not found in database. Check if table name is correct!"));

            if (job.getMasterZip() == null || job.getMasterZip().length == 0) {
                throw new RuntimeException("Master ZIP is empty or null for job: " + jobId);
            }
            System.out.println("✅ [EXECUTOR] Master ZIP found. Size: " + job.getMasterZip().length + " bytes");

            // 2. Παίρνουμε το Token από το Vault
            System.out.println("🔑 [EXECUTOR] Step 2: Retrieving GitHub Token for user: " + username);
            String githubToken = vaultService.getGithubToken(username);
            if (githubToken == null || githubToken.isEmpty()) {
                throw new RuntimeException("GitHub Token not found in Vault for user: " + username);
            }

            // 3. Clone & Extract Logic
            Path tempPath = Files.createTempDirectory("vortex-exec-");
            System.out.println("📂 [EXECUTOR] Step 3: Created temp directory at: " + tempPath.toAbsolutePath());

            System.out.println("🌐 [EXECUTOR] Step 4: Cloning repository: " + repoUrl);
            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempPath.toFile())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                    .call()) {

                System.out.println("📦 [EXECUTOR] Step 5: Extracting infrastructure files to repository root...");
                unzipToRoot(job.getMasterZip(), tempPath);

                // 4. Commit & Push
                System.out.println("📝 [EXECUTOR] Step 6: Committing changes...");
                System.out.println("Files to commit: " + new File(tempPath.toString()).listFiles().length);
                git.add().addFilepattern(".").call();
                git.commit()
                        .setMessage("Bram Vortex: Infrastructure and Pipelines Setup")
                        .setAuthor("Bram Vortex", "no-reply@bramvortex.com")
                        .call();

                // Step 7: Pushing to GitHub
                System.out.println("🚀 [EXECUTOR] Step 7: Pushing to GitHub (branch: main)...");

                Iterable<PushResult> results = git.push()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                        .call();

                for (PushResult result : results) {
                    for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                        if (update.getStatus() != RemoteRefUpdate.Status.OK &&
                                update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {

                            // ΕΔΩ ΕΙΝΑΙ Η ΛΥΣΗ: Τραβάμε το μήνυμα του server
                            String serverMessage = update.getMessage();
                            throw new RuntimeException("Push failed! Status: " + update.getStatus() +
                                    " | Server Message: " + (serverMessage != null ? serverMessage : "No detailed message"));
                        }
                    }
                }
                System.out.println("🎉 [EXECUTOR] SUCCESS: All files pushed to main branch!");

            } finally {
                FileUtils.deleteDirectory(tempPath.toFile());
                System.out.println("🧹 [EXECUTOR] Cleanup: Temp directory deleted.");
            }

        } catch (Exception e) {
            System.err.println("❌ [EXECUTOR FATAL ERROR] Deployment failed for Job " + jobId);
            System.err.println("❌ [REASON]: " + e.getMessage());
            e.printStackTrace(); // Εκτυπώνει όλο το stack trace στα logs
        }
    }

    private void unzipToRoot(byte[] zipData, Path targetPath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String finalPath;

                if (entryName.startsWith("pipelines/.github/")) {
                    finalPath = entryName.substring("pipelines/".length());
                } else if (entryName.startsWith("pipelines/")) {
                    zis.closeEntry();
                    continue;
                } else {
                    finalPath = entryName;
                }

                Path newPath = targetPath.resolve(finalPath);
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    public Map<String, Double> calculateCosts(String targetCloud, String aiSkuResponse) {
        Map<String, Double> costResults = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();

        try {
            // Μετατρέπουμε το JSON απευθείας σε Java Map για να αποφύγουμε τα errors του Iterator
            Map<String, JsonNode> skusMap = mapper.readValue(
                    aiSkuResponse,
                    new TypeReference<Map<String, JsonNode>>() {}
            );

            // Κλασικό Java for-loop πάνω στο Map
            for (Map.Entry<String, JsonNode> entry : skusMap.entrySet()) {
                String computeType = entry.getKey(); // "Virtual Machine", "Container", ή "Kubernetes"
                JsonNode specs = entry.getValue();

                // 1. Δημιουργία δυναμικού Terraform κώδικα (HCL)
                String hclCode = generateHclForCost(targetCloud, computeType, specs);

                // 2. Αν δεν μπορέσαμε να παράγουμε HCL (π.χ. άγνωστος provider), βάζουμε 0.0
                if (hclCode == null || hclCode.isEmpty()) {
                    costResults.put(computeType, 0.0);
                    continue;
                }

                // 3. Αποθήκευση του HCL σε ένα προσωρινό main.tf
                Path tempDir = Files.createTempDirectory("infracost-" + computeType.replace(" ", ""));
                File mainTf = new File(tempDir.toFile(), "main.tf");
                Files.writeString(mainTf.toPath(), hclCode);

                // 4. Εκτέλεση του Infracost CLI πάνω στον προσωρινό φάκελο
                double cost = runInfracostCli(tempDir.toString());
                costResults.put(computeType, cost);

                // 5. Διαγραφή του προσωρινού φακέλου (Cleanup)
                mainTf.delete();
                tempDir.toFile().delete();
            }
        } catch (Exception e) {
            System.err.println("Error calculating costs: " + e.getMessage());
            e.printStackTrace();
        }

        return costResults;
    }

    private String generateHclForCost(String targetCloud, String computeType, JsonNode specs) {
        String hcl = "";

        try {
            // -- AWS MAPPINGS --
            if (targetCloud.equalsIgnoreCase("AWS") || targetCloud.equalsIgnoreCase("Amazon Web Services")) {
                if (computeType.equals("Virtual Machine")) {
                    String instanceType = specs.get("instance_type").asText(); // ΔΥΝΑΜΙΚΟ 100%
                    hcl = String.format("resource \"aws_instance\" \"cost_calc\" { instance_type = \"%s\" }", instanceType);
                }
                else if (computeType.equals("Kubernetes")) {
                    String nodeType = specs.get("instance_type").asText();
                    int count = specs.has("node_count") ? specs.get("node_count").asInt() : 2; // Το count ας έχει ένα fallback
                    hcl = String.format("""
                resource "aws_eks_node_group" "cost_calc" {
                  instance_types = ["%s"]
                  scaling_config { desired_size = %d }
                }
                """, nodeType, count);
                }
                else if (computeType.equals("Container")) {
                    String cpu = specs.get("cpu").asText();
                    String memory = specs.get("memory").asText();
                    hcl = String.format("resource \"aws_ecs_task_definition\" \"cost_calc\" { requires_compatibilities = [\"FARGATE\"] cpu = \"%s\" memory = \"%s\" }", cpu, memory);
                }
            }

            // -- AZURE MAPPINGS --
            else if (targetCloud.equalsIgnoreCase("Azure")) {
                if (computeType.equals("Virtual Machine")) {
                    String size = specs.get("instance_type").asText(); // ΔΥΝΑΜΙΚΟ 100%
                    hcl = String.format("resource \"azurerm_linux_virtual_machine\" \"cost_calc\" { size = \"%s\" }", size);
                }
                else if (computeType.equals("Kubernetes")) {
                    String size = specs.get("instance_type").asText();
                    int count = specs.has("node_count") ? specs.get("node_count").asInt() : 2;
                    hcl = String.format("""
                resource "azurerm_kubernetes_cluster" "cost_calc" {
                  default_node_pool { vm_size = "%s" node_count = %d }
                }
                """, size, count);
                }
                else if (computeType.equals("Container")) {
                    String cpu = specs.get("cpu").asText();
                    String memory = specs.get("memory").asText();
                    hcl = String.format("""
                resource "azurerm_container_group" "cost_calc" {
                  container {
                    cpu    = %s
                    memory = %s
                  }
                }
                """, cpu, memory);
                }
            }
        } catch (NullPointerException e) {
            System.err.println("Το AI δεν επέστρεψε τα σωστά πεδία για το " + computeType);
            return ""; // Επιστρέφει κενό HCL, άρα η κοστολόγηση αυτού του τύπου θα είναι 0.0
        }

        return hcl;
    }

    private double runInfracostCli(String directoryPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "infracost", "breakdown",
                    "--path", directoryPath,
                    "--format", "json"
            );

            Process process = pb.start();
            String jsonOutput = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            // Διαβάζουμε το "totalMonthlyCost" από το output του Infracost
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonOutput);

            if (root.has("totalMonthlyCost")) {
                return root.get("totalMonthlyCost").asDouble();
            }
        } catch (Exception e) {
            System.err.println("Infracost Error: " + e.getMessage());
        }
        return 0.0;
    }
}