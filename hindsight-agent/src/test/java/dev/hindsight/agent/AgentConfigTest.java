package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import dev.hindsight.runtime.ValueDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    private final List<String> warnings = new ArrayList<>();

    private AgentConfig configure(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return AgentConfig.from(properties, warnings::add);
    }

    @Test
    @DisplayName("an unconfigured agent records nothing and complains about nothing")
    void defaults() {
        AgentConfig config = configure();

        assertTrue(config.scope().isEmpty());
        assertEquals(AgentConfig.DEFAULT_BUFFER_EVENTS, config.bufferEvents());
        assertEquals(AgentConfig.DEFAULT_MAX_DEPTH, config.maxDepth());
        assertFalse(config.dump());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("buffer size rounds up to a power of two, so the caller gets at least what they asked for")
    void roundsBufferUp() {
        assertEquals(2048, configure(AgentConfig.BUFFER_EVENTS, "1500").bufferEvents());
        assertEquals(64, configure(AgentConfig.BUFFER_EVENTS, "64").bufferEvents());
        assertEquals(32, configure(AgentConfig.BUFFER_EVENTS, "17").bufferEvents());
    }

    @Test
    @DisplayName("a mistyped number is reported and replaced, never fatal")
    void survivesGarbage() {
        AgentConfig config = configure(AgentConfig.BUFFER_EVENTS, "lots");

        assertEquals(AgentConfig.DEFAULT_BUFFER_EVENTS, config.bufferEvents());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("is not a number"), warnings.toString());
    }

    @Test
    @DisplayName("an out of range size is clamped and reported")
    void clampsOutOfRange() {
        assertEquals(AgentConfig.MIN_BUFFER_EVENTS, configure(AgentConfig.BUFFER_EVENTS, "1").bufferEvents());
        assertEquals(1, warnings.size());

        warnings.clear();
        assertEquals(AgentConfig.MAX_BUFFER_EVENTS,
                configure(AgentConfig.BUFFER_EVENTS, String.valueOf(Integer.MAX_VALUE)).bufferEvents());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("refusing to start over a mistyped flag would take down a working application")
    void badConfigurationNeverThrows() {
        AgentConfig config = configure(
                AgentConfig.BUFFER_EVENTS, "??",
                AgentConfig.MAX_DEPTH, "-4",
                AgentConfig.DUMP, "perhaps");

        assertEquals(AgentConfig.DEFAULT_BUFFER_EVENTS, config.bufferEvents());
        assertEquals(1, config.maxDepth());
        assertFalse(config.dump(), "anything that is not \"true\" is not true");
        assertEquals(2, warnings.size());
    }

    @Test
    @DisplayName("value rendering can be turned down, and an unknown mode is reported")
    void valueDetail() {
        assertEquals(ValueDetail.SUMMARY, configure().valueDetail());
        assertEquals(ValueDetail.TYPE, configure(AgentConfig.VALUES, "type").valueDetail());
        assertEquals(ValueDetail.TYPE, configure(AgentConfig.VALUES, "  TYPE ").valueDetail());
        assertTrue(warnings.isEmpty());

        assertEquals(ValueDetail.SUMMARY, configure(AgentConfig.VALUES, "verbose").valueDetail());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("not one of summary or type"), warnings.toString());
    }

    @Test
    @DisplayName("the value length cap is clamped rather than trusted")
    void valueLength() {
        assertEquals(AgentConfig.DEFAULT_VALUE_LENGTH, configure().valueLength());
        assertEquals(200, configure(AgentConfig.VALUE_LENGTH, "200").valueLength());
        assertEquals(AgentConfig.MIN_VALUE_LENGTH, configure(AgentConfig.VALUE_LENGTH, "0").valueLength());
        assertEquals(AgentConfig.MAX_VALUE_LENGTH,
                configure(AgentConfig.VALUE_LENGTH, "999999").valueLength());
    }

    @Test
    @DisplayName("the trace directory is configurable, and zero files means off")
    void traceSettings() {
        assertEquals(java.nio.file.Path.of("hindsight-traces"), configure().traceDirectory());
        assertEquals(AgentConfig.DEFAULT_TRACE_MAX, configure().traceMax());

        AgentConfig configured = configure(AgentConfig.TRACE_DIR, "/tmp/traces",
                AgentConfig.TRACE_MAX, "0");
        assertEquals(java.nio.file.Path.of("/tmp/traces"), configured.traceDirectory());
        assertEquals(0, configured.traceMax());
        assertTrue(configured.describe().contains("traces=off"), configured.describe());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("the depth limit is bounded like every other setting")
    void boundsTheDepthLimit() {
        // Left unbounded, a mistyped value made the renderer build a multi-megabyte indent for a
        // single line. Every other setting here had a ceiling; this one was the exception.
        assertEquals(AgentConfig.MAX_MAX_DEPTH,
                configure(AgentConfig.MAX_DEPTH, String.valueOf(Integer.MAX_VALUE)).maxDepth());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    @DisplayName("the buffer ceiling stays inside what a reader can open")
    void boundsTheBufferToSomethingReadable() {
        AgentConfig config = configure(AgentConfig.BUFFER_EVENTS, "99999999");

        assertEquals(AgentConfig.MAX_BUFFER_EVENTS, config.bufferEvents());
        assertTrue(config.bufferEvents() <= 1 << 16,
                "a buffer larger than this produces a trace nothing in this project can open");
    }

    @Test
    @DisplayName("the class counter is something you ask for")
    void debugIsOffByDefault() {
        assertFalse(configure().debug());
        assertFalse(configure().describe().contains("debug"));
        assertTrue(configure(AgentConfig.DEBUG, "true").debug());
        assertTrue(configure(AgentConfig.DEBUG, "true").describe().contains("debug=true"));
    }

    @Test
    @DisplayName("what was asked for is read back")
    void readsConfiguredValues() {
        AgentConfig config = configure(
                AgentConfig.PACKAGES, "com.example,org.acme",
                AgentConfig.BUFFER_EVENTS, "256",
                AgentConfig.MAX_DEPTH, "32",
                AgentConfig.DUMP, "true");

        assertTrue(config.scope().includes("com.example.Order"));
        assertEquals(256, config.bufferEvents());
        assertEquals(32, config.maxDepth());
        assertTrue(config.dump());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("the banner says what the agent is about to do")
    void describesItself() {
        AgentConfig config = configure(AgentConfig.PACKAGES, "com.example", AgentConfig.DUMP, "true");

        assertEquals("packages=com.example, buffer=1024 events/thread, maxDepth=256, "
                        + "values=summary/64, dump=true, traces=hindsight-traces (max 50)",
                config.describe());
    }
}
