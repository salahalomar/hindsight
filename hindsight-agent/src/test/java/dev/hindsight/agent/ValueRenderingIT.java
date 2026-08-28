package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summariser is covered exhaustively by its own unit tests. What these prove is the part unit
 * tests cannot: that it survives being reached through inlined advice, from application code, in a
 * JVM that has to keep running afterwards.
 */
class ValueRenderingIT {

    private static final List<String> HOSTILE_RUN = List.of("hostile");

    @Test
    @DisplayName("an argument whose toString throws costs a note in the trace, nothing more")
    void hostileArgumentDoesNotReachTheApplication() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced(HOSTILE_RUN);

        assertEquals(0, result.exitCode(),
                "an object refusing to be printed took the application down:\n" + result);
        assertTrue(result.stdout().contains("described one object"),
                "the instrumented method did not complete:\n" + result);
        assertTrue(result.stderr().isBlank(), "the agent wrote to stderr:\n" + result);

        assertTrue(result.traceLines().stream().anyMatch(line -> line.equals(
                        "  -> sample.testapp.TestApp.describe(Unprintable@x <toString threw IllegalStateException>)")),
                "a broken toString is worth reporting, and worth reporting as broken:\n" + result);
    }

    @Test
    @DisplayName("type mode never calls into the application at all")
    void typeModeAsksTheObjectNothing() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced(HOSTILE_RUN, "-Dhindsight.values=type");

        assertEquals(0, result.exitCode(), result.toString());
        assertTrue(result.traceLines().stream().anyMatch(
                        line -> line.equals("  -> sample.testapp.TestApp.describe(Unprintable)")),
                "type mode should have reported the type and asked nothing else:\n" + result);
        assertTrue(result.traceLines().stream().noneMatch(line -> line.contains("threw")),
                "nothing should have been called, so nothing should have thrown:\n" + result);
    }

    @Test
    @DisplayName("type mode keeps values out of the output entirely")
    void typeModeLeaksNoValues() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced(List.of("s3cret-argument"),
                "-Dhindsight.values=type");

        assertTrue(result.traceLines().stream().anyMatch(line -> line.contains("greet(String)")),
                result.toString());
        assertFalse(String.join("\n", result.traceLines()).contains("s3cret"),
                "an application that turned value rendering off had a value recorded anyway:\n" + result);
    }

    @Test
    @DisplayName("a long value is cut, and says how long it really was")
    void longValuesAreBounded() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced(List.of("x".repeat(300)));

        assertTrue(result.traceLines().stream().anyMatch(
                        line -> line.contains("returned String (311) \"hello from " + "x".repeat(53) + "...\"")),
                "the returned greeting should have been cut at the default cap:\n" + result);
    }

    @Test
    @DisplayName("the cap is configurable, because 64 characters is a guess about someone else's data")
    void valueLengthIsConfigurable() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runTraced(List.of("abcdefghijklmnop"),
                "-Dhindsight.value.length=8");

        assertTrue(result.traceLines().stream().anyMatch(
                        line -> line.contains("greet(String (16) \"abcdefgh...\")")),
                result.toString());
    }
}
