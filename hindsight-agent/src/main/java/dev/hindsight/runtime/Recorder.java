package dev.hindsight.runtime;

/**
 * The sink that instrumented methods call into.
 *
 * <p>Byte Buddy inlines advice bodies into the target method, so these methods are invoked from
 * application code, on the application's class loader, on whatever thread the application happens
 * to be using. Two consequences shape everything here. Nothing may throw: an exception leaving this
 * class surfaces inside a method that has no idea it was instrumented. And nothing may be slow:
 * this runs twice per invocation of every traced method.
 *
 * <p>Values are reduced to their type and the reference is dropped before returning. Nothing that
 * belongs to the application is retained past the call that reported it -- holding those references
 * would leak, and would change the collection behaviour of the program being diagnosed.
 *
 * <p>Step 2 prints types only. Reading the values themselves means calling {@code toString} on
 * application objects, which can be slow, can throw, and can have side effects; that needs the
 * guarded summariser in step 4 rather than an unguarded call here.
 */
public final class Recorder {

    private static final String PREFIX = "[hindsight] ";
    private static final Object[] NO_ARGUMENTS = new Object[0];
    private static final String VOID = "void";

    private Recorder() {
    }

    public static void onEnter(String type, String method, Object[] arguments) {
        if (!Reentrancy.acquire()) {
            return;
        }
        try {
            System.out.println(entryLine(Thread.currentThread().getName(), type, method, arguments));
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
            System.out.println(exitLine(Thread.currentThread().getName(), type, method, returnType, returned, thrown));
        } catch (Throwable ignored) {
            // As above.
        } finally {
            Reentrancy.release();
        }
    }

    static String entryLine(String thread, String type, String method, Object[] arguments) {
        StringBuilder line = frame(thread, "->", type, method).append('(');
        Object[] present = arguments != null ? arguments : NO_ARGUMENTS;
        for (int i = 0; i < present.length; i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(typeOf(present[i]));
        }
        return line.append(')').toString();
    }

    static String exitLine(String thread, String type, String method, String returnType,
                           Object returned, Throwable thrown) {
        StringBuilder line = frame(thread, "<-", type, method);
        if (thrown != null) {
            return line.append(" threw ").append(typeOf(thrown)).toString();
        }
        if (VOID.equals(returnType)) {
            return line.append(" returned void").toString();
        }
        return line.append(" returned ").append(typeOf(returned)).toString();
    }

    private static StringBuilder frame(String thread, String arrow, String type, String method) {
        return new StringBuilder(PREFIX)
                .append('[').append(thread).append("] ")
                .append(arrow).append(' ')
                .append(type).append('.').append(method);
    }

    /**
     * The type of a value, never the value. {@code getClass} is safe to call on anything; it runs
     * no application code and cannot be overridden.
     */
    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> actual = value.getClass();
        String simple = actual.getSimpleName();
        // Anonymous classes have no simple name. Reporting an empty string as a type helps nobody.
        return simple.isEmpty() ? actual.getName() : simple;
    }
}
