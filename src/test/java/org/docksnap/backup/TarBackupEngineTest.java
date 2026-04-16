package org.docksnap.backup;

import org.docksnap.domain.BackupMode;
import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.proc.CommandResult;
import org.docksnap.proc.CommandSpec;
import org.docksnap.run.RunLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TarBackupEngineTest {

    @TempDir
    Path tempDir;

    private static final RunLogger NO_OP_LOGGER = new RunLogger() {
        @Override public void info(String runId, String msg) {}
        @Override public void error(String runId, String msg) {}
    };

    private Job tarJob(String id, String prefix, List<String> paths) {
        return new Job(id, "Tar Job", BackupMode.TAR, paths,
                List.of(), List.of(), prefix, null, null, null);
    }

    /**
     * Fake runner that creates the output file at the path in argv[2]
     * so that TarBackupEngine's Files.size() call succeeds.
     */
    private record CapturingRunner(List<CommandSpec> captured) implements org.docksnap.proc.CommandRunner {
        @Override
        public CommandResult run(CommandSpec spec, LineSink sink) throws Exception {
            Files.writeString(Path.of(spec.argv().get(2)), "fake tar content");
            captured.add(spec);
            return new CommandResult(0);
        }
    }

    @Test
    void backup_buildsCorrectTarCommand() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        TarBackupEngine engine = new TarBackupEngine(tempDir, new CapturingRunner(captured));
        Job job = tarJob("job1", "myprefix", List.of("/var/data", "/etc/config"));

        BackupResult result = engine.backup(job, "run-1", NO_OP_LOGGER);

        assertEquals(1, captured.size());
        List<String> argv = captured.get(0).argv();
        assertEquals("tar", argv.get(0));
        assertEquals("czf", argv.get(1));
        assertTrue(argv.get(2).contains("myprefix"), "output filename should contain prefix");
        assertTrue(argv.get(2).endsWith(".tar.gz"));
        assertEquals("-C", argv.get(3));
        assertEquals("/", argv.get(4));
        assertTrue(argv.contains("var/data"), "absolute path should be stripped of leading slash");
        assertTrue(argv.contains("etc/config"));
    }

    @Test
    void backup_usesjobIdWhenPrefixIsBlank() throws Exception {
        List<CommandSpec> captured = new ArrayList<>();
        TarBackupEngine engine = new TarBackupEngine(tempDir, new CapturingRunner(captured));
        Job job = tarJob("my-job-id", "", List.of("/data"));

        engine.backup(job, "run-1", NO_OP_LOGGER);

        String outPath = captured.get(0).argv().get(2);
        assertTrue(outPath.contains("my-job-id"));
    }

    @Test
    void backup_returnsArtifactPathAndBytes() throws Exception {
        TarBackupEngine engine = new TarBackupEngine(tempDir, new CapturingRunner(new ArrayList<>()));
        Job job = tarJob("j1", "snap", List.of("/data"));

        BackupResult result = engine.backup(job, "r1", NO_OP_LOGGER);

        assertNotNull(result.artifact());
        assertTrue(result.artifact().endsWith(".tar.gz"));
        assertEquals("fake tar content".length(), result.bytes());
    }

    @Test
    void backup_createsBackupDirectory() throws Exception {
        Path subDir = tempDir.resolve("deep/nested");
        TarBackupEngine engine = new TarBackupEngine(subDir, new CapturingRunner(new ArrayList<>()));
        Job job = tarJob("j1", "snap", List.of("/data"));

        engine.backup(job, "r1", NO_OP_LOGGER);

        assertTrue(Files.isDirectory(subDir));
    }

    @Test
    void backup_commandFailure_propagatesException() {
        org.docksnap.proc.CommandRunner failingRunner = (spec, sink) -> {
            throw new RuntimeException("Command failed (1): tar czf ...");
        };
        TarBackupEngine engine = new TarBackupEngine(tempDir, failingRunner);
        Job job = tarJob("j1", "snap", List.of("/data"));

        assertThrows(RuntimeException.class, () -> engine.backup(job, "r1", NO_OP_LOGGER));
    }
}