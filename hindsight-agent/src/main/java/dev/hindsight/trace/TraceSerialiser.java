package dev.hindsight.trace;

import dev.hindsight.runtime.EventKind;
import dev.hindsight.runtime.RingBuffer;

import java.time.format.DateTimeFormatter;

/**
 * Renders a recorded buffer as a versioned JSON document.
 *
 * <p>This is the project's first compatibility commitment. Once a viewer reads one of these files
 * the field names are an interface, so the document names its own schema on the first line and a
 * reader that does not recognise the version is expected to refuse rather than guess.
 *
 * <p>Times in the events are offsets from the first recorded event, in nanoseconds. Absolute
 * {@code nanoTime} readings mean nothing outside the process that took them; the one wall-clock
 * anchor in the document is {@code recordedAt}.
 *
 * <p>One event per line, compact. A trace is something a person may well open in an editor before
 * any viewer gets involved, and line-per-event is both readable and greppable without doubling the
 * size of a thousand-event document.
 */
public final class TraceSerialiser {

    /**
     * Bump the number for any change a reader could misinterpret: a renamed or removed field, or a
     * changed meaning. Adding an optional field that older readers can ignore does not need it.
     */
    public static final String SCHEMA = "hindsight.trace/1";

    private TraceSerialiser() {
    }

    public static String serialise(TraceHeader header, RingBuffer buffer) {
        StringBuilder out = new StringBuilder(256 + buffer.size() * 128);
        out.append("{\n  ");
        Json.member(out, "schema", SCHEMA).append(",\n  ");
        Json.member(out, "agent", header.agentVersion()).append(",\n  ");
        Json.member(out, "recordedAt", DateTimeFormatter.ISO_INSTANT.format(header.recordedAt())).append(",\n  ");
        Json.member(out, "thread", header.threadName()).append(",\n  ");

        Json.quoted(out, "entryPoint").append(":{");
        Json.member(out, "type", header.entryType()).append(',');
        Json.member(out, "method", header.entryMethod()).append("},\n  ");

        // A truncated trace that does not say so reads as a complete account of what happened.
        Json.quoted(out, "truncation").append(":{");
        Json.member(out, "droppedToRing", buffer.dropped()).append(',');
        Json.member(out, "beyondMaxDepth", buffer.beyondMaxDepth()).append("},\n  ");

        Json.quoted(out, "events").append(":[");
        long origin = buffer.size() > 0 ? buffer.nanosAt(0) : 0L;
        for (int index = 0; index < buffer.size(); index++) {
            out.append(index == 0 ? "\n    " : ",\n    ");
            event(out, buffer, index, origin);
        }
        return out.append(buffer.size() > 0 ? "\n  ]\n}\n" : "]\n}\n").toString();
    }

    private static void event(StringBuilder out, RingBuffer buffer, int index, long origin) {
        EventKind kind = buffer.kindAt(index);
        out.append('{');
        Json.member(out, "seq", index).append(',');
        Json.member(out, "kind", kind.name().toLowerCase(java.util.Locale.ROOT)).append(',');
        Json.member(out, "depth", buffer.depthAt(index)).append(',');
        Json.member(out, "offsetNanos", buffer.nanosAt(index) - origin).append(',');
        Json.member(out, "type", buffer.typeAt(index)).append(',');
        Json.member(out, "method", buffer.methodAt(index)).append(',');
        // Named for what it is rather than a single "detail" field, so a reader never has to
        // consult the kind to know what it is looking at.
        Json.member(out, switch (kind) {
            case ENTER -> "arguments";
            case RETURN -> "returned";
            case THROW -> "thrown";
        }, buffer.detailAt(index));
        out.append('}');
    }
}
