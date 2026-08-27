package dev.hindsight.runtime;

/**
 * Stops the recorder from recording itself.
 *
 * <p>The recorder calls into ordinary Java to do its work. The moment any of that is instrumented,
 * recording an event triggers recording an event, and the thread dies of stack exhaustion. This
 * guard is what makes that impossible rather than merely unlikely.
 *
 * <p>The flag is held only for the duration of a recorder call, never across the body of the
 * instrumented method. Nested application frames must still be recorded -- a call tree with only
 * its root is not a call tree -- so the guard closes over the recording, not over the callee.
 *
 * <p>The mutable {@code boolean[]} is deliberate. {@link ThreadLocal#set} writes through to the
 * thread's map on every call; mutating a slot fetched once does not, which matters on a path that
 * runs twice per instrumented invocation.
 */
public final class Reentrancy {

    private static final ThreadLocal<boolean[]> RECORDING = new ThreadLocal<>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    private Reentrancy() {
    }

    /**
     * @return {@code true} if the caller now holds the guard and must {@link #release} it,
     *         {@code false} if this thread is already inside the recorder
     */
    public static boolean acquire() {
        boolean[] recording = RECORDING.get();
        if (recording[0]) {
            return false;
        }
        recording[0] = true;
        return true;
    }

    /** Only ever called by a caller whose {@link #acquire} returned {@code true}. */
    public static void release() {
        RECORDING.get()[0] = false;
    }

    static boolean isRecording() {
        return RECORDING.get()[0];
    }
}
