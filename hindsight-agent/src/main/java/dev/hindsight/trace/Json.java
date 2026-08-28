package dev.hindsight.trace;

import java.util.Locale;

/**
 * The smallest JSON writer that can produce this project's trace files.
 *
 * <p>Hand-written rather than brought in. A serialisation library would be relocated into the agent
 * jar alongside Byte Buddy, adding megabytes and a second collision surface with whatever the host
 * application already ships, to emit a document whose shape is fixed and known at compile time.
 */
final class Json {

    private Json() {
    }

    static StringBuilder quoted(StringBuilder out, String value) {
        out.append('"');
        escape(out, value);
        return out.append('"');
    }

    static StringBuilder member(StringBuilder out, String name, String value) {
        quoted(out, name).append(':');
        return quoted(out, value);
    }

    static StringBuilder member(StringBuilder out, String name, long value) {
        return quoted(out, name).append(':').append(value);
    }

    /**
     * Escapes for JSON, on top of whatever escaping the summariser already did for display. The
     * stored detail is a rendering meant to be read, so a quote inside it is data here, not
     * structure.
     */
    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
    }
}
