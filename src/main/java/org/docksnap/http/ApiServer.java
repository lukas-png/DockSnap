package org.docksnap.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.docksnap.agent.JobRunner;
import org.docksnap.backup.BorgBackupEngine;
import org.docksnap.config.JobConfigStore;
import org.docksnap.domain.BackupMode;
import org.docksnap.domain.Job;
import org.docksnap.domain.Run;
import org.docksnap.run.LogBuffer;
import org.docksnap.run.RunRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

public class ApiServer {

    private final int port;
    private final JobConfigStore configStore;
    private final JobRunner jobRunner;
    private final RunRepository runs;
    private final LogBuffer logs;
    private final BorgBackupEngine borgEngine;
    private Javalin app;

    public ApiServer(int port,
                     JobConfigStore configStore,
                     JobRunner jobRunner,
                     RunRepository runs,
                     LogBuffer logs,
                     BorgBackupEngine borgEngine) {
        this.port = port;
        this.configStore = configStore;
        this.jobRunner = jobRunner;
        this.runs = runs;
        this.logs = logs;
        this.borgEngine = borgEngine;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson().updateMapper(m -> {
                m.registerModule(new JavaTimeModule());
                m.registerModule(new ParameterNamesModule());
                m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));
            config.bundledPlugins.enableCors(cors ->
                cors.addRule(rule -> rule.anyHost())
            );
        });

        app.get("/health", ctx ->
            ctx.json(Map.of("status", "ok"))
        );

        app.get("/jobs", ctx ->
            ctx.json(configStore.list())
        );

        app.post("/jobs/{id}/run", ctx -> {
            String id = ctx.pathParam("id");
            Job job = configStore.getById(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Job not found: " + id));
                return;
            }
            Run run = jobRunner.submit(job);
            ctx.status(202).json(run);
        });

        app.post("/jobs/{id}/test", ctx -> {
            String id = ctx.pathParam("id");
            Job job = configStore.getById(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Job not found: " + id));
                return;
            }
            if (job.mode() != BackupMode.BORG) {
                ctx.status(400).json(Map.of("error", "Test only supported for BORG jobs"));
                return;
            }
            try {
                List<String> output = borgEngine.testRepo(job);
                ctx.json(Map.of("ok", true, "output", output));
            } catch (Exception e) {
                ctx.status(502).json(Map.of("ok", false, "error", e.getMessage()));
            }
        });

        app.get("/jobs/{id}/archives", ctx -> {
            String id = ctx.pathParam("id");
            Job job = configStore.getById(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Job not found: " + id));
                return;
            }
            if (job.mode() != BackupMode.BORG) {
                ctx.status(400).json(Map.of("error", "Archives only available for BORG jobs"));
                return;
            }
            try {
                ctx.json(borgEngine.listArchives(job));
            } catch (Exception e) {
                ctx.status(502).json(Map.of("error", e.getMessage()));
            }
        });

        app.post("/jobs/{id}/keyscan", ctx -> {
            String id = ctx.pathParam("id");
            Job job = configStore.getById(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Job not found: " + id));
                return;
            }
            if (job.mode() != BackupMode.BORG) {
                ctx.status(400).json(Map.of("error", "keyscan only supported for BORG jobs"));
                return;
            }
            if (job.borg().knownHostsPath() == null || job.borg().knownHostsPath().isBlank()) {
                ctx.status(400).json(Map.of("error", "knownHostsPath not configured for this job"));
                return;
            }
            try {
                List<String> lines = borgEngine.keyscan(job);
                Path kh = Path.of(job.borg().knownHostsPath());
                Files.createDirectories(kh.getParent());
                Files.write(kh, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                ctx.json(Map.of("ok", true, "lines", lines, "path", kh.toString()));
            } catch (Exception e) {
                ctx.status(502).json(Map.of("ok", false, "error", e.getMessage()));
            }
        });

        app.get("/runs", ctx ->
            ctx.json(runs.list())
        );

        app.get("/runs/{id}", ctx -> {
            String id = ctx.pathParam("id");
            Run run = runs.find(id);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + id));
                return;
            }
            ctx.json(run);
        });

        app.get("/runs/{id}/logs", ctx -> {
            String id = ctx.pathParam("id");
            int last = ctx.queryParamAsClass("last", Integer.class).getOrDefault(Integer.valueOf(100));
            List<String> lines = logs.tail(id, last);
            ctx.json(Map.of("runId", id, "lines", lines));
        });

        app.start(port);
        System.out.println("[API] Javalin started on port " + port);
    }

    public void stop() {
        if (app != null) app.stop();
    }
}
