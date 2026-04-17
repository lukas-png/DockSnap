package org.docksnap.agent;

import org.docksnap.backup.BackupEngineFactory;
import org.docksnap.docker.DockerGateway;
import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.domain.Run;
import org.docksnap.run.LogBuffer;
import org.docksnap.run.RunLogger;
import org.docksnap.run.RunRepository;
import org.docksnap.upload.Uploader;
import org.docksnap.upload.UploaderFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JobRunner {

    private final BackupEngineFactory engineFactory;
    private final DockerGateway docker;
    private final RunRepository runs;
    private final LogBuffer logs;
    private final UploaderFactory uploaderFactory;
    private final ExecutorService executor;
    private final RunLogger runLogger;

    public JobRunner(BackupEngineFactory engineFactory,
                     DockerGateway docker,
                     RunRepository runs,
                     LogBuffer logs,
                     UploaderFactory uploaderFactory) {
        this.engineFactory = engineFactory;
        this.docker = docker;
        this.runs = runs;
        this.logs = logs;
        this.uploaderFactory = uploaderFactory;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "jobrunner");
            t.setDaemon(true);
            return t;
        });
        this.runLogger = new RunLogger() {
            @Override
            public void info(String runId, String msg) {
                logs.append(runId, "INFO", msg);
                System.out.printf("[INFO][%s] %s%n", runId, msg);
            }
            @Override
            public void error(String runId, String msg) {
                logs.append(runId, "ERROR", msg);
                System.err.printf("[ERROR][%s] %s%n", runId, msg);
            }
        };
    }

    /**
     * Submits a job for asynchronous execution. Creates and persists a RUNNING Run
     * record immediately; the actual backup runs in the thread pool.
     */
    public Run submit(Job job) {
        boolean willStop = job.stopContainers() != null && !job.stopContainers().isEmpty();
        Run run = Run.start(job, willStop);
        runs.save(run);
        String runId = run.id();
        executor.submit(() -> execute(job, runId));
        return run;
    }

    private void execute(Job job, String runId) {
        runLogger.info(runId, "Job started: " + job.name());

        // 1. Stop containers
        if (job.stopContainers() != null) {
            for (String name : job.stopContainers()) {
                try {
                    runLogger.info(runId, "Stopping container: " + name);
                    docker.stopContainerByName(name, 30);
                } catch (Exception e) {
                    runLogger.error(runId, "Failed to stop " + name + ": " + e.getMessage());
                }
            }
        }

        // 2. Run backup engine
        BackupResult result = null;
        try {
            var engine = engineFactory.forMode(job.mode());
            result = engine.backup(job, runId, runLogger);
            runLogger.info(runId, "Backup complete: " + result.artifact() + " (" + result.bytes() + " bytes)");
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            runLogger.error(runId, "Backup failed: " + detail);
            e.printStackTrace();
            startContainers(job, runId);
            runs.update(runs.find(runId).failed(detail));
            return;
        }

        // 3. Start containers (runs even if backup failed — already returned above on failure)
        startContainers(job, runId);

        // 4. Upload (failure is non-fatal — artifact is already on disk)
        try {
            Uploader uploader = uploaderFactory.forJob(job);
            Path artifact = result.artifact() != null ? Path.of(result.artifact()) : null;
            uploader.upload(job, runId, runLogger, artifact);
        } catch (Exception e) {
            runLogger.error(runId, "Upload failed: " + e.getMessage());
        }

        // 5. Mark success
        runs.update(runs.find(runId).success(result.artifact(), result.bytes()));
        runLogger.info(runId, "Job finished successfully.");
    }

    private void startContainers(Job job, String runId) {
        if (job.startContainers() != null) {
            for (String name : job.startContainers()) {
                try {
                    runLogger.info(runId, "Starting container: " + name);
                    docker.startContainerByName(name);
                } catch (Exception e) {
                    runLogger.error(runId, "Failed to start " + name + ": " + e.getMessage());
                }
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
