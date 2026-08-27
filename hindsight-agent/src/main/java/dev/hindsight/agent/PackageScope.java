package dev.hindsight.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * The packages the agent has been asked to record.
 *
 * <p>Selection is opt-in and has no default. An agent that instruments something sensible-looking
 * when nobody told it to is an agent that surprises people in production, so an unconfigured scope
 * matches nothing at all and says so at startup.
 *
 * <p>This chooses what the agent <em>may</em> record. It never overrides {@link Exclusions}, which
 * is a floor: asking for {@code java} does not get you an instrumented {@code java.lang.String}.
 */
public final class PackageScope {

    private static final PackageScope EMPTY = new PackageScope(new String[0]);

    /** Stored with the trailing dot already appended, so matching never concatenates. */
    private final String[] prefixes;

    private PackageScope(String[] prefixes) {
        this.prefixes = prefixes;
    }

    public static PackageScope none() {
        return EMPTY;
    }

    /**
     * @param commaSeparated packages such as {@code com.example,com.acme.orders}, or {@code null}
     */
    public static PackageScope parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return EMPTY;
        }
        List<String> parsed = new ArrayList<>();
        for (String candidate : commaSeparated.split(",")) {
            String prefix = candidate.strip();
            // A trailing dot is how people often write a package prefix; accept it either way
            // rather than silently matching nothing.
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!prefix.isEmpty() && !parsed.contains(prefix + ".")) {
                parsed.add(prefix + ".");
            }
        }
        return parsed.isEmpty() ? EMPTY : new PackageScope(parsed.toArray(new String[0]));
    }

    /**
     * Matches on a package boundary, not on characters. {@code com.example} must not drag in
     * {@code com.exampleother}, which a bare {@code startsWith} would happily do.
     */
    public boolean includes(String binaryClassName) {
        if (binaryClassName == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (binaryClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return prefixes.length == 0;
    }

    /** Renders the configured prefixes back without their matching dots, for the startup banner. */
    public String describe() {
        if (isEmpty()) {
            return "nothing";
        }
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < prefixes.length; i++) {
            if (i > 0) {
                description.append(", ");
            }
            String prefix = prefixes[i];
            description.append(prefix, 0, prefix.length() - 1);
        }
        return description.toString();
    }
}
