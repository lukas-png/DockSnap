package org.docksnap.agent;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.docksnap.config.JobConfigStore;
import org.docksnap.domain.Job;

import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class JobScheduler {

    private final JobConfigStore configStore;
    private final JobRunner jobRunner;
    private final ScheduledExecutorService scheduler;
    private final CronParser parser;

    public JobScheduler(JobConfigStore configStore, JobRunner jobRunner) {
        this.configStore = configStore;
        this.jobRunner = jobRunner;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 5-field UNIX cron: "minute hour day-of-month month day-of-week"
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    }

    public void start() {
        // Fire every 60 seconds; first tick after 60 s so we don't trigger
        // jobs immediately on startup (they can be triggered manually via API).
        scheduler.scheduleAtFixedRate(this::tick, 60, 60, TimeUnit.SECONDS);
        System.out.println("[SCHEDULER] Started — polling every 60 s.");
    }

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now();
        for (Job job : configStore.list()) {
            String schedule = job.schedule();
            if (schedule == null || schedule.isBlank()) continue;
            try {
                Cron cron = parser.parse(schedule);
                cron.validate();
                if (ExecutionTime.forCron(cron).isMatch(now)) {
                    System.out.printf("[SCHEDULER] Triggering job '%s' (%s)%n", job.name(), job.id());
                    jobRunner.submit(job);
                }
            } catch (Exception e) {
                System.err.printf("[SCHEDULER] Invalid cron for job '%s': %s%n", job.name(), e.getMessage());
            }
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
