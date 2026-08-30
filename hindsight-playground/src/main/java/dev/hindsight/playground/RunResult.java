package dev.hindsight.playground;

import java.util.List;

/**
 * What happened to a snippet. Four outcomes, kept distinct because the caller shows each one
 * differently and collapsing them into a single record with mostly-null fields would push that
 * distinction into every caller instead.
 */
public sealed interface RunResult {

    /** The snippet was never going to run: no class, no main method, nothing pasted. */
    record Rejected(String problem) implements RunResult {
    }

    /** The compiler had something to say, with lines numbered against what the author wrote. */
    record DidNotCompile(List<String> errors) implements RunResult {
    }

    /**
     * It ran.
     *
     * @param output    what the snippet itself printed, with the agent's own commentary removed
     * @param recording the call tree as the agent rendered it, or empty if nothing was recorded
     * @param trace     the trace document, or {@code null} when nothing failed. That is not an
     *                  error: a trace is written when an exception escapes, and this did not throw.
     */
    record Ran(int exitCode, String output, String recording, String trace) implements RunResult {

        public boolean recorded() {
            return trace != null;
        }
    }

    /** The run could not be attempted or could not be finished: no agent, no compiler, a timeout. */
    record Failed(String problem) implements RunResult {
    }
}
