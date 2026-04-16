package org.docksnap.proc;

import java.util.List;
import java.util.Map;

public record CommandSpec(List<String> argv, Map<String, String> env) {
    public static CommandSpec of(List<String> argv) {
        return new CommandSpec(argv, Map.of());
    }
}
