package org.docksnap.config;

import org.docksnap.domain.BackupMode;
import org.docksnap.domain.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class StartupValidator {

    public void validate(AppConfig config, List<Job> jobs) {
        ensureWritableDir(config.backupDir(), "BACKUP_DIR");
        ensureWritableDir(config.backupDir().resolve("logs"), "BACKUP_DIR/logs");
        checkReadable(config.jobsFile(), "JOBS_FILE");
        checkDockerSocket();

        boolean hasBorgJob = jobs.stream().anyMatch(j -> j.mode() == BackupMode.BORG);
        if (hasBorgJob && !commandExists("borg")) {
            warn("'borg' binary not found in PATH — all BORG jobs will fail at runtime");
        }

        for (Job job : jobs) {
            if (job.mode() != BackupMode.BORG || job.borg() == null) continue;
            String id = job.id();

            if (job.borg().sshKeyPath() != null && !job.borg().sshKeyPath().isBlank()) {
                Path key = Path.of(job.borg().sshKeyPath());
                if (!Files.exists(key) || !Files.isReadable(key)) {
                    warn("Job '" + id + "': sshKeyPath not found or not readable: " + key);
                }
            }

            if (job.borg().knownHostsPath() != null && !job.borg().knownHostsPath().isBlank()) {
                Path kh = Path.of(job.borg().knownHostsPath());
                Path parent = kh.getParent();
                if (parent != null && (!Files.exists(parent) || !Files.isWritable(parent))) {
                    warn("Job '" + id + "': knownHostsPath parent directory not writable: " + parent
                            + " — auto-keyscan will fail");
                }
            }
        }

        System.out.println("[Startup] Validation complete.");
    }

    private void ensureWritableDir(Path dir, String label) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "[Startup] Cannot create " + label + " directory: " + dir + " — " + e.getMessage());
            }
        }
        if (!Files.isWritable(dir)) {
            throw new IllegalStateException(
                    "[Startup] " + label + " directory is not writable: " + dir);
        }
    }

    private void checkReadable(Path file, String label) {
        if (!Files.exists(file)) {
            throw new IllegalStateException(
                    "[Startup] " + label + " file not found: " + file);
        }
        if (!Files.isReadable(file)) {
            throw new IllegalStateException(
                    "[Startup] " + label + " file is not readable: " + file);
        }
    }

    private void checkDockerSocket() {
        String host = System.getenv("DOCKER_HOST");
        if (host != null && !host.isBlank()) return;
        Path sock = Path.of("/var/run/docker.sock");
        if (!Files.exists(sock) || !Files.isReadable(sock)) {
            warn("Docker socket not found or not readable at " + sock
                    + " — container stop/start will fail (set DOCKER_HOST to override)");
        }
    }

    private boolean commandExists(String cmd) {
        try {
            new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor();
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private void warn(String msg) {
        System.err.println("[WARN][Startup] " + msg);
    }
}
