package org.docksnap.upload;

import org.docksnap.domain.Job;
import org.docksnap.run.RunLogger;

import java.nio.file.Path;

public interface Uploader {
    void upload(Job job, String runId, RunLogger log, Path artifact) throws Exception;
}
