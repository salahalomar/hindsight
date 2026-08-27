package dev.hindsight.agent;

import java.lang.instrument.Instrumentation;

/**
 * Agent entry point, reached through the {@code Premain-Class} manifest attribute when the JVM is
 * started with {@code -javaagent:hindsight-agent.jar}.
 *
 * <p>Configuration is read from {@code -Dhindsight.*} system properties. The {@code agentArgs}
 * string is deliberately ignored: two configuration paths that can disagree with each other is one
 * more than this project needs.
 *
 * <p>Nothing here, or anything reached from here, may throw into the host application. An agent
 * that takes down the process it was meant to diagnose is worse than no agent at all, so the entry
 * point from the JVM is wrapped and any failure degrades to a message on stderr.
 */
public final class Hindsight {

    private static final String PREFIX = "[hindsight] ";

    /** Reported when the classes are loaded from a directory rather than the packaged jar. */
    private static final String UNPACKAGED = "(unpackaged)";

    private Hindsight() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            ClassCounter counter = ClassCounter.installOn(instrumentation);
            Runtime.getRuntime().addShutdownHook(new SummaryHook(counter));
            log("agent loaded - " + version() + " on JVM " + System.getProperty("java.version"));
        } catch (Throwable cause) {
            disable(cause);
        }
    }

    /**
     * A named class rather than a lambda. Agent startup runs before the application has loaded
     * anything, and an invokedynamic call site bootstrapped at that point is one more moving part
     * in the fragile part of the lifecycle for no readability gained.
     */
    private static final class SummaryHook extends Thread {

        private final ClassCounter counter;

        private SummaryHook(ClassCounter counter) {
            super("hindsight-summary");
            this.counter = counter;
        }

        @Override
        public void run() {
            try {
                log(counter.summary());
            } catch (Throwable ignored) {
                // Shutdown is no time to start complaining.
            }
        }
    }

    /** Reads {@code Implementation-Version} from the agent jar manifest. */
    private static String version() {
        String version = Hindsight.class.getPackage().getImplementationVersion();
        return version != null ? version : UNPACKAGED;
    }

    private static void log(String message) {
        System.out.println(PREFIX + message);
    }

    private static void disable(Throwable cause) {
        System.err.println(PREFIX + "disabled: the agent failed to start (" + cause + ")");
    }
}
