package com.christos_bramis.bram_vortex_execution.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class ExecutionService {

    private final VaultService vaultService;

    public ExecutionService(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    public void executeCommit(String username, String repoUrl, Map<String, String> filesToCommit) throws Exception {
        // 1. Λήψη token από Vault
        String token = vaultService.getGithubToken(username);
        if (token == null) throw new RuntimeException("GitHub Token not found for user: " + username);

        // 2. Δημιουργία προσωρινού φακέλου για το Clone
        Path tempDir = Files.createTempDirectory("bram-vortex-repo-");
        File localPath = tempDir.toFile();

        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(localPath)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                .call()) {

            // 3. Προσθήκη των αρχείων (Dockerfiles, Workflows κλπ)
            for (Map.Entry<String, String> entry : filesToCommit.entrySet()) {
                File file = new File(localPath, entry.getKey());
                file.getParentFile().mkdirs(); // Δημιουργία υποφακέλων αν χρειάζεται (π.χ. .github/workflows)
                Files.writeString(file.toPath(), entry.getValue());
            }

            // 4. Git Add, Commit & Push
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Bram Vortex: Automated Infrastructure and Pipeline setup")
                    .setAuthor("Bram Vortex", "no-reply@bramvortex.com")
                    .call();

            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                    .call();

            System.out.println("✅ Successfully pushed changes to " + repoUrl);
        } finally {
            // Καθαρισμός προσωρινών αρχείων
            deleteDirectory(localPath);
        }
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File f : files) deleteDirectory(f);
        }
        directory.delete();
    }
}
