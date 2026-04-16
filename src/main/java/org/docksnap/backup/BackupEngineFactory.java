package org.docksnap.backup;

import org.docksnap.domain.BackupMode;

import java.util.Map;

public class BackupEngineFactory {
    private final Map<BackupMode, BackupEngine> engines;

    public BackupEngineFactory(Map<BackupMode, BackupEngine> engines) {
        this.engines = engines;
    }

    public BackupEngine forMode(BackupMode mode) {
        var e = engines.get(mode);
        if (e == null) throw new IllegalArgumentException("No engine for mode " + mode);
        return e;
    }
}
