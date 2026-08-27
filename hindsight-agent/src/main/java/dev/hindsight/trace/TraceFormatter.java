package dev.hindsight.trace;

import dev.hindsight.runtime.EventKind;
import dev.hindsight.runtime.RingBuffer;

import java.util.Locale;

/**
 * Renders a recorded buffer as an indented call tree.
 *
 * <p>Rendering lives here rather than in the recorder on purpose. Capture runs on application
 * threads twice per traced invocation and must stay cheap; this runs once, on demand, and is
 * allowed to be leisurely. Keeping presentation out of the recorder also means step 5 can add a
 * second rendering -- the JSON trace -- without touching the hot path at all.
 */
public final class TraceFormatter {

    private static final String PREFIX = "[hindsight] ";
    private static final String INDENT = "  ";

    private TraceFormatter() {
    }

    public static String render(String threadName, RingBuffer buffer) {
        StringBuilder out = new StringBuilder(header(threadName, buffer));
        long origin = buffer.size() > 0 ? buffer.nanosAt(0) : 0L;
        for (int index = 0; index < buffer.size(); index++) {
            out.append(System.lineSeparator())
                    .append(PREFIX)
                    .append(elapsed(buffer.nanosAt(index) - origin))
                    .append(' ')
                    .append(INDENT.repeat(buffer.depthAt(index)))
                    .append(event(buffer, index));
        }
        return out.toString();
    }

    static String header(String threadName, RingBuffer buffer) {
        StringBuilder header = new StringBuilder(PREFIX)
                .append("trace for ").append(threadName).append(": ")
                .append(buffer.size()).append(buffer.size() == 1 ? " event" : " events");
        // A truncated trace that does not say it was truncated is worse than no trace, because it
        // reads as a complete account of what happened.
        if (buffer.dropped() > 0) {
            header.append(", ").append(buffer.dropped()).append(" dropped to the ring");
        }
        if (buffer.beyondMaxDepth() > 0) {
            header.append(", ").append(buffer.beyondMaxDepth()).append(" beyond max depth");
        }
        return header.toString();
    }

    static String event(RingBuffer buffer, int index) {
        EventKind kind = buffer.kindAt(index);
        String frame = buffer.typeAt(index) + "." + buffer.methodAt(index);
        String detail = buffer.detailAt(index);
        return switch (kind) {
            case ENTER -> "-> " + frame + "(" + detail + ")";
            case RETURN -> "<- " + frame + " returned " + detail;
            case THROW -> "<! " + frame + " threw " + detail;
        };
    }

    /**
     * Pinned to {@link Locale#ROOT}. The default locale would render this as {@code +  0,000ms} on
     * a machine configured for German, and a diagnostic tool that reads differently depending on
     * where it is run is a diagnostic tool nobody trusts.
     */
    static String elapsed(long nanosSinceFirstEvent) {
        return String.format(Locale.ROOT, "+%7.3fms", nanosSinceFirstEvent / 1_000_000.0);
    }
}
