package dev.hindsight.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingBufferTest {

    private static final String TYPE = "sample.testapp.TestApp";

    private static RingBuffer buffer(int capacity, int maxDepth) {
        return new RingBuffer(capacity, maxDepth);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("capacity must be a power of two, because the ring indexes by masking")
        void rejectsNonPowerOfTwoCapacity() {
            assertThrows(IllegalArgumentException.class, () -> buffer(1000, 8));
            assertThrows(IllegalArgumentException.class, () -> buffer(0, 8));
            assertThrows(IllegalArgumentException.class, () -> buffer(-8, 8));
        }

        @Test
        @DisplayName("a depth limit below one would record nothing at all")
        void rejectsUselessDepthLimit() {
            assertThrows(IllegalArgumentException.class, () -> buffer(16, 0));
        }

        @Test
        @DisplayName("a fresh buffer is empty and at depth zero")
        void startsEmpty() {
            RingBuffer buffer = buffer(16, 8);

            assertEquals(0, buffer.size());
            assertEquals(0, buffer.depth());
            assertEquals(0, buffer.dropped());
            assertEquals(16, buffer.capacity());
        }
    }

    @Nested
    @DisplayName("call depth")
    class Depth {

        @Test
        @DisplayName("entries nest and exits unwind")
        void tracksNesting() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordEnter(TYPE, "main", "String[]");
            assertEquals(1, buffer.depth());
            buffer.recordEnter(TYPE, "greet", "String");
            assertEquals(2, buffer.depth());
            buffer.recordReturn(TYPE, "greet", "String");
            assertEquals(1, buffer.depth());
            buffer.recordReturn(TYPE, "main", "void");
            assertEquals(0, buffer.depth());
        }

        @Test
        @DisplayName("an entry and its matching exit are recorded at the same depth")
        void pairsShareADepth() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordEnter(TYPE, "main", "");
            buffer.recordEnter(TYPE, "greet", "");
            buffer.recordReturn(TYPE, "greet", "String");
            buffer.recordReturn(TYPE, "main", "void");

            assertEquals(0, buffer.depthAt(0));
            assertEquals(1, buffer.depthAt(1));
            assertEquals(1, buffer.depthAt(2));
            assertEquals(0, buffer.depthAt(3));
        }

        @Test
        @DisplayName("an unmatched exit cannot drive the depth negative, and is counted")
        void countsAnUnmatchedExitRatherThanAbsorbingIt() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordReturn(TYPE, "orphan", "void");

            // Losing track of the stack should cost a slightly wrong trace, not an exception
            // thrown into an application in the middle of returning from a method.
            assertEquals(0, buffer.depth());
            assertEquals(0, buffer.depthAt(0));
            // But it must be visible. Silently clamping made a recording that had lost its
            // beginning indistinguishable from a complete one.
            assertEquals(1, buffer.unbalancedExits());
        }

        @Test
        @DisplayName("a balanced trace reports no imbalance")
        void countsNothingWhenNothingIsLost() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordEnter(TYPE, "main", "");
            buffer.recordReturn(TYPE, "main", "void");

            assertEquals(0, buffer.unbalancedExits());
        }

        @Test
        @DisplayName("frames past the limit are counted, and entry and exit are dropped together")
        void suppressesBeyondMaxDepth() {
            RingBuffer buffer = buffer(64, 2);

            buffer.recordEnter(TYPE, "a", "");    // depth 0, recorded
            buffer.recordEnter(TYPE, "b", "");    // depth 1, recorded
            buffer.recordEnter(TYPE, "c", "");    // depth 2, suppressed
            buffer.recordReturn(TYPE, "c", "v");  // suppressed
            buffer.recordReturn(TYPE, "b", "v");  // depth 1, recorded
            buffer.recordReturn(TYPE, "a", "v");  // depth 0, recorded

            assertEquals(4, buffer.size(), "the pairs that survive must still be pairs");
            assertEquals(2, buffer.beyondMaxDepth());
            assertEquals(0, buffer.depth(), "depth accounting continues past the recording limit");
        }
    }

    @Nested
    @DisplayName("bounded storage")
    class Bounded {

        @Test
        @DisplayName("the oldest events are overwritten and counted")
        void overwritesOldest() {
            RingBuffer buffer = buffer(4, 64);
            for (int i = 0; i < 6; i++) {
                buffer.recordEnter(TYPE, "m" + i, "");
            }

            assertEquals(4, buffer.size());
            assertEquals(2, buffer.dropped());
            assertEquals("m2", buffer.methodAt(0), "index 0 is the oldest event still held");
            assertEquals("m5", buffer.methodAt(3));
        }

        @Test
        @DisplayName("reading outside what is held is a bug, not a silent wrong answer")
        void boundsAreChecked() {
            RingBuffer buffer = buffer(4, 64);
            buffer.recordEnter(TYPE, "only", "");

            assertThrows(IndexOutOfBoundsException.class, () -> buffer.methodAt(1));
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.methodAt(-1));
        }
    }

    @Nested
    @DisplayName("recorded content")
    class Content {

        @Test
        @DisplayName("each kind is stored as itself")
        void storesKinds() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordEnter(TYPE, "a", "String");
            buffer.recordThrow(TYPE, "a", "IllegalStateException");
            buffer.recordEnter(TYPE, "b", "");
            buffer.recordReturn(TYPE, "b", "int");

            assertEquals(EventKind.ENTER, buffer.kindAt(0));
            assertEquals(EventKind.THROW, buffer.kindAt(1));
            assertEquals(EventKind.ENTER, buffer.kindAt(2));
            assertEquals(EventKind.RETURN, buffer.kindAt(3));
            assertEquals("IllegalStateException", buffer.detailAt(1));
            assertEquals(TYPE, buffer.typeAt(0));
        }

        @Test
        @DisplayName("events are timestamped in order")
        void timestampsDoNotGoBackwards() {
            RingBuffer buffer = buffer(16, 8);

            buffer.recordEnter(TYPE, "a", "");
            buffer.recordReturn(TYPE, "a", "void");

            assertTrue(buffer.nanosAt(1) >= buffer.nanosAt(0));
        }
    }

    @Test
    @DisplayName("reset returns the buffer to the state a new request expects")
    void resetClearsEverything() {
        RingBuffer buffer = buffer(4, 2);
        for (int i = 0; i < 6; i++) {
            buffer.recordEnter(TYPE, "m" + i, "");
        }

        buffer.reset();

        assertEquals(0, buffer.size());
        assertEquals(0, buffer.depth());
        assertEquals(0, buffer.dropped());
        assertEquals(0, buffer.beyondMaxDepth());
        assertEquals(0, buffer.unbalancedExits());
    }
}
