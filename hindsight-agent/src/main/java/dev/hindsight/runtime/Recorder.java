package dev.hindsight.runtime;

import dev.hindsight.trace.TraceFormatter;
import dev.hindsight.trace.TraceWriter;

/**
 * The sink that instrumented methods call into.
 *
 * <p>Byte Buddy inlines advice bodies into the target method, so these methods are invoked from
 * application code, on the application's class loader, on whatever thread the application happens
 * to be using. Two consequences shape everything here. Nothing may throw: an exception leaving this
 * class surfaces inside a method that has no idea it was instrumented. And nothing may be slow:
 * this runs twice per invocation of every traced method.
 *
 * <p>Values are reduced to text by {@link ValueSummariser} and the reference is dropped before
 * returning. Nothing belonging to the application outlives the call that reported it.
 *
 * <p>Buffers are held in a {@link ThreadLocal} and allocated on a thread's first event. That makes
 * them collectable when the thread dies, which matters a great deal when the threads are virtual
 * and there are a great many of them, and it means a dump can only ever be of the calling thread's
 * own history. For request-scoped tracing that is the right shape; recording across threads is a
 * different problem and would need a different design rather than a wider data structure.
 *
 * <p>Summarising happens inside the reentrancy guard. That matters more than it looks: rendering a
 * value calls application code, and if that code reaches an instrumented method the guard is what
 * stops the recorder being re-entered halfway through recording.
 */
public final class Recorder {

    private static final String VOID = "void";
    private static final String NO_ARGUMENTS = "";

    /*
     * Read when a thread allocates its buffer, and once per completed outermost frame. Deliberately
     * not read from a system property on the recording path: property lookups are synchronised map
     * reads, and this runs twice per traced invocation.
     */
    private static volatile int bufferEvents = 1024;
    private static volatile int maxDepth = 256;
    private static volatile boolean dump;
    private static volatile ValueSummariser summariser =
            new ValueSummariser(ValueDetail.SUMMARY, 64);
    private static volatile TraceWriter traceWriter;

    private static final ThreadLocal<RingBuffer> BUFFERS = new ThreadLocal<>() {
        @Override
        protected RingBuffer initialValue() {
            return new RingBuffer(bufferEvents, maxDepth);
        }
    };

    private Recorder() {
    }

    /**
     * Called once from {@code premain}, before any application class has been instrumented.
     *
     * <p>Validates eagerly, because the alternative is silent. {@link RingBuffer} rejects a
     * capacity that is not a power of two, and it is constructed inside a {@link ThreadLocal}
     * initialiser reached from the recording path, where every throwable is swallowed by design.
     * A bad value would therefore produce an agent that attaches, prints a normal banner,
     * instruments everything and records nothing at all, with no diagnostic anywhere. Failing here
     * is loud and happens once, at startup.
     *
     * @throws IllegalArgumentException if the settings could never produce a usable buffer
     */
    public static void configure(int bufferEvents, int maxDepth, boolean dump,
                                 ValueSummariser summariser, TraceWriter traceWriter) {
        // Constructed and discarded purely to reject bad settings where somebody will see it.
        new RingBuffer(bufferEvents, maxDepth);
        if (summariser == null) {
            throw new IllegalArgumentException("a summariser is required");
        }
        Recorder.bufferEvents = bufferEvents;
        Recorder.maxDepth = maxDepth;
        Recorder.dump = dump;
        Recorder.summariser = summariser;
        Recorder.traceWriter = traceWriter;
    }

    public static void onEnter(String type, String method, Object[] arguments) {
        if (!Reentrancy.acquire()) {
            return;
        }
        try {
            BUFFERS.get().recordEnter(type, method, argumentSummaries(summariser, arguments));
        } catch (Throwable ignored) {
            // The advice suppresses throwables too. This is the inner of the two nets, and it is
            // here because a recorder that can break its host is not worth attaching.
        } finally {
            Reentrancy.release();
        }
    }

    public static void onExit(String type, String method, String returnType, Object returned, Throwable thrown) {
        if (!Reentrancy.acquire()) {
            return;
        }
        try {
            RingBuffer buffer = BUFFERS.get();
            ValueSummariser values = summariser;
            if (thrown != null) {
                buffer.recordThrow(type, method, values.describe(thrown));
            } else {
                buffer.recordReturn(type, method,
                        VOID.equals(returnType) ? VOID : values.summarise(returned));
            }
            if (buffer.depth() == 0) {
                completeOutermostFrame(buffer, type, method, thrown != null);
            }
        } catch (Throwable ignored) {
            // As above.
        } finally {
            Reentrancy.release();
        }
    }

    /**
     * The outermost instrumented frame has returned, so whatever the buffer holds is one complete
     * unit of work. Resetting here is what makes the buffer mean "this request" rather than a
     * rolling mixture of unrelated ones, which is the property step 5 needs in order to dump
     * something coherent when an exception escapes.
     */
    private static void completeOutermostFrame(RingBuffer buffer, String type, String method, boolean failed) {
        String thread = Thread.currentThread().getName();
        // The file first. It is the artefact somebody will still have at 3am; the console tree is a
        // convenience for whoever is watching right now.
        TraceWriter writer = traceWriter;
        if (failed && writer != null) {
            writer.write(thread, type, method, buffer);
        }
        if (dump) {
            System.out.println(TraceFormatter.render(thread, buffer));
        }
        buffer.reset();
    }

    /** Takes the summariser as an argument so the joining logic can be tested without global state. */
    static String argumentSummaries(ValueSummariser values, Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return NO_ARGUMENTS;
        }
        StringBuilder summaries = new StringBuilder(values.summarise(arguments[0]));
        for (int i = 1; i < arguments.length; i++) {
            summaries.append(", ").append(values.summarise(arguments[i]));
        }
        return summaries.toString();
    }
}
