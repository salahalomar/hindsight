package dev.hindsight.trace;

import java.time.Instant;

/**
 * What a trace needs to say about itself before the first event.
 *
 * @param agentVersion which build of the agent produced this, since the schema outlives any one of them
 * @param threadName   the thread whose history this is; a trace is never a view across threads
 * @param entryType    the outermost instrumented type, recorded rather than derived from event zero,
 *                     which is not the entry point once the ring has dropped anything
 * @param entryMethod  the outermost instrumented method
 * @param recordedAt   wall clock, and the only absolute time in the document
 */
public record TraceHeader(String agentVersion,
                          String threadName,
                          String entryType,
                          String entryMethod,
                          Instant recordedAt) {
}
