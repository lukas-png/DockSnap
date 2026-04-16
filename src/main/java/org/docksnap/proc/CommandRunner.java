package org.docksnap.proc;

public interface CommandRunner {
    CommandResult run(CommandSpec spec, LineSink sink) throws Exception;

    @FunctionalInterface
    interface LineSink {
        void onLine(String line);
    }
}
