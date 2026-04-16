package org.docksnap.run;

public interface RunLogger {
    void info(String runId, String msg);
    void error(String runId, String msg);
}
