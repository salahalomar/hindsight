package dev.hindsight.agent;

/**
 * The classes this agent will never instrument, under any configuration.
 *
 * <p>Names arrive in JVM internal form ({@code java/lang/String}), which is what
 * {@link java.lang.instrument.ClassFileTransformer} is handed. Matching on the raw string keeps
 * this check on the class-loading hot path free of allocation and reflection.
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
            "net/bytebuddy/",
    };

    private Exclusions() {
    }

    /**
     * @param internalClassName the class name in internal form, or {@code null} for a hidden class
     * @return {@code true} if the agent must leave this class alone
     */
    public static boolean isExcluded(String internalClassName) {
        // Hidden classes -- lambda bodies, and the proxies Byte Buddy defines at runtime -- are
        // passed with a null name. They have no stable identity to record a trace against.
        if (internalClassName == null) {
            return true;
        }
        for (String prefix : NEVER_INSTRUMENT) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
