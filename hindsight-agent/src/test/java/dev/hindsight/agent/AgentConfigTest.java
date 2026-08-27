package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
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

        assertEquals("packages=com.example, buffer=1024 events/thread, maxDepth=256, dump=true",
                config.describe());
    }
}
