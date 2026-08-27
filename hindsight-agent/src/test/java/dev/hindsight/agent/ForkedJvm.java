package dev.hindsight.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a JVM and collects what it said. */
final class ForkedJvm {

    private ForkedJvm() {
    }

    static Result run(String... jvmArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(PackagedAgent.javaExecutable().toString());
        command.addAll(List.of(jvmArgs));

        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the forked JVM did not exit: " + command);
        }
        return new Result(process.exitValue(), stdout, stderr);
    }

    /** The application under the agent, with the agent attached. */
    static Result runInstrumentedApplication() throws IOException, InterruptedException {
        return run("-javaagent:" + PackagedAgent.agentJar(),
                "-jar", PackagedAgent.applicationJar().toString());
    }

    /** The same application with no agent, for comparison. */
    static Result runBareApplication() throws IOException, InterruptedException {
        return run("-jar", PackagedAgent.applicationJar().toString());
    }

    record Result(int exitCode, String stdout, String stderr) {

        List<String> traceLines() {
            return stdout.lines()
                    .filter(line -> line.contains(" -> ") || line.contains(" <- "))
                    .toList();
        }

        @Override
        public String toString() {
            return "exit=" + exitCode + "\n--- stdout ---\n" + stdout + "--- stderr ---\n" + stderr;
        }
    }
}
