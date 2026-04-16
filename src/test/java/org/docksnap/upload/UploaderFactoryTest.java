package org.docksnap.upload;

import org.docksnap.domain.BackupMode;
import org.docksnap.domain.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UploaderFactoryTest {

    private final UploaderFactory factory = new UploaderFactory();

    private Job jobWithUpload(String type, String uri) {
        Job.UploadTarget upload = (type == null) ? null : new Job.UploadTarget(type, uri);
        return new Job("j1", "Job", BackupMode.TAR, List.of(), List.of(), List.of(),
                "prefix", null, upload, null);
    }

    @Test
    void forJob_nullUpload_returnsNoop() {
        Uploader u = factory.forJob(jobWithUpload(null, null));
        assertInstanceOf(NoopUploader.class, u);
    }

    @Test
    void forJob_typeNone_returnsNoop() {
        Uploader u = factory.forJob(jobWithUpload("none", ""));
        assertInstanceOf(NoopUploader.class, u);
    }

    @Test
    void forJob_typeNoop_returnsNoop() {
        Uploader u = factory.forJob(jobWithUpload("noop", ""));
        assertInstanceOf(NoopUploader.class, u);
    }

    @Test
    void forJob_unknownType_returnsNoop() {
        Uploader u = factory.forJob(jobWithUpload("s3", "s3://bucket/path"));
        assertInstanceOf(NoopUploader.class, u);
    }

    @Test
    void forJob_caseInsensitiveType() {
        Uploader u = factory.forJob(jobWithUpload("NONE", ""));
        assertInstanceOf(NoopUploader.class, u);
    }
}