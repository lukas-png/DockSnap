package org.docksnap.backup;


import org.docksnap.domain.BackupResult;
import org.docksnap.domain.Job;
import org.docksnap.run.RunLogger;

public interface BackupEngine {
    BackupResult backup(Job job, String runId, RunLogger log) throws Exception;
}
