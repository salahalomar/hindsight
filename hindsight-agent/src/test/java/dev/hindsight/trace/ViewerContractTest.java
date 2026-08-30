package dev.hindsight.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hindsight.runtime.EventKind;
import dev.hindsight.runtime.RingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writer and the viewer are the two halves of one format, and they are written in different
 * languages in different directories, so nothing but a test connects them.
 *
 * <p>The field names here are not typed out. They are read back off a document the serialiser
 * actually produced, so renaming a field in Java fails this test until the viewer is updated too.
 */
class ViewerContractTest {

    private static final String APP = "sample.testapp.TestApp";

    private static String viewer() {
        String configured = System.getProperty("hindsight.viewer.html");
        if (configured == null) {
            throw new IllegalStateException(
                    "hindsight.viewer.html is not set; these tests are driven by surefire, run ./mvnw verify");
        }
        try {
            return Files.readString(Path.of(configured));
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read the viewer at " + configured, unreadable);
        }
    }

    /** A trace exercising every kind and every field the format defines. */
    private static JsonNode sampleTrace() throws IOException {
        RingBuffer buffer = new RingBuffer(4, 2);
        buffer.recordEnter(APP, "main", "String[1]@1a2b");
        buffer.recordEnter(APP, "greet", "null");
        buffer.recordEnter(APP, "deep", "");
        buffer.recordReturn(APP, "greet", "String \"hello\"");
        buffer.recordThrow(APP, "main", "NullPointerException");
        TraceHeader header = new TraceHeader("0.1.0", "main", APP, "main", Instant.now());
        return new ObjectMapper().readTree(TraceSerialiser.serialise(header, buffer));
    }

    private static void collectFieldNames(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                into.add(name);
                collectFieldNames(node.get(name), into);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, into));
        }
    }

    @Test
    @DisplayName("the viewer reads the exact schema this project writes")
    void agreesOnTheSchemaVersion() {
        assertTrue(viewer().contains(TraceSerialiser.SCHEMA),
                "the viewer does not mention " + TraceSerialiser.SCHEMA
                        + ", so it would refuse every trace this agent produces");
    }

    @Test
    @DisplayName("every field the serialiser emits is something the viewer knows about")
    void knowsEveryField() throws IOException {
        Set<String> emitted = new TreeSet<>();
        collectFieldNames(sampleTrace(), emitted);

        String viewer = viewer();
        Set<String> unknown = new TreeSet<>();
        for (String field : emitted) {
            if (!viewer.contains(field)) {
                unknown.add(field);
            }
        }

        assertTrue(unknown.isEmpty(),
                "the trace format carries fields the viewer never reads: " + unknown
                        + ". Either the viewer needs updating or the field should not be emitted.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventKind.class)
    @DisplayName("every event kind is one the viewer can render")
    void handlesEveryKind(EventKind kind) {
        assertTrue(viewer().contains("\"" + kind.name().toLowerCase(Locale.ROOT) + "\""),
                "the viewer does not handle the " + kind + " event kind");
    }

    @Test
    @DisplayName("the viewer says what it is, because a recording is not a replay")
    void statesThePremise() {
        String viewer = viewer().toLowerCase(Locale.ROOT);

        // Not decoration. Somebody stepping backwards through a call tree will assume they are
        // undoing execution unless the interface tells them otherwise.
        assertTrue(viewer.contains("recording, not a replay"), "the viewer does not state the premise");
        assertTrue(viewer.contains("re-executed") || viewer.contains("re-execute"),
                "the viewer does not say that nothing is re-executed");
    }

    @DisplayName("the viewer loads nothing from anywhere")
    @ParameterizedTest(name = "no {0}")
    @ValueSource(strings = {"://", "@import", "<link", "<script src", "XMLHttpRequest", "fetch("})
    void isSelfContained(String forbidden) {
        // It has to open from a file:// URL on a laptop with no network and no server. One stray
        // stylesheet reference and it silently renders as unstyled text at exactly the wrong moment.
        assertFalse(viewer().contains(forbidden),
                "the viewer references something external: " + forbidden);
    }

    @Test
    @DisplayName("the sample the viewer ships is a trace this agent could have written")
    void theEmbeddedSampleIsStillValid() throws IOException {
        // The one-click sample is a real recording pasted into the page, so nothing regenerates it
        // when the format changes. Without this it would quietly become a trace the viewer refuses.
        String viewer = viewer();
        int start = viewer.indexOf("id=\"sample-trace\">");
        assertTrue(start > 0, "the viewer no longer ships a sample trace");
        start = viewer.indexOf('>', start) + 1;
        String sample = viewer.substring(start, viewer.indexOf("</script>", start));

        JsonNode parsed = new ObjectMapper().readTree(sample);
        assertEquals(TraceSerialiser.SCHEMA, parsed.get("schema").asText(),
                "the embedded sample is from an older schema and the viewer would refuse it");

        Set<String> emitted = new TreeSet<>();
        collectFieldNames(sampleTrace(), emitted);
        Set<String> inSample = new TreeSet<>();
        collectFieldNames(parsed, inSample);
        assertTrue(inSample.containsAll(emitted),
                "the sample is missing fields the serialiser now emits: "
                        + new TreeSet<>(emitted) { { removeAll(inSample); } });
    }

    @Test
    @DisplayName("the viewer is one file, and stays small enough to send to somebody")
    void staysSelfContainedAndSmall() {
        assertTrue(viewer().length() < 64 * 1024,
                "the viewer has grown past 64KB; it is meant to be a single file you can attach to a ticket");
    }
}
