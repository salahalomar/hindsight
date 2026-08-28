package dev.hindsight.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import dev.hindsight.runtime.Recorder;
import dev.hindsight.runtime.ValueSummariser;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.function.Consumer;

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

    /**
     * Byte Buddy probes {@code sun.misc.Unsafe} while initialising its class injector, and on
     * JDK 24+ that probe prints a terminal-deprecation warning. An agent has no business writing
     * warnings into the stderr of an application that merely attached it, and we never inject
     * classes anyway, so the probe is switched off before Byte Buddy is touched.
     *
     * <p>The literal matters. Shade rewrites string constants when it relocates a package, so both
     * this name and Byte Buddy's own copy of it become {@code dev.hindsight.shaded.bytebuddy.safe}
     * in the packaged jar. The setting therefore reaches only the agent's private copy of the
     * library; a host application's own Byte Buddy still reads {@code net.bytebuddy.safe} and is
     * left exactly as it was found.
     */
    private static final String BYTE_BUDDY_SAFE_MODE = "net.bytebuddy.safe";

    private Hindsight() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            // Before anything reaches Byte Buddy, including any static initialiser of this class.
            useSafeByteBuddyMode();

            ClassCounter counter = ClassCounter.installOn(instrumentation);
            Runtime.getRuntime().addShutdownHook(new SummaryHook(counter));

            AgentConfig config = AgentConfig.fromSystemProperties(new Warning());
            log("agent loaded - " + version() + " on JVM " + System.getProperty("java.version"));
            log(config.describe());

            if (config.scope().isEmpty()) {
                // Attaching and instrumenting nothing is a legitimate state, but a silent one is
                // indistinguishable from a broken agent, so it is said out loud.
                log("no packages selected, recording nothing."
                        + " Set -D" + AgentConfig.PACKAGES + "=com.example to record something");
                return;
            }

            Recorder.configure(config.bufferEvents(), config.maxDepth(), config.dump(),
                    new ValueSummariser(config.valueDetail(), config.valueLength()));
            installTracing(instrumentation, config.scope());
        } catch (Throwable cause) {
            disable(cause);
        }
    }

    private static void useSafeByteBuddyMode() {
        if (System.getProperty(BYTE_BUDDY_SAFE_MODE) == null) {
            System.setProperty(BYTE_BUDDY_SAFE_MODE, "true");
        }
    }

    private static void installTracing(Instrumentation instrumentation, PackageScope scope) {
        new AgentBuilder.Default()
                // Restricts instrumentation to changes that do not alter the class schema. Advice
                // is inlined into existing method bodies and adds no members, so this costs
                // nothing and rules out a whole category of way of breaking a running application.
                .disableClassFormatChanges()
                // Instrument on load only. Retransforming what is already loaded is a much larger
                // promise than step 2 is in a position to keep.
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                // Silent unless a transformation fails. An agent that quietly instruments nothing
                // is the single worst failure mode available to it.
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                // The exclusion list is a floor and is applied first. A scope of "java" does not
                // buy anyone an instrumented java.lang.String.
                .ignore(ElementMatchers.<TypeDescription>isSynthetic().or(new ExcludedTypes()))
                .type(new ScopedTypes(scope))
                .transform(new TraceTransformer(tracedMethods()))
                .installOn(instrumentation);
    }

    /**
     * Built on demand rather than held in a static field. A static field of a Byte Buddy type would
     * be initialised during this class's own initialisation -- which happens before {@link #premain}
     * runs, and therefore before safe mode could be set.
     *
     * <p>{@code isMethod} excludes constructors and static initialisers. Constructor advice is
     * workable -- we read arguments and never {@code this}, which is the part that is unavailable
     * before initialisation -- but it doubles the instrumentation surface, and step 3 is about
     * choosing what to record rather than widening what can be recorded. Abstract and native
     * methods have no body to splice into, and synthetic ones are compiler bookkeeping that would
     * show up in a trace as frames nobody wrote.
     */
    private static ElementMatcher.Junction<MethodDescription> tracedMethods() {
        return ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()));
    }

    /** Restricts instrumentation to the packages the user actually asked for. */
    private static final class ScopedTypes implements ElementMatcher<TypeDescription> {

        private final PackageScope scope;

        private ScopedTypes(PackageScope scope) {
            this.scope = scope;
        }

        @Override
        public boolean matches(TypeDescription target) {
            return target != null && scope.includes(target.getName());
        }
    }

    /**
     * A named class rather than a method reference, for the same reason as {@link SummaryHook}:
     * this runs during agent startup, and an invokedynamic call site bootstrapped there is one more
     * moving part in the fragile part of the lifecycle.
     */
    private static final class Warning implements Consumer<String> {

        @Override
        public void accept(String message) {
            log("ignoring " + message);
        }
    }

    /** Bridges the agent's own exclusion list into Byte Buddy's matcher vocabulary. */
    private static final class ExcludedTypes implements ElementMatcher<TypeDescription> {

        @Override
        public boolean matches(TypeDescription target) {
            return target == null || Exclusions.isExcludedType(target.getName());
        }
    }

    private static final class TraceTransformer implements AgentBuilder.Transformer {

        private final ElementMatcher.Junction<MethodDescription> methods;

        private TraceTransformer(ElementMatcher.Junction<MethodDescription> methods) {
            this.methods = methods;
        }

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                TypeDescription type,
                                                ClassLoader classLoader,
                                                JavaModule module,
                                                ProtectionDomain protectionDomain) {
            return builder.visit(Advice.to(MethodTraceAdvice.class).on(methods));
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
