package dev.hindsight.playground;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs one Java file under the agent and prints what was recorded.
 *
 * <p>The point of this over the {@code -javaagent} flag itself is only convenience: it finds the
 * agent, puts the snippet in a package the agent will look at, compiles it and picks the trace back
 * up. Attaching the agent to a real application is still one flag and needs none of this.
 */
public final class PlaygroundCli {

    private static final String USAGE = """
            usage: hindsight-run <File.java> [--agent <path>] [--timeout <seconds>] [--json]

              Compiles the file, runs it with the agent attached, and prints the recorded call tree.
              A trace is written only if an exception escapes, which is when there is something to
              look at. --json prints the trace document instead, for piping into the viewer.
            """;

    private PlaygroundCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            System.exit(args.length == 0 ? 2 : 0);
        }

        Path file = Path.of(args[0]);
        Path agent = null;
        Duration timeout = Duration.ofSeconds(15);
        boolean asJson = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--agent" -> agent = Path.of(argument(args, ++i, "--agent"));
                case "--timeout" -> timeout = Duration.ofSeconds(Long.parseLong(argument(args, ++i, "--timeout")));
                case "--json" -> asJson = true;
                default -> fail("unknown argument: " + args[i]);
            }
        }
        if (!Files.isRegularFile(file)) {
            fail("no such file: " + file);
        }
        if (agent == null) {
            agent = defaultAgent();
        }

        RunResult result = new SnippetRunner(agent, timeout)
                .run(Files.readString(file, StandardCharsets.UTF_8));
        System.exit(report(result, asJson));
    }

    private static int report(RunResult result, boolean asJson) {
        switch (result) {
            case RunResult.Rejected rejected -> {
                System.err.println(rejected.problem());
                return 2;
            }
            case RunResult.Failed failed -> {
                System.err.println(failed.problem());
                return 3;
            }
            case RunResult.DidNotCompile didNotCompile -> {
                System.err.println("The snippet did not compile:");
                didNotCompile.errors().forEach(error -> System.err.println("  " + error));
                return 1;
            }
            case RunResult.Ran ran -> {
                if (asJson) {
                    if (!ran.recorded()) {
                        System.err.println("Nothing failed, so no trace was written.");
                        return 1;
                    }
                    System.out.print(ran.trace());
                    return 0;
                }
                if (!ran.output().isEmpty()) {
                    System.out.println(ran.output());
                }
                if (!ran.recording().isEmpty()) {
                    System.out.println(ran.recording());
                }
                if (!ran.recorded()) {
                    System.out.println("Nothing failed, so no trace file was written. A recording is "
                            + "kept when an exception escapes; until then there is nothing to keep.");
                }
                return ran.exitCode();
            }
        }
    }

    /** The agent as built by this repository, relative to wherever the command was run from. */
    private static Path defaultAgent() {
        for (String candidate : List.of(
                "hindsight-agent/target/hindsight-agent.jar",
                "../hindsight-agent/target/hindsight-agent.jar")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return Path.of("hindsight-agent/target/hindsight-agent.jar");
    }

    private static String argument(String[] args, int index, String flag) {
        if (index >= args.length) {
            fail(flag + " needs a value");
        }
        return args[index];
    }

    private static void fail(String message) {
        System.err.println(message);
        System.err.print(USAGE);
        System.exit(2);
    }
}
