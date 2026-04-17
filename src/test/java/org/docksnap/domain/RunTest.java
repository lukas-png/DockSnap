package org.docksnap.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunTest {

    private static Job job(String id, List<String> stopContainers) {
        return new Job(id, "Test Job", BackupMode.TAR,
                List.of("/data"), stopContainers, List.of(), "prefix",
                null, null, "0 2 * * *", null);
    }

    @Test
    void start_populatesAllFields() {
        Run run = Run.start(job("j1", List.of("db")), true);

        assertNotNull(run.id());
        assertFalse(run.id().isBlank());
        assertEquals("j1", run.jobId());
        assertEquals("Test Job", run.jobName());
        assertEquals(BackupMode.TAR, run.mode());
        assertTrue(run.stopContainers());
        assertEquals(RunStatus.RUNNING, run.status());
        assertNotNull(run.startedAt());
        assertNull(run.finishedAt());
        assertNull(run.artifact());
        assertNull(run.bytes());
        assertNull(run.error());
    }

    @Test
    void start_uniqueIds() {
        Job j = job("j1", List.of());
        String id1 = Run.start(j, false).id();
        String id2 = Run.start(j, false).id();
        assertNotEquals(id1, id2);
    }

    @Test
    void success_transitionsStatus() {
        Run run = Run.start(job("j1", List.of()), false)
                .success("/backups/file.tar.gz", 4096L);

        assertEquals(RunStatus.SUCCESS, run.status());
        assertEquals("/backups/file.tar.gz", run.artifact());
        assertEquals(4096L, run.bytes());
        assertNotNull(run.finishedAt());
        assertNull(run.error());
    }

    @Test
    void failed_transitionsStatus() {
        Run run = Run.start(job("j1", List.of()), false)
                .failed("disk full");

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("disk full", run.error());
        assertNotNull(run.finishedAt());
    }

    @Test
    void success_preservesImmutableFields() {
        Run initial = Run.start(job("j1", List.of()), false);
        Run done = initial.success("/out.tar.gz", 100L);

        assertEquals(initial.id(), done.id());
        assertEquals(initial.jobId(), done.jobId());
        assertEquals(initial.startedAt(), done.startedAt());
    }
}
