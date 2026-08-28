package dev.hindsight.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of the project, end to end: an exception escapes a request, and what is left
 * behind is enough to find where the bad value came from rather than only where it landed.
 */
class DumpOnExceptionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ForkedJvm.Result runFailing(Path traces, String... extraProperties)
            throws IOException, InterruptedException {
        return ForkedJvm.runWithAgent(
                Stream.concat(
                        Stream.of("-Dhindsight.packages=sample.testapp",
                                "-Dhindsight.trace.dir=" + traces),
                        Stream.of(extraProperties)).toList(),
                List.of("fail"));
    }

    private static List<Path> tracesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
        }
    }

    @Test
    @DisplayName("an escaping exception leaves a trace behind")
    void writesATraceWhenAnExceptionEscapes(@TempDir Path traces) throws Exception {
        ForkedJvm.Result result = runFailing(traces);

        assertNotEquals(0, result.exitCode(), "the application was supposed to fail:\n" + result);
        assertTrue(result.stdout().contains("trace written to"), result.toString());

        List<Path> written = tracesIn(traces);
        assertEquals(1, written.size(), "expected exactly one trace, found " + written);
    }

    @Test
    @DisplayName("the trace names the value that caused the failure, one frame above where it landed")
    void theTraceShowsWhereTheBadValueCameFrom() throws Exception {
        Path traces = Files.createTempDirectory("hindsight-it");
        try {
            runFailing(traces);
            JsonNode trace = JSON.readTree(Files.readString(tracesIn(traces).getFirst()));

            assertEquals("hindsight.trace/1", trace.get("schema").asText());
            assertEquals("main", trace.get("thread").asText());
            assertEquals("sample.testapp.TestApp", trace.get("entryPoint").get("type").asText());
            assertEquals("main", trace.get("entryPoint").get("method").asText());

            JsonNode events = trace.get("events");

            // The stack trace says the failure happened in normalise. The recording says greet was
            // entered with a null, one frame earlier, which is the thing worth knowing.
            JsonNode enteredGreet = find(events, "enter", "greet");
            assertEquals("null", enteredGreet.get("arguments").asText(),
                    "the argument that caused the failure is not in the trace");
            assertEquals(1, enteredGreet.get("depth").asInt());

            JsonNode threwInNormalise = find(events, "throw", "normalise");
            assertEquals(2, threwInNormalise.get("depth").asInt());
            assertTrue(threwInNormalise.get("thrown").asText().startsWith("NullPointerException"),
                    threwInNormalise.toString());

            // And the exception is recorded unwinding all the way back out of the entry point.
            JsonNode last = events.get(events.size() - 1);
            assertEquals("throw", last.get("kind").asText());
            assertEquals("main", last.get("method").asText());
            assertEquals(0, last.get("depth").asInt());
        } finally {
            for (Path file : tracesIn(traces)) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(traces);
        }
    }

    @Test
    @DisplayName("a request that succeeds leaves nothing behind")
    void writesNothingWhenNothingFails(@TempDir Path traces) throws Exception {
        ForkedJvm.Result result = ForkedJvm.runWithAgent(
                List.of("-Dhindsight.packages=sample.testapp", "-Dhindsight.trace.dir=" + traces),
                List.of());

        assertEquals(0, result.exitCode(), result.toString());
        assertEquals(List.of(), tracesIn(traces),
                "a successful run should not be writing files anywhere");
    }

    @Test
    @DisplayName("tracing can be switched off entirely, and then it is off")
    void respectsBeingTurnedOff(@TempDir Path traces) throws Exception {
        ForkedJvm.Result result = runFailing(traces, "-Dhindsight.trace.max=0");

        assertNotEquals(0, result.exitCode(), result.toString());
        assertEquals(List.of(), tracesIn(traces));
        assertTrue(result.stdout().contains("traces=off"), result.toString());
    }

    private static JsonNode find(JsonNode events, String kind, String method) {
        for (JsonNode event : events) {
            if (kind.equals(event.get("kind").asText()) && method.equals(event.get("method").asText())) {
                return event;
            }
        }
        throw new AssertionError("no " + kind + " event for " + method + " in " + events.toPrettyString());
    }
}
