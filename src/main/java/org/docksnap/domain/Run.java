package org.docksnap.domain;

import java.time.Instant;
import java.util.UUID;

public record Run(
        String id,
        String jobId,
        String jobName,
        BackupMode mode,
        boolean stopContainers,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        String artifact,
        Long bytes,
        String error
) {
    public static Run start(Job job, boolean stopContainers) {
        return new Run(
                UUID.randomUUID().toString(),
                job.id(),
                job.name(),
                job.mode(),
                stopContainers,
                RunStatus.RUNNING,
                Instant.now(),
                null,
                null,
                null,
                null
        );
    }

    public Run success(String artifact, long bytes) {
        return new Run(id, jobId, jobName, mode, stopContainers, RunStatus.SUCCESS,
                startedAt, Instant.now(), artifact, bytes, null);
    }

    public Run failed(String error) {
        return new Run(id, jobId, jobName, mode, stopContainers, RunStatus.FAILED,
                startedAt, Instant.now(), artifact, bytes, error);
    }
}
