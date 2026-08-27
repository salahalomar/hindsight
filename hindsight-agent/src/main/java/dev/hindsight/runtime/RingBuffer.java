package dev.hindsight.runtime;

/**
 * One thread's recent method boundaries, bounded, oldest overwritten.
 *
 * <p>Held as parallel arrays rather than an array of event objects. An object per event would mean
 * two allocations for every traced invocation, which is precisely the sort of pressure that changes
 * the collection behaviour of the program being diagnosed; the arrays allocate once and then cost
 * nothing.
 *
 * <p>Every slot is a {@code String}, {@code int}, {@code long} or {@code byte}. Nothing belonging to
 * the application can be stored here even by accident, which makes "never retain a captured object"
 * a property of the type rather than a rule somebody has to remember. Values are reduced to text at
 * capture time, by the caller, before they ever reach this class.
 *
 * <p>Not thread safe, and deliberately so: an instance belongs to exactly one thread. The write
 * methods are public only because the rendering layer lives in another package and its tests have
 * to be able to build a populated buffer; nothing outside {@link Recorder} writes to one.
 */
public final class RingBuffer {

    private final int capacity;
    private final int mask;
    private final int maxDepth;

    private final byte[] kinds;
    private final String[] types;
    private final String[] methods;
    private final String[] details;
    private final int[] depths;
    private final long[] nanos;

    /** Live events, never above {@link #capacity}. */
    private int size;

    /** Total ever written, which is what makes the ring's oldest slot findable. */
    private long written;

    private int depth;
    private long beyondMaxDepth;

    /**
     * @param capacity number of events, must be a power of two so the ring indexes by masking
     * @param maxDepth call depth past which events are counted but not stored
     */
    public RingBuffer(int capacity, int maxDepth) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a positive power of two: " + capacity);
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1: " + maxDepth);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.maxDepth = maxDepth;
        this.kinds = new byte[capacity];
        this.types = new String[capacity];
        this.methods = new String[capacity];
        this.details = new String[capacity];
        this.depths = new int[capacity];
        this.nanos = new long[capacity];
    }

    public void recordEnter(String type, String method, String argumentTypes) {
        append(EventKind.ENTER, type, method, argumentTypes);
        depth++;
    }

    public void recordReturn(String type, String method, String returnedType) {
        leaveFrame();
        append(EventKind.RETURN, type, method, returnedType);
    }

    public void recordThrow(String type, String method, String thrownType) {
        leaveFrame();
        append(EventKind.THROW, type, method, thrownType);
    }

    /**
     * Clamped at zero. A negative depth would mean the agent had lost track of the call stack, and
     * the useful response to that is a slightly wrong trace rather than an exception thrown into an
     * application in the middle of returning from a method.
     */
    private void leaveFrame() {
        depth = Math.max(0, depth - 1);
    }

    private void append(EventKind kind, String type, String method, String detail) {
        // Past the depth limit, entries and exits are suppressed together, so the pairs that do get
        // recorded still match. Runaway recursion costs a counter here, not the whole buffer.
        if (depth >= maxDepth) {
            beyondMaxDepth++;
            return;
        }
        int slot = (int) (written & mask);
        kinds[slot] = kind.code();
        types[slot] = type;
        methods[slot] = method;
        details[slot] = detail;
        depths[slot] = depth;
        nanos[slot] = System.nanoTime();
        written++;
        if (size < capacity) {
            size++;
        }
    }

    /**
     * Empties the buffer without clearing the arrays. The stale references are the agent's own
     * short strings, never application objects, they are bounded by capacity, and they are
     * overwritten as the next request fills the ring. Wiping them would put a pass over the whole
     * buffer on the path that completes every request, to reclaim nothing that matters.
     */
    public void reset() {
        size = 0;
        written = 0;
        depth = 0;
        beyondMaxDepth = 0;
    }

    public int depth() {
        return depth;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Events lost to the ring wrapping, which the reader deserves to be told about. */
    public long dropped() {
        return written - size;
    }

    /** Events suppressed for being deeper than {@code maxDepth}. */
    public long beyondMaxDepth() {
        return beyondMaxDepth;
    }

    public EventKind kindAt(int index) {
        return EventKind.of(kinds[slot(index)]);
    }

    public String typeAt(int index) {
        return types[slot(index)];
    }

    public String methodAt(int index) {
        return methods[slot(index)];
    }

    public String detailAt(int index) {
        return details[slot(index)];
    }

    public int depthAt(int index) {
        return depths[slot(index)];
    }

    public long nanosAt(int index) {
        return nanos[slot(index)];
    }

    /** Index 0 is the oldest event still held, not the oldest ever written. */
    private int slot(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " of " + size);
        }
        return (int) ((written - size + index) & mask);
    }
}
