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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

                System.out.println("🚀 [EXECUTOR] Step 7: Pushing to GitHub (branch: main)...");

                Iterable<PushResult> results = git.push()
                        .setRemote("origin")
                        // Αυτό αναγκάζει το τοπικό master/main να πάει στο remote main
                        .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main"))
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                        .call();
                // Έλεγχος αν το push έγινε όντως δεκτό από το GitHub
                for (PushResult result : results) {
                    for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                        if (update.getStatus() != RemoteRefUpdate.Status.OK &&
                                update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                            throw new RuntimeException("Push failed with status: " + update.getStatus());
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
}