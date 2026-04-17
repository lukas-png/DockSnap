package org.docksnap.backup;

import org.docksnap.domain.BackupMode;
import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.proc.CommandResult;
import org.docksnap.proc.CommandSpec;
import org.docksnap.run.RunLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BorgBackupEngineTest {

    private static final RunLogger NO_OP_LOGGER = new RunLogger() {
        @Override public void info(String runId, String msg) {}
        @Override public void error(String runId, String msg) {}
    };

    private record CapturingRunner(List<CommandSpec> captured) implements org.docksnap.proc.CommandRunner {
        @Override
        public CommandResult run(CommandSpec spec, LineSink sink) {
            captured.add(spec);
            return new CommandResult(0);
        }
    }

    private Job borgJob(String id, String repo, String archivePrefix, String compression,
                        String sshKey, String knownHosts, List<String> paths) {
        Job.BorgOptions borg = new Job.BorgOptions(repo, archivePrefix, compression, sshKey, knownHosts, null, null, null);
        return new Job(id, "Borg Job", BackupMode.BORG, paths,
                List.of(), List.of(), null, borg, null, null);
    }

    @Test
    void backup_buildsBorgCreateCommand() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        BorgBackupEngine engine = new BorgBackupEngine(new CapturingRunner(captured));
        Job job = borgJob("j1", "user@host:/repo", "snap", "lz4", null, null,
                List.of("/var/data"));

        BackupResult result = engine.backup(job, "r1", NO_OP_LOGGER);

        CommandSpec createCmd = captured.stream().filter(c -> c.argv().contains("create")).findFirst().orElseThrow();
        List<String> argv = createCmd.argv();
        assertEquals("borg", argv.get(0));
        assertEquals("create", argv.get(1));
        assertTrue(argv.contains("--stats"));
        assertTrue(argv.contains("--show-rc"));
        assertTrue(argv.contains("--compression"));
        assertTrue(argv.contains("lz4"));
        assertTrue(argv.stream().anyMatch(a -> a.startsWith("user@host:/repo::snap-")));
        assertTrue(argv.contains("/var/data"));
    }

    @Test
    void backup_usesJobIdWhenArchivePrefixIsBlank() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        BorgBackupEngine engine = new BorgBackupEngine(new CapturingRunner(captured));
        Job job = borgJob("my-job", "user@host:/repo", "", null, null, null, List.of("/data"));

        engine.backup(job, "r1", NO_OP_LOGGER);

        CommandSpec createCmd = captured.stream().filter(c -> c.argv().contains("create")).findFirst().orElseThrow();
        String target = createCmd.argv().stream()
                .filter(a -> a.contains("::"))
                .findFirst().orElseThrow();
        assertTrue(target.startsWith("user@host:/repo::my-job-"));
    }

    @Test
    void backup_setsBorgRsh_whenSshKeyConfigured() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        BorgBackupEngine engine = new BorgBackupEngine(new CapturingRunner(captured));
        Job job = borgJob("j1", "user@host:/repo", "snap", null,
                "/secrets/id_rsa", "/secrets/known_hosts", List.of("/data"));

        engine.backup(job, "r1", NO_OP_LOGGER);

        CommandSpec createCmd = captured.stream().filter(c -> c.argv().contains("create")).findFirst().orElseThrow();
        String borgRsh = createCmd.env().get("BORG_RSH");
        assertNotNull(borgRsh);
        assertTrue(borgRsh.contains("-i /secrets/id_rsa"));
        assertTrue(borgRsh.contains("UserKnownHostsFile=/secrets/known_hosts"));
    }

    @Test
    void backup_noBorgRsh_whenNoSshKey() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        BorgBackupEngine engine = new BorgBackupEngine(new CapturingRunner(captured));
        Job job = borgJob("j1", "user@host:/repo", "snap", null, null, null, List.of("/data"));

        engine.backup(job, "r1", NO_OP_LOGGER);

        CommandSpec createCmd = captured.stream().filter(c -> c.argv().contains("create")).findFirst().orElseThrow();
        assertFalse(createCmd.env().containsKey("BORG_RSH"));
    }

    @Test
    void backup_missingRepo_throwsIllegalArgument() {
        BorgBackupEngine engine = new BorgBackupEngine((spec, sink) -> new CommandResult(0));
        Job job = borgJob("j1", null, "snap", null, null, null, List.of("/data"));

        assertThrows(IllegalArgumentException.class,
                () -> engine.backup(job, "r1", NO_OP_LOGGER));
    }

    @Test
    void backup_nullBorgOptions_throwsIllegalArgument() {
        BorgBackupEngine engine = new BorgBackupEngine((spec, sink) -> new CommandResult(0));
        Job job = new Job("j1", "Job", BackupMode.BORG, List.of("/data"),
                List.of(), List.of(), null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> engine.backup(job, "r1", NO_OP_LOGGER));
    }

    @Test
    void backup_returnsTargetAsArtifact() throws Exception {
        BorgBackupEngine engine = new BorgBackupEngine(
                new CapturingRunner(new ArrayList<>()));
        Job job = borgJob("j1", "user@host:/repo", "snap", null, null, null, List.of("/data"));

        BackupResult result = engine.backup(job, "r1", NO_OP_LOGGER);

        assertTrue(result.artifact().startsWith("user@host:/repo::snap-"));
        assertEquals(0L, result.bytes());
    }

    @Test
    void backup_omitsCompressionFlag_whenBlank() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        BorgBackupEngine engine = new BorgBackupEngine(new CapturingRunner(captured));
        Job job = borgJob("j1", "user@host:/repo", "snap", "", null, null, List.of("/data"));

        engine.backup(job, "r1", NO_OP_LOGGER);

        CommandSpec createCmd = captured.stream().filter(c -> c.argv().contains("create")).findFirst().orElseThrow();
        assertFalse(createCmd.argv().contains("--compression"));
    }
}
