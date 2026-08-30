package dev.hindsight.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueSummariserTest {

    private final ValueSummariser summariser = new ValueSummariser(ValueDetail.SUMMARY, 16);

    @Nested
    @DisplayName("values with a rendering that cannot be overridden")
    class SafeRenderings {

        @Test
        @DisplayName("null is null, not an absence")
        void nulls() {
            assertEquals("null", summariser.summarise(null));
        }

        @Test
        @DisplayName("strings are quoted and escaped")
        void strings() {
            assertEquals("String \"salah\"", summariser.summarise("salah"));
            assertEquals("String \"a\\nb\\tc\"", summariser.summarise("a\nb\tc"));
            assertEquals("String \"say \\\"hi\\\"\"", summariser.summarise("say \"hi\""));
            assertEquals("String \"\\u0007\"", summariser.summarise("\u0007"));
        }

        @Test
        @DisplayName("boxed primitives print as themselves")
        void boxedPrimitives() {
            assertEquals("Integer 42", summariser.summarise(42));
            assertEquals("Long 42", summariser.summarise(42L));
            assertEquals("Double 1.5", summariser.summarise(1.5));
            assertEquals("Boolean true", summariser.summarise(true));
            assertEquals("Character 'x'", summariser.summarise('x'));
        }

        @Test
        @DisplayName("a Number subclass is not assumed safe just because it is a Number")
        void userWrittenNumbersAreNotTrusted() {
            String summary = summariser.summarise(new HostileNumber());

            // Matched by exact class rather than instanceof, so this falls through to the guarded
            // object path and is reported, rather than exploding inside a string concatenation.
            assertTrue(summary.contains("toString threw AssertionError"), summary);
        }

        @Test
        @DisplayName("enum constants are read through name(), which cannot be overridden")
        void enums() {
            // ACTIVE has a constant body, so its getClass() is an anonymous subclass with no simple
            // name, and its toString() is overridden with something misleading.
            assertEquals("Status.ACTIVE", summariser.summarise(Status.ACTIVE));
            assertEquals("Status.IDLE", summariser.summarise(Status.IDLE));
        }
    }

    @Nested
    @DisplayName("containers are described, never rendered")
    class Containers {

        @Test
        @DisplayName("a huge collection costs a size call, not a hundred megabytes of string")
        void hugeCollections() {
            // toString on a million-element list builds the whole string before any length cap
            // could apply. This one throws if it is ever called.
            String summary = summariser.summarise(new HugeList());

            assertTrue(summary.startsWith("HugeList[1000000]@"), summary);
        }

        @Test
        @DisplayName("collections and maps are told apart at a glance")
        void collectionsAndMaps() {
            // Concrete JDK types on purpose: the factory methods return internal classes whose
            // names are not a contract, and a test should not pin one.
            String list = summariser.summarise(new ArrayList<>(List.of("a", "b")));
            String map = summariser.summarise(new HashMap<>(Map.of("a", 1)));

            assertTrue(list.matches("ArrayList\\[2]@[0-9a-f]+"), list);
            assertTrue(map.matches("HashMap\\{1}@[0-9a-f]+"), map);
        }

        @Test
        @DisplayName("arrays report their component type and length without reading elements")
        void arrays() {
            assertTrue(summariser.summarise(new int[4]).startsWith("int[4]@"));
            assertTrue(summariser.summarise(new String[0]).startsWith("String[0]@"));
            assertTrue(summariser.summarise(new int[2][]).startsWith("int[][2]@"));
        }

        @Test
        @DisplayName("a size() that throws is application code too, and is guarded")
        void hostileSize() {
            assertTrue(summariser.summarise(new ExplodingSize()).startsWith("ExplodingSize[?]@"),
                    summariser.summarise(new ExplodingSize()));
        }
    }

    @Nested
    @DisplayName("objects, where application code actually runs")
    class Objects {

        @Test
        @DisplayName("a class that does not override toString is not asked")
        void withoutOverride() {
            String summary = summariser.summarise(new Plain());

            // Type and identity only. The inherited toString would repeat the identity and nothing
            // else, so it is not worth a call into the application.
            assertTrue(summary.matches("Plain@[0-9a-f]+"), summary);
        }

        @Test
        @DisplayName("a class that does override toString has it rendered")
        void withOverride() {
            String summary = summariser.summarise(new Described("ok"));

            assertTrue(summary.matches("Described@[0-9a-f]+ \"described ok\""), summary);
        }

        @Test
        @DisplayName("a throwing toString is reported as having thrown")
        void hostileToString() {
            String summary = summariser.summarise(new HostileToString());

            assertTrue(summary.matches("HostileToString@[0-9a-f]+ <toString threw IllegalStateException>"),
                    summary);
        }

        @Test
        @DisplayName("mutually recursive toString ends in a caught error, not a dead thread")
        void cyclicStructures() {
            Cycle first = new Cycle();
            Cycle second = new Cycle();
            first.other = second;
            second.other = first;

            String summary = summariser.summarise(first);

            assertTrue(summary.contains("<toString threw StackOverflowError>"), summary);
        }

        @Test
        @DisplayName("a toString that returns null is not a null summary")
        void nullReturningToString() {
            assertTrue(summariser.summarise(new NullToString()).endsWith("<toString returned null>"));
        }

        @Test
        @DisplayName("the same instance summarises identically, so a trace can be read for identity")
        void identityIsStable() {
            Plain instance = new Plain();

            assertEquals(summariser.summarise(instance), summariser.summarise(instance));
        }
    }

    @Nested
    @DisplayName("bounded output")
    class Bounded {

        @Test
        @DisplayName("a cut value says how long it really was")
        void truncationIsAnnounced() {
            String summary = summariser.summarise("0123456789abcdefGHIJK");

            // Sixteen characters kept, and the real length reported so the cut cannot be mistaken
            // for the value having ended there.
            assertEquals("String (21) \"0123456789abcdef...\"", summary);
        }

        @Test
        @DisplayName("a value exactly at the limit is not cut")
        void boundaryIsInclusive() {
            assertEquals("String \"0123456789abcdef\"", summariser.summarise("0123456789abcdef"));
        }

        @Test
        @DisplayName("a character is never cut in half")
        void neverSplitsASurrogatePair() {
            // The cap is sixteen UTF-16 units, and this puts the emoji's two halves at units 15
            // and 16, so a naive cut keeps the first half alone. A lone high surrogate is not a
            // character: the UTF-8 encoder writes it as '?', corrupting exactly the character at
            // the boundary somebody is squinting at.
            String summary = summariser.summarise("a".repeat(15) + "\uD83D\uDE00" + "zzz");

            assertFalse(summary.contains("?"), summary);
            assertEquals("String (20) \"" + "a".repeat(15) + "...\"", summary);
        }

        @Test
        @DisplayName("a character that fits entirely is kept entirely")
        void keepsACompleteCharacterAtTheLimit() {
            // Here the pair occupies units 15 and 16, ending exactly on the cap, so it survives.
            String summary = summariser.summarise("a".repeat(14) + "\uD83D\uDE00" + "zz");

            assertTrue(summary.contains("\uD83D\uDE00"), summary);
            assertFalse(summary.contains("?"), summary);
            assertEquals("String (18) \"" + "a".repeat(14) + "\uD83D\uDE00...\"", summary);
        }

        @Test
        @DisplayName("an overridden toString is capped like any other text")
        void toStringIsCappedToo() {
            String summary = summariser.summarise(new Described("x".repeat(200)));

            assertTrue(summary.contains("(210) \""), summary);
            assertTrue(summary.endsWith("...\""), summary);
        }
    }

    @Nested
    @DisplayName("thrown exceptions")
    class Thrown {

        @Test
        @DisplayName("the message is the part worth having")
        void message() {
            assertEquals("IllegalStateException: \"boom\"",
                    summariser.describe(new IllegalStateException("boom")));
        }

        @Test
        @DisplayName("no message is not an empty message")
        void withoutMessage() {
            assertEquals("IllegalStateException", summariser.describe(new IllegalStateException()));
        }

        @Test
        @DisplayName("a getMessage that throws is guarded like everything else")
        void hostileMessage() {
            assertTrue(summariser.describe(new HostileMessage()).contains("threw"),
                    summariser.describe(new HostileMessage()));
        }

        @Test
        @DisplayName("a long message is capped")
        void longMessage() {
            assertTrue(summariser.describe(new IllegalStateException("y".repeat(100))).contains("(100)"));
        }
    }

    @Nested
    @DisplayName("type mode")
    class TypeOnly {

        private final ValueSummariser types = new ValueSummariser(ValueDetail.TYPE, 16);

        @Test
        @DisplayName("nothing belonging to the application is called at all")
        void nothingIsInvoked() {
            // No toString, no size, no getMessage. This is the setting for an application whose
            // toString implementations cannot be trusted to be free of side effects.
            assertEquals("HostileToString", types.summarise(new HostileToString()));
            assertEquals("HugeList", types.summarise(new HugeList()));
            assertEquals("ExplodingSize", types.summarise(new ExplodingSize()));
            assertEquals("IllegalStateException", types.describe(new IllegalStateException("boom")));
        }

        @Test
        @DisplayName("values are still distinguishable by type")
        void stillUseful() {
            assertEquals("String", types.summarise("salah"));
            assertEquals("Integer", types.summarise(42));
            assertEquals("null", types.summarise(null));
        }

        @Test
        @DisplayName("no value ever reaches the output")
        void valuesNeverLeak() {
            String secret = "correct-horse-battery-staple";

            assertFalse(types.summarise(secret).contains(secret));
        }
    }

    // --- fixtures -------------------------------------------------------------------------------

    private enum Status {
        ACTIVE {
            @Override
            public String toString() {
                return "this must not be used";
            }
        },
        IDLE
    }

    private static final class Plain {
    }

    private record Described(String what) {
        @Override
        public String toString() {
            return "described " + what;
        }
    }

    private static final class HostileToString {
        @Override
        public String toString() {
            throw new IllegalStateException("toString must not be trusted");
        }
    }

    private static final class NullToString {
        @Override
        public String toString() {
            return null;
        }
    }

    private static final class Cycle {
        private Cycle other;

        @Override
        public String toString() {
            return "cycle -> " + other;
        }
    }

    private static final class HostileMessage extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage must not be trusted");
        }
    }

    private static final class HostileNumber extends Number {
        @Override
        public int intValue() {
            return 0;
        }

        @Override
        public long longValue() {
            return 0;
        }

        @Override
        public float floatValue() {
            return 0;
        }

        @Override
        public double doubleValue() {
            return 0;
        }

        @Override
        public String toString() {
            throw new AssertionError("a user-written Number must not be treated as a safe scalar");
        }
    }

    /** Reports a size no sane toString could render, and throws if anyone tries. */
    private static final class HugeList extends AbstractList<String> {
        @Override
        public String get(int index) {
            throw new AssertionError("elements must not be read");
        }

        @Override
        public int size() {
            return 1_000_000;
        }

        @Override
        public String toString() {
            throw new AssertionError("toString must not be called on a collection");
        }
    }

    private static final class ExplodingSize extends AbstractList<String> {
        @Override
        public String get(int index) {
            throw new AssertionError("elements must not be read");
        }

        @Override
        public int size() {
            throw new IllegalStateException("size must not be trusted either");
        }
    }
}
