package org.docksnap.upload;


import org.docksnap.domain.Job;

public class UploaderFactory {
    private final Uploader noop = new NoopUploader();

    public Uploader forJob(Job job) {
        if (job.upload() == null || job.upload().type() == null) return noop;
        String t = job.upload().type().toLowerCase();
        return switch (t) {
            case "none", "noop" -> noop;
            default -> noop;
        };
    }
}
