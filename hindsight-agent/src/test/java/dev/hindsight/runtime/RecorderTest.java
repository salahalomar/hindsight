package dev.hindsight.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The recorder's job at capture time is to turn values into text without touching them. These tests
 * pin that reduction; how the resulting text is laid out is the formatter's problem.
 */
class RecorderTest {

    @Test
    @DisplayName("no arguments reduce to nothing")
    void noArguments() {
        assertEquals("", Recorder.argumentTypes(new Object[0]));
    }

    @Test
    @DisplayName("a missing argument array is not a crash")
    void nullArgumentArray() {
        assertEquals("", Recorder.argumentTypes(null));
    }

    @Test
    @DisplayName("arguments are listed by type, in order")
    void argumentTypesInOrder() {
        assertEquals("String, Integer, Boolean",
                Recorder.argumentTypes(new Object[]{"salah", 42, true}));
    }

    @Test
    @DisplayName("a null argument is reported as null rather than skipped")
    void nullArgument() {
        assertEquals("null, String", Recorder.argumentTypes(new Object[]{null, "x"}));
    }

    @Test
    @DisplayName("an anonymous class falls back to its binary name rather than an empty one")
    void anonymousClassesHaveNoSimpleName() {
        Object anonymous = new Object() {
        };

        assertEquals(anonymous.getClass().getName(), Recorder.typeOf(anonymous));
    }

    @Test
    @DisplayName("the value itself is never rendered, only its type")
    void valuesAreNeverRendered() {
        String secret = "correct-horse-battery-staple";

        assertFalse(Recorder.argumentTypes(new Object[]{secret}).contains(secret),
                "reading values means calling toString on application objects; that is step 4's job");
    }

    @Test
    @DisplayName("a throwing toString is never given the chance to throw")
    void hostileToStringIsNeverCalled() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new UnsupportedOperationException("toString must not be called");
            }
        };

        assertFalse(Recorder.argumentTypes(new Object[]{hostile}).isEmpty());
    }
}
