package org.docksnap.agent;

import org.docksnap.backup.BackupEngine;
import org.docksnap.backup.BackupEngineFactory;
import org.docksnap.docker.ContainerInfo;
import org.docksnap.docker.DockerGateway;
import org.docksnap.domain.BackupMode;
import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.domain.Run;
import org.docksnap.domain.RunStatus;
import org.docksnap.run.InMemoryRunRepository;
import org.docksnap.run.LogBuffer;
import org.docksnap.upload.UploaderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobRunnerTest {

    // --- Fakes ---

    static class FakeDockerGateway implements DockerGateway {
        final List<String> stopped = new ArrayList<>();
        final List<String> started = new ArrayList<>();

        @Override
        public List<ContainerInfo> listContainers() { return List.of(); }

        @Override
        public void stopContainerByName(String name, int timeoutSeconds) {
            stopped.add(name);
        }

        @Override
        public void startContainerByName(String name) {
            started.add(name);
        }
    }

    static BackupEngine successEngine(String artifact, long bytes) {
        return (job, runId, log) -> new BackupResult(artifact, bytes);
    }

    static BackupEngine failingEngine(String message) {
        return (job, runId, log) -> { throw new RuntimeException(message); };
    }

    // --- Setup ---

    private JobRunner runner;
    private InMemoryRunRepository runs;
    private LogBuffer logs;
    private FakeDockerGateway docker;

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        logs = new LogBuffer(null);
        docker = new FakeDockerGateway();
    }

    @AfterEach
    void tearDown() {
        if (runner != null) runner.shutdown();
    }

    private JobRunner buildRunner(BackupEngine engine) {
        BackupEngineFactory factory = new BackupEngineFactory(
                Map.of(BackupMode.TAR, engine, BackupMode.BORG, engine));
        return new JobRunner(factory, docker, runs, logs, new UploaderFactory(), new org.docksnap.notify.NtfyNotifier(null));
    }

    private Job job(String id, List<String> stop, List<String> start) {
        return new Job(id, "Test Job", BackupMode.TAR, List.of("/data"),
                stop, start, "prefix", null, null, null, null);
    }

    private Run waitForCompletion(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Run r = runs.find(runId);
            if (r != null && r.status() != RunStatus.RUNNING) return r;
            Thread.sleep(20);
        }
        return runs.find(runId);
    }

    // --- Tests ---

    @Test
    void submit_returnsRunningRunImmediately() {
        runner = buildRunner(successEngine("/out.tar.gz", 100L));
        Job job = job("j1", List.of(), List.of());

        Run run = runner.submit(job);

        assertEquals(RunStatus.RUNNING, run.status());
        assertNotNull(runs.find(run.id()));
    }

    @Test
    void submit_marksRunSuccessOnCompletion() throws Exception {
        runner = buildRunner(successEngine("/backups/snap.tar.gz", 512L));
        Job job = job("j1", List.of(), List.of());

        Run submitted = runner.submit(job);
        Run finished = waitForCompletion(submitted.id());

        assertEquals(RunStatus.SUCCESS, finished.status());
        assertEquals("/backups/snap.tar.gz", finished.artifact());
        assertEquals(512L, finished.bytes());
        assertNotNull(finished.finishedAt());
    }

    @Test
    void submit_marksRunFailedWhenBackupThrows() throws Exception {
        runner = buildRunner(failingEngine("disk full"));
        Job job = job("j1", List.of(), List.of());

        Run submitted = runner.submit(job);
        Run finished = waitForCompletion(submitted.id());

        assertEquals(RunStatus.FAILED, finished.status());
        assertTrue(finished.error().contains("disk full"));
    }

    @Test
    void submit_stopsContainersBeforeBackup() throws Exception {
        runner = buildRunner(successEngine("/out.tar.gz", 0L));
        Job job = job("j1", List.of("postgres", "app"), List.of());

        Run submitted = runner.submit(job);
        waitForCompletion(submitted.id());

        assertTrue(docker.stopped.contains("postgres"));
        assertTrue(docker.stopped.contains("app"));
    }

    @Test
    void submit_startsContainersAfterSuccess() throws Exception {
        runner = buildRunner(successEngine("/out.tar.gz", 0L));
        Job job = job("j1", List.of("postgres"), List.of("postgres"));

        Run submitted = runner.submit(job);
        waitForCompletion(submitted.id());

        assertTrue(docker.started.contains("postgres"));
    }

    @Test
    void submit_startsContainersEvenAfterBackupFailure() throws Exception {
        runner = buildRunner(failingEngine("oops"));
        Job job = job("j1", List.of("postgres"), List.of("postgres"));

        Run submitted = runner.submit(job);
        waitForCompletion(submitted.id());

        assertTrue(docker.started.contains("postgres"),
                "containers must be started even if backup failed");
    }

    @Test
    void submit_noContainers_doesNotCallDocker() throws Exception {
        runner = buildRunner(successEngine("/out.tar.gz", 0L));
        Job job = job("j1", List.of(), List.of());

        Run submitted = runner.submit(job);
        waitForCompletion(submitted.id());

        assertTrue(docker.stopped.isEmpty());
        assertTrue(docker.started.isEmpty());
    }

    @Test
    void submit_logsAreWrittenToBuffer() throws Exception {
        runner = buildRunner(successEngine("/out.tar.gz", 0L));
        Job job = job("j1", List.of(), List.of());

        Run submitted = runner.submit(job);
        waitForCompletion(submitted.id());

        List<String> lines = logs.tail(submitted.id(), 100);
        assertFalse(lines.isEmpty(), "log buffer should contain output for the run");
    }

    @Test
    void submit_multipleJobsRunConcurrently() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        BackupEngine blockingEngine = (job, runId, log) -> {
            latch.await(3, TimeUnit.SECONDS);
            return new BackupResult("/out.tar.gz", 0L);
        };
        runner = buildRunner(blockingEngine);
        Job j1 = job("j1", List.of(), List.of());
        Job j2 = job("j2", List.of(), List.of());

        Run r1 = runner.submit(j1);
        Run r2 = runner.submit(j2);
        latch.countDown();

        waitForCompletion(r1.id());
        waitForCompletion(r2.id());

        assertEquals(RunStatus.SUCCESS, runs.find(r1.id()).status());
        assertEquals(RunStatus.SUCCESS, runs.find(r2.id()).status());
    }
}
