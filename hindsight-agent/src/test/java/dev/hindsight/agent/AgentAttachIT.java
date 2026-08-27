package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts a real JVM with {@code -javaagent} and reads what comes back. This is the only test in the
 * suite that exercises the premain handshake end to end.
 */
class AgentAttachIT {

    private static final Pattern SUMMARY = Pattern.compile(
            "\\[hindsight] (\\d+) class(?:es)? loaded since attach, (\\d+) excluded, (\\d+) candidate");

    @Test
    @DisplayName("the agent attaches and reports classes it could instrument")
    void attachesAndObservesClassLoading() throws Exception {
        Result result = run("-javaagent:" + PackagedAgent.agentJar(),
                "-jar", PackagedAgent.applicationJar().toString());

        assertEquals(0, result.exitCode, "the application exited abnormally:\n" + result);
        assertTrue(result.stdout.contains("[hindsight] agent loaded"), result.toString());

        Matcher summary = SUMMARY.matcher(result.stdout);
        assertTrue(summary.find(), "no summary line was printed:\n" + result);
        assertTrue(Long.parseLong(summary.group(3)) >= 1,
                "the transformer ran but the JVM never offered it an application class:\n" + result);
    }

    @Test
    @DisplayName("the application's own behaviour is untouched")
    void doesNotDisturbTheApplication() throws Exception {
        Result without = run("-jar", PackagedAgent.applicationJar().toString());
        Result with = run("-javaagent:" + PackagedAgent.agentJar(),
                "-jar", PackagedAgent.applicationJar().toString());

        assertFalse(without.stdout.contains("[hindsight]"),
                "the unattached run should know nothing about the agent");
        assertEquals(without.exitCode, with.exitCode);
        assertTrue(with.stdout.contains("hello from testapp"));
        assertTrue(with.stderr.isBlank(), "the agent wrote to stderr:\n" + with);
    }

    private static Result run(String... jvmArgs) throws IOException, InterruptedException {
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

    private record Result(int exitCode, String stdout, String stderr) {
        @Override
        public String toString() {
            return "exit=" + exitCode + "\n--- stdout ---\n" + stdout + "--- stderr ---\n" + stderr;
        }
    }
}
