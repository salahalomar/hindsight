package dev.hindsight.agent;

/**
 * The classes this agent will never instrument, under any configuration.
 *
 * <p>Two spellings of the same list are needed. {@link java.lang.instrument.ClassFileTransformer}
 * is handed internal names ({@code java/lang/String}); Byte Buddy matches on
 * {@code TypeDescription.getName()}, which is binary ({@code java.lang.String}). Both are checked
 * on the class-loading path, so the second form is derived once rather than converted per call.
 *
 * <p>This is a floor, not a policy. Package-prefix selection of what the agent <em>does</em>
 * record arrives in step 3; whatever that ends up allowing, nothing here is ever instrumented.
 */
public final class Exclusions {

    private static final String[] NEVER_INSTRUMENT = {
            // The platform. The recorder calls into these classes on every single event, so
            // instrumenting them is unbounded recursion by another name, and touching the
            // bootstrap classes the JVM is still starting up on invites deadlock besides.
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun/",

            // The agent itself, including the Byte Buddy shaded into dev/hindsight/shaded/.
            // Deliberately one wholesale prefix rather than an enumeration of the agent's own
            // packages: a list has to be maintained, and the cost of forgetting an entry is
            // the recorder recording itself.
            "dev/hindsight/",

            // An application's own copy of Byte Buddy. Instrumenting the instrumentation
            // library is a reliable way to watch a process eat itself.
            hostByteBuddy(),
    };

    private static final String[] NEVER_INSTRUMENT_BINARY = asBinaryNames(NEVER_INSTRUMENT);

    private Exclusions() {
    }

    /**
     * @param internalClassName the class name in internal form, or {@code null} for a hidden class
     * @return {@code true} if the agent must leave this class alone
     */
    public static boolean isExcluded(String internalClassName) {
        // Hidden classes -- lambda bodies, and the proxies Byte Buddy defines at runtime -- are
        // passed with a null name. They have no stable identity to record a trace against.
        return hasExcludedPrefix(internalClassName, NEVER_INSTRUMENT);
    }

    /**
     * @param binaryClassName the class name in binary form, or {@code null}
     * @return {@code true} if the agent must leave this class alone
     */
    public static boolean isExcludedType(String binaryClassName) {
        return hasExcludedPrefix(binaryClassName, NEVER_INSTRUMENT_BINARY);
    }

    private static boolean hasExcludedPrefix(String className, String[] prefixes) {
        if (className == null) {
            return true;
        }
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assembled from parts rather than written as a literal.
     *
     * <p>The shade plugin rewrites string constants along with type references when it relocates a
     * package, so the literal {@code "net/bytebuddy/"} would be rewritten to the agent's own shaded
     * package during packaging. The rule meant to protect the <em>host application's</em> Byte
     * Buddy would silently become a duplicate of the rule above it, and the class this exists to
     * protect would be instrumented. Neither half matches the relocation pattern on its own.
     */
    private static String hostByteBuddy() {
        return String.join("/", "net", "bytebuddy") + "/";
    }

    private static String[] asBinaryNames(String[] internalPrefixes) {
        String[] binary = new String[internalPrefixes.length];
        for (int i = 0; i < internalPrefixes.length; i++) {
            binary[i] = internalPrefixes[i].replace('/', '.');
        }
        return binary;
    }
}
