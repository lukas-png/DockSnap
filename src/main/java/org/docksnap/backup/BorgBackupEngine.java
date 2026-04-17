package org.docksnap.backup;

import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.proc.CommandRunner;
import org.docksnap.proc.CommandSpec;
import org.docksnap.run.RunLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

        ensureKnownHosts(job, runId, log);

        Map<String, String> env = buildEnv(job.borg());
        if (env.containsKey("BORG_RSH")) {
            log.info(runId, "BORG_RSH: " + env.get("BORG_RSH"));
        }

        ensureRepo(job, runId, log, env);

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

        String target = job.borg().repo() + "::" + archive;
        argv.add(target);
        argv.addAll(job.paths());

        log.info(runId, "BORG: " + String.join(" ", argv));
        runner.run(new CommandSpec(argv, env), line -> log.info(runId, "[borg] " + line));

        if (job.borg().keep() != null && job.borg().keep() > 0) {
            runPrune(job, runId, log, env, prefix);
        }

        return new BackupResult(target, 0L);
    }


    public List<String> testRepo(Job job) throws Exception {
        if (job.borg() == null || job.borg().repo() == null || job.borg().repo().isBlank()) {
            throw new IllegalArgumentException("No borg config on job " + job.id());
        }
        Map<String, String> env = buildEnv(job.borg());
        List<String> output = new ArrayList<>();
        runner.run(new CommandSpec(List.of("borg", "info", job.borg().repo()), env), output::add);
        return output;
    }


    public List<String> keyscan(Job job) throws Exception {
        if (job.borg() == null || job.borg().repo() == null || job.borg().repo().isBlank()) {
            throw new IllegalArgumentException("No borg config on job " + job.id());
        }
        String host = parseHost(job.borg().repo());
        int port = job.borg().sshPort() != null ? job.borg().sshPort() : 22;
        List<String> output = new ArrayList<>();
        runner.run(new CommandSpec(List.of("ssh-keyscan", "-p", String.valueOf(port), host), Map.of()), output::add);
        return output;
    }

    private void ensureKnownHosts(Job job, String runId, RunLogger log) throws Exception {
        Job.BorgOptions borg = job.borg();
        if (borg == null || borg.knownHostsPath() == null || borg.knownHostsPath().isBlank()) return;
        Path kh = Path.of(borg.knownHostsPath());
        if (Files.exists(kh) && Files.size(kh) > 0) return;
        log.info(runId, "known_hosts missing or empty — running ssh-keyscan...");
        List<String> lines = keyscan(job);
        if (kh.getParent() != null) {
            Files.createDirectories(kh.getParent());
        }
        Files.write(kh, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info(runId, "known_hosts populated (" + lines.size() + " lines).");
    }

    private void ensureRepo(Job job, String runId, RunLogger log, Map<String, String> env) throws Exception {
        try {
            runner.run(new CommandSpec(List.of("borg", "info", job.borg().repo()), env),
                    line -> log.info(runId, "[borg-info] " + line));
            log.info(runId, "Borg repo exists.");
        } catch (Exception e) {
            log.info(runId, "Borg repo not found or inaccessible — attempting init: " + e.getMessage());
            String enc = (job.borg().encryption() != null && !job.borg().encryption().isBlank())
                    ? job.borg().encryption() : "none";
            runner.run(new CommandSpec(List.of("borg", "init", "--encryption=" + enc, job.borg().repo()), env),
                    line -> log.info(runId, "[borg-init] " + line));
            log.info(runId, "Borg repo initialized with encryption=" + enc);
        }
    }

    private void runPrune(Job job, String runId, RunLogger log, Map<String, String> env, String prefix) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("borg"); argv.add("prune");
        argv.add("--keep-last"); argv.add(String.valueOf(job.borg().keep()));
        argv.add("--glob-archives"); argv.add(prefix + "-*");
        argv.add(job.borg().repo());
        log.info(runId, "BORG prune: " + String.join(" ", argv));
        runner.run(new CommandSpec(argv, env), line -> log.info(runId, "[borg-prune] " + line));
    }

    private Map<String, String> buildEnv(Job.BorgOptions borg) {
        if (borg.sshKeyPath() == null || borg.sshKeyPath().isBlank()) {
            return Map.of();
        }
        StringBuilder sshCmd = new StringBuilder("ssh -i ").append(borg.sshKeyPath());
        if (borg.sshPort() != null) {
            sshCmd.append(" -p ").append(borg.sshPort());
        }
        sshCmd.append(" -o StrictHostKeyChecking=yes");
        if (borg.knownHostsPath() != null && !borg.knownHostsPath().isBlank()) {
            sshCmd.append(" -o UserKnownHostsFile=").append(borg.knownHostsPath());
        }
        return Map.of("BORG_RSH", sshCmd.toString());
    }

    private String parseHost(String repo) {
        if (repo.startsWith("ssh://")) {
            String s = repo.substring(6);
            if (s.contains("@")) s = s.substring(s.indexOf('@') + 1);
            if (s.contains(":"))      return s.substring(0, s.indexOf(':'));
            else if (s.contains("/")) return s.substring(0, s.indexOf('/'));
            return s;
        }
        // SCP style: [user@]host:path
        String s = repo.contains("@") ? repo.substring(repo.indexOf('@') + 1) : repo;
        return s.contains(":") ? s.substring(0, s.indexOf(':')) : s;
    }
}
