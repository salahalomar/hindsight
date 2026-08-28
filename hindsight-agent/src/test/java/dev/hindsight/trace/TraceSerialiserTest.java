package dev.hindsight.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hindsight.runtime.RingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trace file is this project's first compatibility commitment, so these tests read it the way a
 * viewer will: by parsing it. Comparing strings would only prove the writer emits the string
 * somebody expected, not that it emits JSON at all.
 */
class TraceSerialiserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String APP = "sample.testapp.TestApp";

    private static final TraceHeader HEADER = new TraceHeader(
            "0.1.0", "main", APP, "main", Instant.parse("2026-08-28T14:25:30.123Z"));

    private static JsonNode parse(RingBuffer buffer) throws Exception {
        return JSON.readTree(TraceSerialiser.serialise(HEADER, buffer));
    }

    private static RingBuffer failingCall() {
        RingBuffer buffer = new RingBuffer(64, 16);
        buffer.recordEnter(APP, "main", "String[1]@1a2b");
        buffer.recordEnter(APP, "greet", "null");
        buffer.recordThrow(APP, "greet", "NullPointerException: \"name is null\"");
        buffer.recordThrow(APP, "main", "NullPointerException: \"name is null\"");
        return buffer;
    }

    @Test
    @DisplayName("the document names its own schema, so a reader can refuse what it does not know")
    void declaresItsSchema() throws Exception {
        assertEquals("hindsight.trace/1", parse(failingCall()).get("schema").asText());
    }

    @Test
    @DisplayName("the header says who recorded it, when, and where it started")
    void header() throws Exception {
        JsonNode trace = parse(failingCall());

        assertEquals("0.1.0", trace.get("agent").asText());
        assertEquals("main", trace.get("thread").asText());
        assertEquals("2026-08-28T14:25:30.123Z", trace.get("recordedAt").asText());
        assertEquals(APP, trace.get("entryPoint").get("type").asText());
        assertEquals("main", trace.get("entryPoint").get("method").asText());
    }

    @Test
    @DisplayName("each event names its payload rather than hiding it behind one generic field")
    void eventsAreSelfDescribing() throws Exception {
        JsonNode events = parse(failingCall()).get("events");

        assertEquals(4, events.size());

        JsonNode entered = events.get(0);
        assertEquals(0, entered.get("seq").asInt());
        assertEquals("enter", entered.get("kind").asText());
        assertEquals(0, entered.get("depth").asInt());
        assertEquals(APP, entered.get("type").asText());
        assertEquals("main", entered.get("method").asText());
        assertEquals("String[1]@1a2b", entered.get("arguments").asText());
        assertFalse(entered.has("returned"));

        JsonNode threw = events.get(3);
        assertEquals("throw", threw.get("kind").asText());
        assertEquals("NullPointerException: \"name is null\"", threw.get("thrown").asText());
        assertFalse(threw.has("arguments"));
    }

    @Test
    @DisplayName("a returned value is named as returned")
    void returns() throws Exception {
        RingBuffer buffer = new RingBuffer(16, 8);
        buffer.recordEnter(APP, "greet", "String \"x\"");
        buffer.recordReturn(APP, "greet", "String \"hello\"");

        JsonNode events = parse(buffer).get("events");

        assertEquals("String \"hello\"", events.get(1).get("returned").asText());
    }

    @Test
    @DisplayName("times are offsets from the first event, since absolute nanoTime means nothing elsewhere")
    void timesAreRelative() throws Exception {
        JsonNode events = parse(failingCall()).get("events");

        assertEquals(0, events.get(0).get("offsetNanos").asLong());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).get("offsetNanos").asLong() >= 0,
                    "offsets must not run backwards");
        }
    }

    @Test
    @DisplayName("a truncated trace admits it in the document, not just on a console somewhere")
    void truncationIsRecorded() throws Exception {
        RingBuffer buffer = new RingBuffer(4, 64);
        for (int i = 0; i < 3; i++) {
            buffer.recordEnter(APP, "m" + i, "");
            buffer.recordReturn(APP, "m" + i, "void");
        }

        JsonNode truncation = parse(buffer).get("truncation");

        assertEquals(2, truncation.get("droppedToRing").asLong());
        assertEquals(0, truncation.get("beyondMaxDepth").asLong());
    }

    @Test
    @DisplayName("a summary containing quotes and backslashes survives the round trip exactly")
    void escaping() throws Exception {
        RingBuffer buffer = new RingBuffer(16, 8);
        // Exactly what the summariser produces for a string containing a newline: by the time it
        // reaches here the backslash and the n are two literal characters, and both have to survive.
        String hostile = "String \"say \\\"hi\\\"\\nand \\\\ that\"";
        buffer.recordEnter(APP, "greet", hostile);

        assertEquals(hostile, parse(buffer).get("events").get(0).get("arguments").asText());
    }

    @Test
    @DisplayName("an empty buffer still produces a readable document")
    void emptyTrace() throws Exception {
        JsonNode trace = parse(new RingBuffer(16, 8));

        assertTrue(trace.get("events").isArray());
        assertEquals(0, trace.get("events").size());
    }
}
