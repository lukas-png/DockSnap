package org.docksnap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.docksnap.agent.JobRunner;
import org.docksnap.agent.JobScheduler;
import org.docksnap.backup.BackupEngineFactory;
import org.docksnap.backup.BorgBackupEngine;
import org.docksnap.backup.TarBackupEngine;
import org.docksnap.config.AppConfig;
import org.docksnap.config.JobConfigStore;
import org.docksnap.docker.DockerJavaGateway;
import org.docksnap.domain.BackupMode;
import org.docksnap.http.ApiServer;
import org.docksnap.proc.ProcessCommandRunner;
import org.docksnap.run.InMemoryRunRepository;
import org.docksnap.run.LogBuffer;
import org.docksnap.upload.UploaderFactory;

import java.time.Duration;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {

        // --- Configuration ---
        AppConfig config = AppConfig.fromEnv();

        // --- Jackson ObjectMapper ---
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // --- Job configuration ---
        JobConfigStore jobConfigStore = new JobConfigStore(config.jobsFile(), om);

        // --- Docker client (connects to DOCKER_HOST or /var/run/docker.sock) ---
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        DockerClient dockerClient = DockerClientImpl.getInstance(dockerConfig, httpClient);
        DockerJavaGateway dockerGateway = new DockerJavaGateway(dockerClient);

        // --- Command runner ---
        ProcessCommandRunner commandRunner = new ProcessCommandRunner();

        // --- Backup engines ---
        TarBackupEngine tarEngine = new TarBackupEngine(config.backupDir(), commandRunner);
        BorgBackupEngine borgEngine = new BorgBackupEngine(commandRunner);
        BackupEngineFactory engineFactory = new BackupEngineFactory(
                Map.of(BackupMode.TAR, tarEngine, BackupMode.BORG, borgEngine)
        );

        // --- Run tracking ---
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        LogBuffer logBuffer = new LogBuffer();

        // --- Upload ---
        UploaderFactory uploaderFactory = new UploaderFactory();

        // --- Job runner (async execution) ---
        JobRunner jobRunner = new JobRunner(engineFactory, dockerGateway,
                runRepository, logBuffer, uploaderFactory);

        // --- Cron scheduler ---
        JobScheduler scheduler = new JobScheduler(jobConfigStore, jobRunner);
        scheduler.start();

        // --- HTTP API ---
        ApiServer apiServer = new ApiServer(config.port(), jobConfigStore,
                jobRunner, runRepository, logBuffer);
        apiServer.start();

        // --- Graceful shutdown ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Main] Shutting down...");
            apiServer.stop();
            scheduler.shutdown();
            jobRunner.shutdown();
        }, "shutdown-hook"));

        System.out.println("[Main] DockSnap running. API on :" + config.port() + ", jobs: " + config.jobsFile());
    }
}
