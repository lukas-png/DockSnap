
package org.docksnap.backup;

import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.proc.CommandRunner;
import org.docksnap.proc.CommandSpec;
import org.docksnap.run.RunLogger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class BorgBackupEngine implements BackupEngine {

    private final CommandRunner runner;

    public BorgBackupEngine(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public BackupResult backup(Job job, String runId, RunLogger log) throws Exception {
        if (job.borg() == null || job.borg().repo() == null || job.borg().repo().isBlank()) {
            throw new IllegalArgumentException("BORG job requires borg.repo");
        }

        String prefix = (job.borg().archivePrefix() != null && !job.borg().archivePrefix().isBlank())
                ? job.borg().archivePrefix() : job.id();

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        String archive = prefix + "-" + ts;

        List<String> argv = new ArrayList<>();
        argv.add("borg"); argv.add("create");
        argv.add("--stats"); argv.add("--show-rc");

        if (job.borg().compression() != null && !job.borg().compression().isBlank()) {
            argv.add("--compression"); argv.add(job.borg().compression());
        }

        Map<String, String> env = new HashMap<>();
        if (job.borg().sshKeyPath() != null && !job.borg().sshKeyPath().isBlank()) {
            String sshCmd = "ssh -i " + job.borg().sshKeyPath() + " -o StrictHostKeyChecking=yes";
            if (job.borg().knownHostsPath() != null && !job.borg().knownHostsPath().isBlank()) {
                sshCmd += " -o UserKnownHostsFile=" + job.borg().knownHostsPath();
            }
            env.put("BORG_RSH", sshCmd);
            log.info(runId, "BORG_RSH configured.");
        }

        String target = job.borg().repo() + "::" + archive;
        argv.add(target);
        argv.addAll(job.paths());

        log.info(runId, "BORG: " + String.join(" ", argv));
        runner.run(new CommandSpec(argv, env), line -> log.info(runId, "[borg] " + line));

        // bytes schwer; MVP 0
        return new BackupResult(target, 0L);
    }
}
