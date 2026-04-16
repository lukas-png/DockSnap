package org.docksnap.proc;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(CommandSpec spec, LineSink sink) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(spec.argv());
        pb.redirectErrorStream(true);
        pb.environment().putAll(spec.env());

        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sink.onLine(line);
        }
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Command failed (" + code + "): " + String.join(" ", spec.argv()));
        return new CommandResult(code);
    }
}
