package dev.hindsight.runtime;

/**
 * How much of a value the recorder is allowed to render.
 *
 * <p>{@link #SUMMARY} is the default because a debugger that shows no values is a debugger people
 * conclude is broken. {@link #TYPE} exists because {@code toString} is application code: it can be
 * slow, and it can have side effects that a lazily-initialised object would rather you did not
 * trigger from a diagnostic tool.
 */
public enum ValueDetail {

    /** Type, identity, and a bounded rendering of the value. */
    SUMMARY,

    /** Type names only. Nothing belonging to the application is ever called. */
    TYPE;

    /**
     * @param configured the raw property value, or {@code null} if it was never set
     * @param fallback   returned when nothing was configured
     * @return the matching mode, or {@code null} if something was configured and it was not a mode,
     *         which the caller reports rather than silently treating as a default
     */
    public static ValueDetail parse(String configured, ValueDetail fallback) {
        if (configured == null) {
            return fallback;
        }
        for (ValueDetail candidate : values()) {
            if (candidate.name().equalsIgnoreCase(configured.strip())) {
                return candidate;
            }
        }
        return null;
    }
}
