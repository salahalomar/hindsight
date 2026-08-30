package dev.hindsight.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorder's own job is joining summaries into an argument list. What a single summary looks
 * like is {@link ValueSummariserTest}'s problem.
 */
class RecorderTest {

    private final ValueSummariser summariser = new ValueSummariser(ValueDetail.SUMMARY, 64);

    @Test
    @DisplayName("settings that could only ever record nothing are refused at startup")
    void configureRefusesUnusableSettings() {
        // A capacity that is not a power of two makes every buffer construction throw, inside a
        // ThreadLocal initialiser on a path that swallows throwables by design. The agent would
        // attach, print a normal banner, instrument everything and record absolutely nothing.
        assertThrows(IllegalArgumentException.class,
                () -> Recorder.configure(1000, 256, false, summariser, null));
        assertThrows(IllegalArgumentException.class,
                () -> Recorder.configure(1024, 0, false, summariser, null));
        assertThrows(IllegalArgumentException.class,
                () -> Recorder.configure(1024, 256, false, null, null));
    }

    @Test
    @DisplayName("no arguments join to nothing")
    void noArguments() {
        assertEquals("", Recorder.argumentSummaries(summariser, new Object[0]));
    }

    @Test
    @DisplayName("a missing argument array is not a crash")
    void nullArgumentArray() {
        assertEquals("", Recorder.argumentSummaries(summariser, null));
    }

    @Test
    @DisplayName("arguments are joined in order")
    void argumentsInOrder() {
        assertEquals("String \"salah\", Integer 42, Boolean true",
                Recorder.argumentSummaries(summariser, new Object[]{"salah", 42, true}));
    }

    @Test
    @DisplayName("a null argument is reported as null rather than skipped")
    void nullArgument() {
        assertEquals("null, String \"x\"", Recorder.argumentSummaries(summariser, new Object[]{null, "x"}));
    }

    @Test
    @DisplayName("one hostile argument does not cost the whole argument list")
    void hostileArgumentsAreContained() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new UnsupportedOperationException("toString must not escape");
            }
        };

        String summaries = Recorder.argumentSummaries(summariser, new Object[]{"before", hostile, 42});

        assertTrue(summaries.startsWith("String \"before\", "), summaries);
        assertTrue(summaries.contains("toString threw UnsupportedOperationException"), summaries);
        assertTrue(summaries.endsWith(", Integer 42"), summaries);
    }
}
