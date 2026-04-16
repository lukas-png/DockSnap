package org.docksnap.backup;

import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.proc.CommandRunner;
import org.docksnap.proc.CommandSpec;
import org.docksnap.run.RunLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TarBackupEngine implements BackupEngine {

    private final Path backupDir;
    private final CommandRunner runner;

    public TarBackupEngine(Path backupDir, CommandRunner runner) {
        this.backupDir = backupDir;
        this.runner = runner;
    }

    @Override
    public BackupResult backup(Job job, String runId, RunLogger log) throws Exception {
        Files.createDirectories(backupDir);

        String prefix = (job.filenamePrefix() != null && !job.filenamePrefix().isBlank())
                ? job.filenamePrefix() : job.id();

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path out = backupDir.resolve(prefix + "-" + ts + ".tar.gz");

        List<String> argv = new ArrayList<String>();
        argv.add("tar"); argv.add("czf"); argv.add(out.toString());
        argv.add("-C"); argv.add("/");

        for (String p : job.paths()) {
            String rel = p.startsWith("/") ? p.substring(1) : p;
            argv.add(rel);
        }

        log.info(runId, "TAR: " + String.join(" ", argv));
        runner.run(CommandSpec.of(argv), line -> log.info(runId, "[tar] " + line));

        long bytes = Files.size(out);
        return new BackupResult(out.toString(), bytes);
    }
}
