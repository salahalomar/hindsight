package dev.hindsight.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecorderTest {

    private static final String TYPE = "sample.testapp.TestApp";

    @Nested
    @DisplayName("entry lines")
    class EntryLines {

        @Test
        @DisplayName("a method with no arguments reads as empty parentheses")
        void noArguments() {
            assertEquals("[hindsight] [main] -> sample.testapp.TestApp.greet()",
                    Recorder.entryLine("main", TYPE, "greet", new Object[0]));
        }

        @Test
        @DisplayName("arguments are listed by type, in order")
        void argumentTypes() {
            assertEquals("[hindsight] [main] -> sample.testapp.TestApp.greet(String, Integer)",
                    Recorder.entryLine("main", TYPE, "greet", new Object[]{"salah", 42}));
        }

        @Test
        @DisplayName("a null argument is reported as null rather than skipped")
        void nullArgument() {
            assertEquals("[hindsight] [main] -> sample.testapp.TestApp.greet(null, String)",
                    Recorder.entryLine("main", TYPE, "greet", new Object[]{null, "x"}));
        }

        @Test
        @DisplayName("a missing argument array is not a crash")
        void nullArgumentArray() {
            assertEquals("[hindsight] [main] -> sample.testapp.TestApp.greet()",
                    Recorder.entryLine("main", TYPE, "greet", null));
        }

        @Test
        @DisplayName("the thread name is part of the line")
        void namesTheThread() {
            assertEquals("[hindsight] [pool-1-thread-3] -> sample.testapp.TestApp.greet()",
                    Recorder.entryLine("pool-1-thread-3", TYPE, "greet", new Object[0]));
        }
    }

    @Nested
    @DisplayName("exit lines")
    class ExitLines {

        @Test
        @DisplayName("a returned value is reported by type")
        void returnedValue() {
            assertEquals("[hindsight] [main] <- sample.testapp.TestApp.greet returned String",
                    Recorder.exitLine("main", TYPE, "greet", "java.lang.String", "hello", null));
        }

        @Test
        @DisplayName("void is distinguished from a null return")
        void voidIsNotNull() {
            assertEquals("[hindsight] [main] <- sample.testapp.TestApp.run returned void",
                    Recorder.exitLine("main", TYPE, "run", "void", null, null));
            assertEquals("[hindsight] [main] <- sample.testapp.TestApp.find returned null",
                    Recorder.exitLine("main", TYPE, "find", "java.lang.String", null, null));
        }

        @Test
        @DisplayName("a thrown exception replaces the return")
        void thrown() {
            assertEquals("[hindsight] [main] <- sample.testapp.TestApp.greet threw IllegalStateException",
                    Recorder.exitLine("main", TYPE, "greet", "java.lang.String", null,
                            new IllegalStateException("boom")));
        }

        @Test
        @DisplayName("a throw from a void method still reports the throw")
        void thrownFromVoid() {
            assertEquals("[hindsight] [main] <- sample.testapp.TestApp.run threw ArithmeticException",
                    Recorder.exitLine("main", TYPE, "run", "void", null, new ArithmeticException()));
        }
    }

    @Test
    @DisplayName("an anonymous class falls back to its binary name rather than an empty one")
    void anonymousClassesHaveNoSimpleName() {
        Object anonymous = new Object() {
        };
        assertFalse(anonymous.getClass().getSimpleName().isEmpty()
                        && Recorder.entryLine("main", TYPE, "greet", new Object[]{anonymous}).contains("()"),
                "guard against the fallback silently not being exercised");

        String line = Recorder.entryLine("main", TYPE, "greet", new Object[]{anonymous});
        assertEquals("[hindsight] [main] -> sample.testapp.TestApp.greet("
                + anonymous.getClass().getName() + ")", line);
    }

    @Test
    @DisplayName("the value itself is never printed, only its type")
    void valuesAreNeverRendered() {
        String secret = "correct-horse-battery-staple";
        String line = Recorder.entryLine("main", TYPE, "greet", new Object[]{secret});

        assertFalse(line.contains(secret),
                "step 2 must not call toString on application objects; that is step 4's job");
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

        String line = Recorder.entryLine("main", TYPE, "greet", new Object[]{hostile});
        assertFalse(line.isEmpty());
    }
}
