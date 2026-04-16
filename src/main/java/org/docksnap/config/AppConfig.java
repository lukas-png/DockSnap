package org.docksnap.config;

import java.nio.file.Path;

public record AppConfig(
        int port,
        Path backupDir,
        Path jobsFile
) {
    public static AppConfig fromEnv() {
        int port = Integer.parseInt(env("PORT", "8080"));
        Path backupDir = Path.of(env("BACKUP_DIR", "/backups"));
        Path jobsFile = Path.of(env("JOBS_FILE", "/config/jobs.json"));
        return new AppConfig(port, backupDir, jobsFile);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }
}
