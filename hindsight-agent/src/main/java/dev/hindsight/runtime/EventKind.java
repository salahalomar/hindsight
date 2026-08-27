package dev.hindsight.runtime;

/**
 * What happened at a method boundary.
 *
 * <p>A return and a throw are separate kinds rather than one exit with a flag. The viewer has to
 * colour them differently, step 5 has to find the throwing frame, and collapsing them into one kind
 * only to reconstruct the difference later is the sort of saving that costs more than it saves.
 */
public enum EventKind {

    ENTER,
    RETURN,
    THROW;

    /** {@code values()} clones on every call, and this is read once per event when rendering. */
    private static final EventKind[] BY_CODE = values();

    /**
     * Stored in the buffer as a byte. The encoding never leaves memory in this step, so declaration
     * order is safe to change until step 5 pins a schema to it.
     */
    public byte code() {
        return (byte) ordinal();
    }

    public static EventKind of(byte code) {
        return BY_CODE[code];
    }
}
