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

    /** The application with the agent attached and whatever configuration the test needs. */
    static Result runWithAgent(String... systemProperties) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of(systemProperties));
        arguments.add("-javaagent:" + PackagedAgent.agentJar());
        arguments.add("-jar");
        arguments.add(PackagedAgent.applicationJar().toString());
        return run(arguments.toArray(new String[0]));
    }

    /** The application traced, dumping its call tree when the outermost frame returns. */
    static Result runTraced(String... extraProperties) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dhindsight.packages=sample.testapp");
        arguments.add("-Dhindsight.dump=true");
        arguments.addAll(List.of(extraProperties));
        return runWithAgent(arguments.toArray(new String[0]));
    }

    /** The same application with no agent, for comparison. */
    static Result runBareApplication() throws IOException, InterruptedException {
        return run("-jar", PackagedAgent.applicationJar().toString());
    }

    record Result(int exitCode, String stdout, String stderr) {

        /** The recorded events only, stripped of the log prefix and the leading timestamp. */
        List<String> traceLines() {
            return stdout.lines()
                    .filter(line -> line.contains("-> ") || line.contains("<- ") || line.contains("<! "))
                    .map(line -> line.replaceFirst("^\\[hindsight] \\+ *[0-9.]+ms ", ""))
                    .toList();
        }

        @Override
        public String toString() {
            return "exit=" + exitCode + "\n--- stdout ---\n" + stdout + "--- stderr ---\n" + stderr;
        }
    }
}
