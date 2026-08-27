package dev.hindsight.runtime;

import dev.hindsight.trace.TraceFormatter;

/**
 * The sink that instrumented methods call into.
 *
 * <p>Byte Buddy inlines advice bodies into the target method, so these methods are invoked from
 * application code, on the application's class loader, on whatever thread the application happens
 * to be using. Two consequences shape everything here. Nothing may throw: an exception leaving this
 * class surfaces inside a method that has no idea it was instrumented. And nothing may be slow:
 * this runs twice per invocation of every traced method.
 *
 * <p>Values are reduced to their type name and the reference is dropped before returning. Nothing
 * belonging to the application outlives the call that reported it.
 *
 * <p>Buffers are held in a {@link ThreadLocal} and allocated on a thread's first event. That makes
 * them collectable when the thread dies, which matters a great deal when the threads are virtual
 * and there are a great many of them, and it means a dump can only ever be of the calling thread's
 * own history. For request-scoped tracing that is the right shape; recording across threads is a
 * different problem and would need a different design rather than a wider data structure.
 *
 * <p>Types are still reported in place of values. Rendering a value means calling {@code toString}
 * on an application object, which can be slow, can throw, and can have side effects; that needs the
 * guarded summariser in step 4 rather than an unguarded call here.
 */
public final class Recorder {

    private static final String VOID = "void";
    private static final String NO_ARGUMENTS = "";
    private static final String NULL = "null";

    /*
     * Read when a thread allocates its buffer, and once per completed outermost frame. Deliberately
     * not read from a system property on the recording path: property lookups are synchronised map
     * reads, and this runs twice per traced invocation.
     */
    private static volatile int bufferEvents = 1024;
    private static volatile int maxDepth = 256;
    private static volatile boolean dump;

    private static final ThreadLocal<RingBuffer> BUFFERS = new ThreadLocal<>() {
        @Override
        protected RingBuffer initialValue() {
            return new RingBuffer(bufferEvents, maxDepth);
        }
    };

    private Recorder() {
    }

    /** Called once from {@code premain}, before any application class has been instrumented. */
    public static void configure(int bufferEvents, int maxDepth, boolean dump) {
        Recorder.bufferEvents = bufferEvents;
        Recorder.maxDepth = maxDepth;
        Recorder.dump = dump;
    }

    public static void onEnter(String type, String method, Object[] arguments) {
        if (!Reentrancy.acquire()) {
            return;
        }
        try {
            BUFFERS.get().recordEnter(type, method, argumentTypes(arguments));
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
            if (thrown != null) {
                buffer.recordThrow(type, method, typeOf(thrown));
            } else {
                buffer.recordReturn(type, method, VOID.equals(returnType) ? VOID : typeOf(returned));
            }
            if (buffer.depth() == 0) {
                completeOutermostFrame(buffer);
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
    private static void completeOutermostFrame(RingBuffer buffer) {
        if (dump) {
            System.out.println(TraceFormatter.render(Thread.currentThread().getName(), buffer));
        }
        buffer.reset();
    }

    static String argumentTypes(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return NO_ARGUMENTS;
        }
        StringBuilder types = new StringBuilder(typeOf(arguments[0]));
        for (int i = 1; i < arguments.length; i++) {
            types.append(", ").append(typeOf(arguments[i]));
        }
        return types.toString();
    }

    /**
     * The type of a value, never the value. {@code getClass} is safe to call on anything; it runs
     * no application code and cannot be overridden.
     */
    static String typeOf(Object value) {
        if (value == null) {
            return NULL;
        }
        Class<?> actual = value.getClass();
        String simple = actual.getSimpleName();
        // Anonymous classes have no simple name. Reporting an empty string as a type helps nobody.
        return simple.isEmpty() ? actual.getName() : simple;
    }
}
