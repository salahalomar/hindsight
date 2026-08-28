package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that advice reaches real methods in a separately packaged application, running in its own
 * JVM, loaded by the application class loader.
 */
class MethodTracingIT {

    @Test
    @DisplayName("the whole call tree is recorded, nested and in order")
    void recordsTheCallTree() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced();

        assertEquals(0, result.exitCode(), result.toString());
        assertEquals(List.of(
                        "-> sample.testapp.TestApp.main(String[0]@x)",
                        "  -> sample.testapp.TestApp.greet(String \"testapp\")",
                        "    -> sample.testapp.Greeting.normalise()",
                        "    <- sample.testapp.Greeting.normalise returned String \"testapp\"",
                        "    -> sample.testapp.Greeting.render(String \"testapp\")",
                        "    <- sample.testapp.Greeting.render returned String \"hello from testapp\"",
                        "  <- sample.testapp.TestApp.greet returned String \"hello from testapp\"",
                        "<- sample.testapp.TestApp.main returned void"),
                result.traceLines(),
                "the recorded tree does not match the call the application actually made:\n" + result);
    }

    @Test
    @DisplayName("instrumented methods still do their job")
    void preservesApplicationBehaviour() throws Exception {
        ForkedJvm.Result traced = ForkedJvm.runTraced();
        ForkedJvm.Result bare = ForkedJvm.runBareApplication();

        // Advice is spliced into every one of these methods. Their return values must survive it.
        assertTrue(bare.stdout().contains("hello from testapp"));
        assertTrue(traced.stdout().contains("hello from testapp"),
                "instrumenting a method must not change what it returns:\n" + traced);
    }

    @Test
    @DisplayName("an agent nobody configured records nothing, and says so")
    void recordsNothingWhenUnconfigured() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runWithAgent();

        assertEquals(List.of(), result.traceLines(), result.toString());
        assertTrue(result.stdout().contains("no packages selected"),
                "an agent that silently does nothing is indistinguishable from a broken one:\n" + result);
        assertTrue(result.stdout().contains("hello from testapp"));
    }

    @Test
    @DisplayName("packages outside the scope are left alone")
    void recordsNothingOutsideTheScope() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runWithAgent(
                "-Dhindsight.packages=com.example.nothing.here", "-Dhindsight.dump=true");

        assertEquals(List.of(), result.traceLines(), result.toString());
        assertTrue(result.stdout().contains("hello from testapp"));
    }

    @Test
    @DisplayName("the exclusion list outranks anything the user asks for")
    void exclusionsBeatTheConfiguredScope() throws Exception {
        // Asking for the platform is either a mistake or an experiment. Either way, instrumenting
        // java.lang from inside the recorder would not survive the first event.
        ForkedJvm.Result result = ForkedJvm.runWithAgent(
                "-Dhindsight.packages=java,jdk,sample.testapp", "-Dhindsight.dump=true");

        assertEquals(0, result.exitCode(), "the JVM did not survive being asked to trace itself:\n" + result);
        assertTrue(result.stdout().contains("hello from testapp"), result.toString());
        assertTrue(result.traceLines().stream().noneMatch(line -> line.contains(" java.") || line.contains(" jdk.")),
                "a platform class was instrumented:\n" + result);
        assertTrue(result.traceLines().stream().anyMatch(line -> line.contains("sample.testapp")),
                "the legitimate half of the scope should still have been recorded:\n" + result);
    }

    @Test
    @DisplayName("frames past the depth limit are dropped in pairs, leaving the tree balanced")
    void honoursTheDepthLimit() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced("-Dhindsight.depth.max=2");

        assertEquals(List.of(
                        "-> sample.testapp.TestApp.main(String[0]@x)",
                        "  -> sample.testapp.TestApp.greet(String \"testapp\")",
                        "  <- sample.testapp.TestApp.greet returned String \"hello from testapp\"",
                        "<- sample.testapp.TestApp.main returned void"),
                result.traceLines(),
                "the depth limit should remove whole frames, not half of them:\n" + result);
        assertTrue(result.stdout().contains("beyond max depth"),
                "a truncated trace has to admit it:\n" + result);
    }

    @Test
    @DisplayName("recording stays off standard error")
    void staysOffStandardError() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced();

        assertTrue(result.stderr().isBlank(), "the agent wrote to stderr:\n" + result);
    }
}
