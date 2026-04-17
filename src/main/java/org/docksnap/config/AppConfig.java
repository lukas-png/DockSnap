package org.docksnap.config;

import java.nio.file.Path;

public record AppConfig(
        int port,
        Path backupDir,
        Path jobsFile,
        boolean apiEnabled,
        String ntfyUrl
) {
    public static AppConfig fromEnv() {
        int port = Integer.parseInt(env("PORT", "8080"));
        Path backupDir = Path.of(env("BACKUP_DIR", "/backups"));
        Path jobsFile = Path.of(env("JOBS_FILE", "/config/jobs.json"));
        boolean apiEnabled = !env("API_ENABLED", "true").equalsIgnoreCase("false");
        String ntfyUrl = System.getenv("NTFY_URL");
        return new AppConfig(port, backupDir, jobsFile, apiEnabled, ntfyUrl);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }
}
