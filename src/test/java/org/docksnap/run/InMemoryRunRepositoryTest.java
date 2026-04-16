package org.docksnap.run;

import org.docksnap.domain.BackupMode;
import org.docksnap.domain.Job;
import org.docksnap.domain.Run;
import org.docksnap.domain.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRunRepositoryTest {

    private InMemoryRunRepository repo;
    private Job job;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRunRepository();
        job = new Job("j1", "Job", BackupMode.TAR, List.of("/data"),
                List.of(), List.of(), "prefix", null, null, null);
    }

    @Test
    void saveAndFind() {
        Run run = Run.start(job, false);
        repo.save(run);
        assertEquals(run, repo.find(run.id()));
    }

    @Test
    void find_unknownId_returnsNull() {
        assertNull(repo.find("no-such-id"));
    }

    @Test
    void list_returnsNewestFirst() {
        Run r1 = Run.start(job, false);
        Run r2 = Run.start(job, false);
        repo.save(r1);
        repo.save(r2);

        List<Run> list = repo.list();
        assertEquals(2, list.size());
        assertEquals(r2.id(), list.get(0).id(), "newest run should be first");
        assertEquals(r1.id(), list.get(1).id());
    }

    @Test
    void update_replacesExistingRun() {
        Run running = Run.start(job, false);
        repo.save(running);

        Run done = running.success("/out.tar.gz", 512L);
        repo.update(done);

        Run found = repo.find(running.id());
        assertEquals(RunStatus.SUCCESS, found.status());
        assertEquals("/out.tar.gz", found.artifact());
    }

    @Test
    void save_capsAt50Entries() {
        for (int i = 0; i < 55; i++) {
            repo.save(Run.start(job, false));
        }
        assertEquals(50, repo.list().size());
    }

    @Test
    void save_evictsOldestWhenCapExceeded() {
        Run first = Run.start(job, false);
        repo.save(first);

        for (int i = 0; i < 50; i++) {
            repo.save(Run.start(job, false));
        }

        assertNull(repo.find(first.id()), "oldest run should have been evicted");
        assertEquals(50, repo.list().size());
    }
}