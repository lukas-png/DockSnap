package org.docksnap.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.docksnap.domain.BackupMode;
import org.docksnap.domain.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobConfigStoreTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper OM = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Path writeJson(String content) throws Exception {
        Path file = tempDir.resolve("jobs.json");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void list_returnsAllJobs() throws Exception {
        Path file = writeJson("""
                {"jobs": [
                  {"id":"j1","name":"Job 1","mode":"TAR","paths":["/data"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":"j1",
                   "borg":null,"upload":null,"schedule":null},
                  {"id":"j2","name":"Job 2","mode":"BORG","paths":["/etc"],
                   "stopContainers":["app"],"startContainers":["app"],"filenamePrefix":null,
                   "borg":{"repo":"user@host:/r","archivePrefix":"snap","compression":"lz4",
                           "sshKeyPath":null,"knownHostsPath":null},
                   "upload":null,"schedule":"0 1 * * *"}
                ]}""");

        JobConfigStore store = new JobConfigStore(file, OM);
        List<Job> jobs = store.list();

        assertEquals(2, jobs.size());
        assertEquals("j1", jobs.get(0).id());
        assertEquals(BackupMode.TAR, jobs.get(0).mode());
        assertEquals("j2", jobs.get(1).id());
        assertEquals(BackupMode.BORG, jobs.get(1).mode());
    }

    @Test
    void getById_returnsMatchingJob() throws Exception {
        Path file = writeJson("""
                {"jobs": [
                  {"id":"alpha","name":"Alpha","mode":"TAR","paths":["/a"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":"a",
                   "borg":null,"upload":null,"schedule":null}
                ]}""");

        JobConfigStore store = new JobConfigStore(file, OM);
        Job job = store.getById("alpha");

        assertNotNull(job);
        assertEquals("Alpha", job.name());
    }

    @Test
    void getById_unknownId_returnsNull() throws Exception {
        Path file = writeJson("""
                {"jobs": [
                  {"id":"j1","name":"J1","mode":"TAR","paths":["/data"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":"j1",
                   "borg":null,"upload":null,"schedule":null}
                ]}""");

        JobConfigStore store = new JobConfigStore(file, OM);
        assertNull(store.getById("does-not-exist"));
    }

    @Test
    void emptyJobsArray_returnsEmptyList() throws Exception {
        Path file = writeJson("{\"jobs\": []}");
        JobConfigStore store = new JobConfigStore(file, OM);
        assertTrue(store.list().isEmpty());
    }

    @Test
    void nullJobsField_returnsEmptyList() throws Exception {
        Path file = writeJson("{\"jobs\": null}");
        JobConfigStore store = new JobConfigStore(file, OM);
        assertTrue(store.list().isEmpty());
    }

    @Test
    void reload_picksUpChanges() throws Exception {
        Path file = writeJson("""
                {"jobs": [
                  {"id":"j1","name":"Old Name","mode":"TAR","paths":["/data"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":"j1",
                   "borg":null,"upload":null,"schedule":null}
                ]}""");

        JobConfigStore store = new JobConfigStore(file, OM);
        assertEquals("Old Name", store.getById("j1").name());

        Files.writeString(file, """
                {"jobs": [
                  {"id":"j1","name":"New Name","mode":"TAR","paths":["/data"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":"j1",
                   "borg":null,"upload":null,"schedule":null}
                ]}""");
        store.reload();

        assertEquals("New Name", store.getById("j1").name());
    }

    @Test
    void borgOptions_deserializedCorrectly() throws Exception {
        Path file = writeJson("""
                {"jobs": [
                  {"id":"borg-job","name":"Borg","mode":"BORG","paths":["/data"],
                   "stopContainers":[],"startContainers":[],"filenamePrefix":null,
                   "borg":{"repo":"user@host:/repo","archivePrefix":"snap",
                           "compression":"lz4","sshKeyPath":"/key","knownHostsPath":"/kh"},
                   "upload":null,"schedule":null}
                ]}""");

        JobConfigStore store = new JobConfigStore(file, OM);
        Job job = store.getById("borg-job");

        assertNotNull(job.borg());
        assertEquals("user@host:/repo", job.borg().repo());
        assertEquals("snap", job.borg().archivePrefix());
        assertEquals("lz4", job.borg().compression());
        assertEquals("/key", job.borg().sshKeyPath());
        assertEquals("/kh", job.borg().knownHostsPath());
    }
}