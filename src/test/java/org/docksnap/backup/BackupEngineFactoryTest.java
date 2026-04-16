package org.docksnap.backup;

import org.docksnap.domain.BackupMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupEngineFactoryTest {

    @Test
    void forMode_returnsCorrectEngine() {
        BackupEngine tarEngine = (job, runId, log) -> null;
        BackupEngine borgEngine = (job, runId, log) -> null;

        BackupEngineFactory factory = new BackupEngineFactory(
                Map.of(BackupMode.TAR, tarEngine, BackupMode.BORG, borgEngine)
        );

        assertSame(tarEngine, factory.forMode(BackupMode.TAR));
        assertSame(borgEngine, factory.forMode(BackupMode.BORG));
    }

    @Test
    void forMode_unknownMode_throwsIllegalArgument() {
        BackupEngineFactory factory = new BackupEngineFactory(Map.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> factory.forMode(BackupMode.TAR)
        );
        assertTrue(ex.getMessage().contains("TAR"));
    }
}