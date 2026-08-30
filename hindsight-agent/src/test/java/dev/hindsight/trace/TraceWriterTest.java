package dev.hindsight.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hindsight.runtime.RingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceWriterTest {

    private final List<String> messages = new ArrayList<>();

    private static RingBuffer buffer() {
        RingBuffer buffer = new RingBuffer(16, 8);
        buffer.recordEnter("sample.testapp.TestApp", "main", "");
        buffer.recordThrow("sample.testapp.TestApp", "main", "IllegalStateException");
        return buffer;
    }

    private TraceWriter writerFor(Path directory, int maxFiles) {
        return new TraceWriter(directory, maxFiles, "0.1.0", messages::add);
    }

    @Test
    @DisplayName("a trace lands in the configured directory, as parseable JSON")
    void writesATrace(@TempDir Path directory) throws Exception {
        Path traces = directory.resolve("does-not-exist-yet");

        Path written = writerFor(traces, 10).write("main", "sample.testapp.TestApp", "main", buffer());

        assertNotNull(written);
        assertTrue(Files.exists(written));
        assertEquals("hindsight.trace/1",
                new ObjectMapper().readTree(Files.readString(written)).get("schema").asText());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().startsWith("trace written to "), messages.toString());
    }

    @Test
    @DisplayName("a thread name is arbitrary text, and it ends up in a path")
    void sanitisesThreadNames(@TempDir Path directory) {
        Path written = writerFor(directory, 10)
                .write("pool-1/thread 3:../etc", "sample.testapp.TestApp", "main", buffer());

        assertNotNull(written);
        String name = written.getFileName().toString();

        // The contract is that nothing in a thread name can escape the file name, not that it
        // transliterates to one exact string.
        assertTrue(name.matches("hindsight-\\d+-[A-Za-z0-9_-]+-1\\.json"), name);
        assertFalse(name.contains(".."), name);
        assertEquals(directory, written.getParent(), "the file escaped its directory: " + written);
    }

    @Test
    @DisplayName("an application failing in a loop cannot fill the disk")
    void stopsAtTheCap(@TempDir Path directory) {
        TraceWriter writer = writerFor(directory, 2);

        assertNotNull(writer.write("main", "T", "m", buffer()));
        assertNotNull(writer.write("main", "T", "m", buffer()));
        assertNull(writer.write("main", "T", "m", buffer()));
        assertNull(writer.write("main", "T", "m", buffer()));

        assertEquals(3, messages.size(), messages.toString());
        assertTrue(messages.getLast().contains("reached 2 trace files"), messages.toString());
    }

    @Test
    @DisplayName("switching tracing off is silent, not a complaint on every failure")
    void zeroMeansOff(@TempDir Path directory) {
        TraceWriter writer = writerFor(directory, 0);

        assertNull(writer.write("main", "T", "m", buffer()));
        assertNull(writer.write("main", "T", "m", buffer()));

        assertTrue(messages.isEmpty(), messages.toString());
        assertEquals("off", writer.describe());
    }

    @Test
    @DisplayName("a hiccup is not a reason to stay blind for the rest of the process's life")
    void recoversFromATransientFailure(@TempDir Path directory) throws IOException {
        // A full disk that is later freed, or a directory rotated away underneath us: the write
        // fails, and then conditions change. Here a plain file stands where the directory should
        // be, and is then removed.
        Path traces = directory.resolve("traces");
        Files.createFile(traces);
        TraceWriter writer = writerFor(traces, 10);

        assertNull(writer.write("main", "T", "m", buffer()), "the obstruction should have stopped it");
        Files.delete(traces);

        assertNotNull(writer.write("main", "T", "m", buffer()),
                "the obstruction is gone, so the next failure should be recorded");
    }

    @Test
    @DisplayName("a run of failures is the configuration, not the weather, and it does give up")
    void givesUpAfterARunOfFailures(@TempDir Path directory) throws IOException {
        Path blocked = Files.createFile(directory.resolve("in-the-way"));
        TraceWriter writer = writerFor(blocked, 20);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertNull(writer.write("main", "T", "m", buffer()));
        }

        // Two warnings that it would try again, then one saying it has stopped, and nothing after.
        assertEquals(3, messages.size(), messages.toString());
        assertTrue(messages.getLast().contains("giving up"), messages.toString());
    }
}
