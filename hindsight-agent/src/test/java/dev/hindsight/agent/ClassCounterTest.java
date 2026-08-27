package dev.hindsight.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClassCounterTest {

    private static final byte[] NO_BYTECODE = new byte[0];

    private ClassCounter counter;

    @BeforeEach
    void setUp() {
        counter = new ClassCounter();
    }

    private byte[] offer(String internalName) {
        return counter.transform(null, internalName, null, null, NO_BYTECODE);
    }

    @Test
    @DisplayName("returns null so the class is loaded unchanged")
    void neverTransforms() {
        assertNull(offer("sample/testapp/TestApp"));
        assertNull(offer("java/lang/String"));
    }

    @Test
    @DisplayName("splits what it sees into excluded and candidates")
    void countsCandidatesSeparatelyFromExclusions() {
        offer("sample/testapp/TestApp");
        offer("com/example/orders/OrderService");
        offer("java/lang/String");
        offer("dev/hindsight/agent/Hindsight");
        offer(null);

        assertEquals(5, counter.seen());
        assertEquals(3, counter.excluded());
        assertEquals(2, counter.candidates());
    }

    @Test
    @DisplayName("summary reads as a sentence")
    void summaryIsReadable() {
        offer("sample/testapp/TestApp");
        offer("java/lang/String");

        assertEquals("2 classes loaded since attach, 1 excluded, 1 candidate", counter.summary());
    }

    @Test
    @DisplayName("a counter that has seen nothing still reports cleanly")
    void emptyCounterIsWellFormed() {
        assertEquals("0 classes loaded since attach, 0 excluded, 0 candidates", counter.summary());
    }

    @Test
    @DisplayName("counts of one are not reported as plurals")
    void singularCountsReadCorrectly() {
        offer("sample/testapp/TestApp");

        assertEquals("1 class loaded since attach, 0 excluded, 1 candidate", counter.summary());
    }
}
