package org.docksnap.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

public class LogBuffer {
    private final Map<String, Deque<String>> tails = new HashMap<>();
    private final Path logDir;

    public LogBuffer(Path logDir) {
        this.logDir = logDir;
        if (logDir != null) {
            try { Files.createDirectories(logDir); } catch (IOException ignored) {}
        }
    }

    public synchronized void append(String runId, String level, String msg) {
        String line = Instant.now() + " [" + level + "] " + msg;
        var q = tails.computeIfAbsent(runId, _ -> new ArrayDeque<>());
        q.addLast(line);
        while (q.size() > 2000) q.removeFirst();
        if (logDir != null) {
            try {
                Files.writeString(logDir.resolve(runId + ".log"), line + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }

    public synchronized List<String> tail(String runId, int max) {
        var q = tails.getOrDefault(runId, new ArrayDeque<>());
        int skip = Math.max(0, q.size() - max);
        List<String> out = new ArrayList<>();
        int i = 0;
        for (String s : q) if (i++ >= skip) out.add(s);
        return out;
    }
}
