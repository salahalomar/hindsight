package dev.hindsight.trace;

import dev.hindsight.runtime.RingBuffer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Writes a thread's recorded history to a file when an exception escapes its entry point.
 *
 * <p>The write is synchronous, on the failing thread. That is a deliberate cost: it happens only on
 * a request that has already failed, and the alternatives -- a queue and a writer thread -- trade
 * that latency for the possibility of losing the trace at shutdown, which is exactly when a failing
 * process is most likely to be going away.
 *
 * <p>The number of files is capped. An application failing in a loop would otherwise fill a disk,
 * and an agent that turns a bug into an outage has made things worse than it found them. Once the
 * cap is reached, or once writing has failed, the writer says so once and stops.
 */
public final class TraceWriter {

    private static final int MAX_THREAD_NAME = 40;

    /**
     * Consecutive failures tolerated before giving up. A full disk that is later freed, a directory
     * rotated away underneath us, a momentary NFS stall: none of those are reasons to stay blind
     * for the rest of the process's life, which is what latching on the first failure meant. A run
     * of them is a reason, because by then it is the configuration and not the weather.
     */
    private static final int TOLERATED_FAILURES = 3;

    private final Path directory;
    private final int maxFiles;
    private final String agentVersion;
    private final Consumer<String> log;

    private final AtomicInteger attempted = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public TraceWriter(Path directory, int maxFiles, String agentVersion, Consumer<String> log) {
        this.directory = directory;
        this.maxFiles = maxFiles;
        this.agentVersion = agentVersion;
        this.log = log;
    }

    /**
     * Never throws. A diagnostic tool that cannot write its diagnosis has failed at its job, not at
     * the application's.
     *
     * @return the file written, or {@code null} if nothing was
     */
    public Path write(String threadName, String entryType, String entryMethod, RingBuffer buffer) {
        // maxFiles of zero is how tracing is switched off, and a deliberate setting does not
        // deserve a complaint on every failing request.
        if (maxFiles <= 0 || stopped.get()) {
            return null;
        }
        int sequence = attempted.incrementAndGet();
        if (sequence > maxFiles) {
            stop("reached " + maxFiles + " trace files, writing no more this run"
                    + " (raise -Dhindsight.trace.max to change that)");
            return null;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName(threadName, sequence));
            TraceHeader header = new TraceHeader(
                    agentVersion, threadName, entryType, entryMethod, Instant.now());
            Files.writeString(file, TraceSerialiser.serialise(header, buffer), StandardCharsets.UTF_8);
            consecutiveFailures.set(0);
            log.accept("trace written to " + file);
            return file;
        } catch (Throwable failure) {
            // A read-only working directory is an ordinary deployment, not an exceptional one.
            if (consecutiveFailures.incrementAndGet() >= TOLERATED_FAILURES) {
                stop("cannot write traces to " + directory + " after " + TOLERATED_FAILURES
                        + " attempts, giving up (" + failure + ")");
            } else {
                log.accept("could not write a trace to " + directory + " (" + failure
                        + "); will try again on the next failure");
            }
            return null;
        }
    }

    public String describe() {
        return maxFiles <= 0 ? "off" : directory + " (max " + maxFiles + ")";
    }

    private void stop(String reason) {
        if (stopped.compareAndSet(false, true)) {
            log.accept(reason);
        }
    }

    /** Thread names are arbitrary text and end up in a path here, so they are made safe first. */
    private static String fileName(String threadName, int sequence) {
        return "hindsight-" + Instant.now().toEpochMilli()
                + "-" + safe(threadName)
                + "-" + sequence + ".json";
    }

    private static String safe(String threadName) {
        if (threadName == null || threadName.isBlank()) {
            return "thread";
        }
        int length = Math.min(threadName.length(), MAX_THREAD_NAME);
        StringBuilder safe = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char character = threadName.charAt(i);
            safe.append(Character.isLetterOrDigit(character) || character == '-' || character == '_'
                    ? character
                    : '-');
        }
        return safe.toString();
    }
}
