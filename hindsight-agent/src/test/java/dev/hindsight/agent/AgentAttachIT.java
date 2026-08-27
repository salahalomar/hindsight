package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        ForkedJvm.Result result = ForkedJvm.runWithAgent();

        assertEquals(0, result.exitCode(), "the application exited abnormally:\n" + result);
        assertTrue(result.stdout().contains("[hindsight] agent loaded"), result.toString());

        Matcher summary = SUMMARY.matcher(result.stdout());
        assertTrue(summary.find(), "no summary line was printed:\n" + result);
        assertTrue(Long.parseLong(summary.group(3)) >= 1,
                "the transformer ran but the JVM never offered it an application class:\n" + result);
    }

    @Test
    @DisplayName("the application's own behaviour is untouched")
    void doesNotDisturbTheApplication() throws Exception {
        ForkedJvm.Result without = ForkedJvm.runBareApplication();
        ForkedJvm.Result with = ForkedJvm.runWithAgent();

        assertFalse(without.stdout().contains("[hindsight]"),
                "the unattached run should know nothing about the agent");
        assertEquals(without.exitCode(), with.exitCode());
        assertTrue(with.stdout().contains("hello from testapp"));
    }

    @Test
    @DisplayName("attaching the agent writes nothing to the application's stderr")
    void staysOffStandardError() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runWithAgent();

        // Byte Buddy probes sun.misc.Unsafe while initialising its class injector, and on JDK 24+
        // that probe prints a terminal-deprecation warning. An application that merely attached an
        // agent should not start emitting warnings about the agent's dependencies.
        assertTrue(result.stderr().isBlank(), "the agent wrote to stderr:\n" + result);
    }
}
