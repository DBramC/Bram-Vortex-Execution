package com.christos_bramis.bram_vortex_execution.service;

import com.christos_bramis.bram_vortex_execution.entity.ValidatorJob;
import com.christos_bramis.bram_vortex_execution.repository.ValidatorJobsRepository;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public void processDeployment(String username, String jobId, String repoUrl) throws Exception {
        // 1. Τραβάμε το Job από τη βάση
        ValidatorJob job = validatorJobsRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found"));

        // 2. Παίρνουμε το Token από το Vault
        String githubToken = vaultService.getGithubToken(username);

        // 3. Clone & Extract Logic
        Path tempPath = Files.createTempDirectory("vortex-exec-");

        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(tempPath.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                .call()) {

            // Αποσυμπίεση του ZIP απευθείας στο root του cloned repo
            unzipToRoot(job.getMasterZip(), tempPath);

            // 4. Commit & Push
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Bram Vortex: Infrastructure and Pipelines Setup")
                    .setAuthor("Bram Vortex", "no-reply@bramvortex.com")
                    .call();

            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                    .call();
        } finally {
            FileUtils.deleteDirectory(tempPath.toFile());
        }
    }

    private void unzipToRoot(byte[] zipData, Path targetPath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String finalPath;

                // 1. Αν είναι το .github μέσα στο pipelines, αφαίρεσε το "pipelines/"
                if (entryName.startsWith("pipelines/.github/")) {
                    finalPath = entryName.substring("pipelines/".length());
                }
                // 2. Αν είναι ο φάκελος pipelines (ή οτιδήποτε άλλο μέσα του εκτός του .github), αγνόησέ τον
                else if (entryName.startsWith("pipelines/")) {
                    zis.closeEntry();
                    continue;
                }
                // 3. Όλα τα υπόλοιπα (terraform, ansible κλπ) μένουν ως έχουν
                else {
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