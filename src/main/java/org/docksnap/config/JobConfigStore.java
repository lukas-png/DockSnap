package org.docksnap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.docksnap.domain.Job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class JobConfigStore {
    private final Path file;
    private final ObjectMapper om;
    private volatile List<Job> cache = List.of();

    public JobConfigStore(Path file, ObjectMapper om) throws Exception {
        this.file = file;
        this.om = om;
        reload();
    }

    public synchronized void reload() throws Exception {
        var bytes = Files.readAllBytes(file);
        var wrapper = om.readValue(bytes, JobsWrapper.class);
        cache = (wrapper.jobs == null) ? List.of() : wrapper.jobs;
    }

    public List<Job> list() { return cache; }

    public Job getById(String id) {
        for (Job j : cache) if (j.id().equals(id)) return j;
        return null;
    }

    public static class JobsWrapper {
        public List<Job> jobs;
    }
}
