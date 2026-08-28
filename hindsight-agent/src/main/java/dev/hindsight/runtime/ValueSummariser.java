package dev.hindsight.runtime;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an application value into a bounded string, then forgets it.
 *
 * <p>This is the one place in the agent that calls application code, and everything about it is
 * built around that. {@code toString} can be slow, can throw, can recurse forever, and can have
 * side effects that the application never expected a diagnostic tool to trigger.
 *
 * <p>The defences, in the order they matter:
 *
 * <ul>
 *   <li><b>Containers are never rendered through {@code toString}.</b> A collection with a million
 *       elements builds the entire string before any length cap could apply, so collections, maps
 *       and arrays are described structurally instead. Truncating afterwards is too late to help.
 *   <li><b>Types with a known-safe rendering skip {@code toString} entirely.</b> Boxed primitives
 *       are matched by exact class, not by {@code instanceof Number}, so a user-written
 *       {@code Number} subclass does not slip through as safe. Enum constants are read with
 *       {@code name()}, which is final, rather than an overridable {@code toString}.
 *   <li><b>Classes that do not override {@code toString} are not asked.</b> The inherited
 *       implementation only repeats the identity that is already being printed.
 *   <li><b>Anything that does get called is wrapped.</b> A throwing {@code toString} is reported as
 *       having thrown, which is itself worth knowing, and mutual recursion between two objects
 *       ends in a caught {@link StackOverflowError} rather than a dead thread.
 *   <li><b>Output is capped</b>, and a value that was cut says how long it really was.
 * </ul>
 *
 * <p>What is deliberately <em>not</em> defended against is a {@code toString} that is merely slow.
 * Bounding that needs a watchdog thread and the ability to interrupt application code mid-call,
 * which is far more dangerous than the problem. {@link ValueDetail#TYPE} is the answer there.
 *
 * <p>Nothing here retains what it was given. A summary is text, and the reference is gone by the
 * time this returns.
 */
public final class ValueSummariser {

    private static final String NULL = "null";
    private static final String ELLIPSIS = "...";

    /**
     * Whether a class bothers to override {@code toString}, cached per class. {@link ClassValue}
     * exists for exactly this: the reflection happens once, and the lookup afterwards is one of the
     * cheapest things the JVM offers on a path that runs for every argument of every call.
     */
    private static final ClassValue<Boolean> OVERRIDES_TO_STRING = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("toString").getDeclaringClass() != Object.class;
            } catch (Throwable unreadable) {
                // If the class will not describe itself, do not go on to call into it.
                return Boolean.FALSE;
            }
        }
    };

    private final ValueDetail detail;
    private final int maxLength;

    public ValueSummariser(ValueDetail detail, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be at least 1: " + maxLength);
        }
        this.detail = detail;
        this.maxLength = maxLength;
    }

    /** Renders one value. Never throws, never retains, never returns null. */
    public String summarise(Object value) {
        try {
            return render(value);
        } catch (Throwable hostile) {
            // Reached only if the defences below are themselves defeated. A useless summary beats
            // an exception surfacing inside a method that has no idea it was instrumented.
            return "<unsummarisable>";
        }
    }

    /** Renders a thrown exception, where the message is the part worth having. */
    public String describe(Throwable thrown) {
        if (thrown == null) {
            return NULL;
        }
        String type = simpleNameOf(thrown.getClass());
        if (detail == ValueDetail.TYPE) {
            return type;
        }
        String message;
        try {
            message = thrown.getMessage();
        } catch (Throwable hostile) {
            // getMessage is overridable, and an exception whose message throws is exactly the sort
            // of thing worth reporting rather than tripping over.
            return type + ": " + threw(hostile);
        }
        return message == null ? type : type + ": " + quote(message);
    }

    private String render(Object value) {
        if (value == null) {
            return NULL;
        }
        Class<?> type = value.getClass();

        if (detail == ValueDetail.TYPE) {
            return simpleNameOf(type);
        }
        if (value instanceof String text) {
            return "String " + quote(text);
        }
        if (isSafeScalar(type)) {
            return simpleNameOf(type) + " " + (value instanceof Character character
                    ? "'" + character + "'"
                    : value);
        }
        if (value instanceof Enum<?> constant) {
            // getDeclaringClass, not getClass: a constant with a body is an anonymous subclass
            // whose simple name is empty. name() is final where toString is not.
            return constant.getDeclaringClass().getSimpleName() + "." + constant.name();
        }
        if (type.isArray()) {
            return simpleNameOf(type.getComponentType()) + "[" + Array.getLength(value) + "]"
                    + identity(value);
        }
        if (value instanceof Map<?, ?> map) {
            return simpleNameOf(type) + "{" + mapSize(map) + "}" + identity(value);
        }
        if (value instanceof Collection<?> collection) {
            return simpleNameOf(type) + "[" + collectionSize(collection) + "]" + identity(value);
        }
        if (value instanceof Throwable thrown) {
            return describe(thrown);
        }
        return object(value, type);
    }

    private String object(Object value, Class<?> type) {
        String head = simpleNameOf(type) + identity(value);
        if (!OVERRIDES_TO_STRING.get(type)) {
            // The inherited toString would only repeat the identity already printed.
            return head;
        }
        String rendered;
        try {
            rendered = value.toString();
        } catch (Throwable hostile) {
            // Includes StackOverflowError, which is how mutually recursive toString implementations
            // announce themselves. An object with a broken toString is worth knowing about.
            return head + " " + threw(hostile);
        }
        return rendered == null ? head + " <toString returned null>" : head + " " + quote(rendered);
    }

    /*
     * size() is application code on a user-written collection, so it is guarded like toString.
     * Written out twice rather than shared behind a functional interface: a method reference here
     * would capture the container and allocate, on a path that runs for every argument of every
     * traced call.
     */

    private static String mapSize(Map<?, ?> map) {
        try {
            return String.valueOf(map.size());
        } catch (Throwable hostile) {
            return "?";
        }
    }

    private static String collectionSize(Collection<?> collection) {
        try {
            return String.valueOf(collection.size());
        } catch (Throwable hostile) {
            return "?";
        }
    }

    private static String threw(Throwable hostile) {
        // Only the class name: asking a hostile object a second question is not a plan.
        return "<toString threw " + hostile.getClass().getSimpleName() + ">";
    }

    private static boolean isSafeScalar(Class<?> type) {
        // Exact classes, not instanceof Number: a user-written Number subclass has a user-written
        // toString, and the whole point of this list is that these renderings cannot be overridden.
        return type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Double.class || type == Float.class
                || type == Boolean.class || type == Character.class;
    }

    /** Distinguishes two instances of the same class across a trace. Runs no application code. */
    private static String identity(Object value) {
        return "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String simpleNameOf(Class<?> type) {
        String simple = type.getSimpleName();
        // Anonymous classes have no simple name. Reporting an empty string as a type helps nobody.
        return simple.isEmpty() ? type.getName() : simple;
    }

    private String quote(String text) {
        if (text.length() <= maxLength) {
            return "\"" + escape(text) + "\"";
        }
        // A cut value says how long it really was, so the truncation cannot be mistaken for the
        // value having ended there.
        return "(" + text.length() + ") \"" + escape(text.substring(0, maxLength)) + ELLIPSIS + "\"";
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    // A raw control character would break the line structure of the trace.
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
