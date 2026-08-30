package dev.hindsight.agent;

import dev.hindsight.runtime.ValueDetail;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Everything the agent reads from {@code -Dhindsight.*} at startup.
 *
 * <p>Parsed once, in {@code premain}, and handed to the runtime as plain values. Nothing on the
 * recording path ever reads a system property: property lookups are synchronised map reads, and
 * this path runs twice per traced invocation.
 *
 * <p>A bad value is reported and replaced with the default rather than failing the attach. Refusing
 * to start because a buffer size was mistyped would take down an application that was running fine
 * a moment ago.
 */
public record AgentConfig(PackageScope scope, int bufferEvents, int maxDepth, boolean dump,
                          ValueDetail valueDetail, int valueLength,
                          Path traceDirectory, int traceMax, boolean debug) {

    public static final String PACKAGES = "hindsight.packages";
    public static final String BUFFER_EVENTS = "hindsight.buffer.events";
    public static final String MAX_DEPTH = "hindsight.depth.max";
    public static final String DUMP = "hindsight.dump";
    public static final String VALUES = "hindsight.values";
    public static final String VALUE_LENGTH = "hindsight.value.length";
    public static final String TRACE_DIR = "hindsight.trace.dir";
    public static final String TRACE_MAX = "hindsight.trace.max";
    public static final String DEBUG = "hindsight.debug";

    static final int DEFAULT_BUFFER_EVENTS = 1024;
    static final int DEFAULT_MAX_DEPTH = 256;
    static final ValueDetail DEFAULT_VALUE_DETAIL = ValueDetail.SUMMARY;
    static final int DEFAULT_VALUE_LENGTH = 64;

    /** Long enough to be worth reading, short enough that one argument cannot dominate a trace. */
    static final int MIN_VALUE_LENGTH = 8;
    static final int MAX_VALUE_LENGTH = 4096;
    static final String DEFAULT_TRACE_DIR = "hindsight-traces";

    /** Enough to diagnose a failure, few enough that a crash loop cannot fill a disk. Zero is off. */
    static final int DEFAULT_TRACE_MAX = 50;

    /** Below this a buffer cannot hold a useful call tree; above it, one thread can cost megabytes. */
    static final int MIN_BUFFER_EVENTS = 16;

    /**
     * 65,536 events is roughly 2.4MB per traced thread and a trace file of about ten megabytes,
     * which is the most the viewer can be expected to open. The previous ceiling of 1 &lt;&lt; 20
     * permitted a configuration whose output nothing in this project could read.
     */
    static final int MAX_BUFFER_EVENTS = 1 << 16;

    /**
     * Deeper than any real call stack -- the JVM itself overflows well before this -- and bounded
     * so that a mistyped value cannot make the renderer build a multi-megabyte indent for one line.
     * Every other setting here has a ceiling; this one was the exception, which was the bug.
     */
    static final int MAX_MAX_DEPTH = 4096;

    public static AgentConfig fromSystemProperties(Consumer<String> warnings) {
        return from(System.getProperties(), warnings);
    }

    static AgentConfig from(Properties properties, Consumer<String> warnings) {
        return new AgentConfig(
                PackageScope.parse(properties.getProperty(PACKAGES)),
                toPowerOfTwo(bounded(properties, BUFFER_EVENTS, DEFAULT_BUFFER_EVENTS,
                        MIN_BUFFER_EVENTS, MAX_BUFFER_EVENTS, warnings)),
                bounded(properties, MAX_DEPTH, DEFAULT_MAX_DEPTH, 1, MAX_MAX_DEPTH, warnings),
                Boolean.parseBoolean(properties.getProperty(DUMP)),
                valueDetail(properties, warnings),
                bounded(properties, VALUE_LENGTH, DEFAULT_VALUE_LENGTH,
                        MIN_VALUE_LENGTH, MAX_VALUE_LENGTH, warnings),
                traceDirectory(properties, warnings),
                bounded(properties, TRACE_MAX, DEFAULT_TRACE_MAX, 0, Integer.MAX_VALUE, warnings),
                Boolean.parseBoolean(properties.getProperty(DEBUG)));
    }

    private static Path traceDirectory(Properties properties, Consumer<String> warnings) {
        String configured = properties.getProperty(TRACE_DIR, DEFAULT_TRACE_DIR);
        try {
            return Path.of(configured);
        } catch (RuntimeException notAPath) {
            // Path.of rejects names the platform cannot represent. Losing traces is better than
            // refusing to attach over it.
            warnings.accept(TRACE_DIR + "=" + configured + " is not a usable path, using "
                    + DEFAULT_TRACE_DIR);
            return Path.of(DEFAULT_TRACE_DIR);
        }
    }

    private static ValueDetail valueDetail(Properties properties, Consumer<String> warnings) {
        String configured = properties.getProperty(VALUES);
        ValueDetail parsed = ValueDetail.parse(configured, DEFAULT_VALUE_DETAIL);
        if (parsed == null) {
            warnings.accept(VALUES + "=" + configured + " is not one of summary or type, using "
                    + DEFAULT_VALUE_DETAIL.name().toLowerCase(java.util.Locale.ROOT));
            return DEFAULT_VALUE_DETAIL;
        }
        return parsed;
    }

    private static int bounded(Properties properties, String key, int fallback,
                               int minimum, int maximum, Consumer<String> warnings) {
        String configured = properties.getProperty(key);
        if (configured == null) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(configured.strip());
        } catch (NumberFormatException notANumber) {
            warnings.accept(key + "=" + configured + " is not a number, using " + fallback);
            return fallback;
        }
        if (value < minimum || value > maximum) {
            int clamped = Math.min(Math.max(value, minimum), maximum);
            warnings.accept(key + "=" + value + " is out of range, using " + clamped);
            return clamped;
        }
        return value;
    }

    /**
     * The ring indexes by masking rather than dividing, which requires a power of two. Rounding up
     * gives the caller at least what they asked for.
     */
    private static int toPowerOfTwo(int requested) {
        return Integer.highestOneBit(requested) == requested
                ? requested
                : Integer.highestOneBit(requested) << 1;
    }

    /** One line for the startup banner, so what the agent will actually do is never a guess. */
    public String describe() {
        return "packages=" + scope().describe()
                + ", buffer=" + bufferEvents() + " events/thread"
                + ", maxDepth=" + maxDepth()
                + ", values=" + valueDetail().name().toLowerCase(java.util.Locale.ROOT)
                + "/" + valueLength()
                + ", dump=" + dump()
                + ", traces=" + (traceMax() <= 0 ? "off" : traceDirectory() + " (max " + traceMax() + ")")
                + (debug() ? ", debug=true" : "");
    }
}
