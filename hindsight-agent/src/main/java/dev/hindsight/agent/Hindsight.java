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
 * <p>Nothing in this class, or anything it calls, may throw into the host application. An agent
 * that takes down the process it was meant to diagnose is worse than no agent at all, so every
 * entry point from the JVM is wrapped and failures degrade to a message on stderr.
 */
public final class Hindsight {

    private static final String PREFIX = "[hindsight] ";

    /** Reported when the classes are loaded from a directory rather than the packaged jar. */
    private static final String UNPACKAGED = "(unpackaged)";

    private Hindsight() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            System.out.println(PREFIX + "agent loaded - " + version()
                    + " on JVM " + System.getProperty("java.version"));
        } catch (Throwable t) {
            disable(t);
        }
    }

    /** Reads {@code Implementation-Version} from the agent jar manifest. */
    private static String version() {
        String version = Hindsight.class.getPackage().getImplementationVersion();
        return version != null ? version : UNPACKAGED;
    }

    private static void disable(Throwable cause) {
        System.err.println(PREFIX + "disabled: the agent failed to start (" + cause + ")");
    }
}
