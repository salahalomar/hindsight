package dev.hindsight.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReentrancyTest {

    @AfterEach
    void releaseWhateverThisTestLeftBehind() {
        Reentrancy.release();
    }

    @Test
    @DisplayName("the first caller acquires the guard")
    void acquiresWhenIdle() {
        assertTrue(Reentrancy.acquire());
        assertTrue(Reentrancy.isRecording());
    }

    @Test
    @DisplayName("a recorder that reaches instrumented code is turned away")
    void refusesToNest() {
        assertTrue(Reentrancy.acquire());

        assertFalse(Reentrancy.acquire(), "the nested call must not be allowed to record");
        assertFalse(Reentrancy.acquire(), "and still not on a third attempt");
    }

    @Test
    @DisplayName("releasing lets the next event through")
    void releaseRestoresTheThread() {
        assertTrue(Reentrancy.acquire());
        Reentrancy.release();

        assertFalse(Reentrancy.isRecording());
        assertTrue(Reentrancy.acquire(), "the next instrumented call on this thread must record");
    }

    @Test
    @DisplayName("one thread recording does not silence another")
    void isolatesThreads() throws Exception {
        assertTrue(Reentrancy.acquire());

        AtomicBoolean acquiredElsewhere = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            acquiredElsewhere.set(Reentrancy.acquire());
            finished.countDown();
        });
        other.start();

        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertTrue(acquiredElsewhere.get(),
                "the guard is per-thread; a busy recorder on one thread must not blind the others");
        assertTrue(Reentrancy.isRecording(), "and this thread still holds its own");
    }

    @Test
    @DisplayName("virtual threads each get their own guard")
    void isolatesVirtualThreads() throws Exception {
        assertTrue(Reentrancy.acquire());

        AtomicBoolean acquiredElsewhere = new AtomicBoolean();
        Thread virtual = Thread.ofVirtual().start(() -> acquiredElsewhere.set(Reentrancy.acquire()));
        virtual.join();

        assertTrue(acquiredElsewhere.get());
    }
}
