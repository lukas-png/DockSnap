package org.docksnap.upload;

import org.docksnap.domain.Job;
import org.docksnap.run.RunLogger;

import java.nio.file.Path;

public class NoopUploader implements Uploader {
    @Override
    public void upload(Job job, String runId, RunLogger log, Path artifact) {
        log.info(runId, "Upload: skipped (noop).");
    }
}
