package dev.hindsight.playground;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Compiles a snippet and runs it in a fresh JVM with the agent attached.
 *
 * <p><b>This runs the code it is given.</b> That is what the thing is for, and on the machine you
 * are sitting at it is no different from typing {@code javac} yourself. It becomes something else
 * entirely the moment it is reachable from a network, so the HTTP caller binds to loopback and says
 * so on the page. The command line caller has no such surface: it runs a file you named.
 *
 * <p>A separate JVM rather than this one, for the same reason the agent needs one: instrumentation
 * happens as classes load, so the agent has to be present from the start. It also means a snippet
 * that loops forever, exhausts its heap or calls {@code System.exit} costs a child process rather
 * than the process hosting it.
 */
public final class SnippetRunner {

    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final String AGENT_PREFIX = "[hindsight] ";

    private final Path agentJar;
    private final Duration timeout;

    public SnippetRunner(Path agentJar, Duration timeout) {
        this.agentJar = agentJar;
        this.timeout = timeout;
    }

    public RunResult run(String pasted) {
        if (agentJar == null || !Files.isRegularFile(agentJar)) {
            return new RunResult.Failed("No agent jar at " + agentJar
                    + ". Run ./mvnw package first.");
        }
        Snippet snippet;
        try {
            snippet = Snippet.of(pasted);
        } catch (IllegalArgumentException rejected) {
            return new RunResult.Rejected(rejected.getMessage());
        }

        Path work;
        try {
            work = Files.createTempDirectory("hindsight-snippet");
        } catch (IOException noTemporaryDirectory) {
            return new RunResult.Failed("Cannot create a working directory: " + noTemporaryDirectory);
        }
        try {
            Path classes = Files.createDirectories(work.resolve("classes"));
            List<String> errors = compile(snippet, work, classes);
            if (!errors.isEmpty()) {
                return new RunResult.DidNotCompile(errors);
            }
            return execute(snippet, classes, work.resolve("traces"));
        } catch (IOException failure) {
            return new RunResult.Failed("Could not run the snippet: " + failure);
        } finally {
            deleteRecursively(work);
        }
    }

    private static List<String> compile(Snippet snippet, Path work, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return List.of("No Java compiler is available. This needs to run on a JDK, not a JRE.");
        }
        Path source = work.resolve(snippet.fileName());
        Files.writeString(source, snippet.source(), StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, files, diagnostics,
                            List.of("-d", classes.toString()), null,
                            files.getJavaFileObjects(source.toFile()))
                    .call();
            if (compiled) {
                return List.of();
            }
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            // Numbered against the pasted text, not against the package line we prepended to it.
            long line = diagnostic.getLineNumber() - snippet.addedLines();
            errors.add((line > 0 ? "line " + line + ": " : "") + diagnostic.getMessage(null));
        }
        return errors.isEmpty() ? List.of("The snippet did not compile.") : errors;
    }

    private RunResult execute(Snippet snippet, Path classes, Path traces) throws IOException {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar,
                "-Dhindsight.packages=" + Snippet.PACKAGE,
                "-Dhindsight.trace.dir=" + traces,
                // Rendering the tree is the agent's own job. Asking it to print one is cheaper than
                // teaching this module to read the trace format, and it cannot drift from it.
                "-Dhindsight.dump=true",
                "-cp", classes.toString(),
                snippet.qualifiedName())
                .redirectErrorStream(true)
                .start();

        /*
         * The output has to be drained on another thread. readAllBytes returns when the stream
         * closes, and the stream of a snippet that never terminates never closes, so reading it
         * here would block forever and the timeout below would never get a chance to fire. That is
         * not hypothetical: an endless-loop snippet is the first thing anyone tries.
         */
        StringBuilder collected = new StringBuilder();
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                collected.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException closed) {
                // The process was killed out from under the read; whatever arrived is what there is.
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor();
            }
            // Joining after the process is gone is what makes the buffer safe to read here.
            drain.join(Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new RunResult.Failed("Interrupted while running the snippet.");
        }
        if (!finished) {
            return new RunResult.Failed("The snippet was still running after " + timeout.toSeconds()
                    + " seconds and was stopped. An endless loop, perhaps?");
        }
        String output = collected.toString();
        Captured captured = separate(output);
        return new RunResult.Ran(process.exitValue(),
                truncate(captured.program()), captured.recording(), newestTrace(traces));
    }

    /**
     * Splits what the child printed into the snippet's own output and the agent's rendered tree.
     *
     * <p>The agent prefixes everything it says, so the two are separable. Of its lines only the
     * dump is wanted: the startup banner, the configuration and the class counter are addressed to
     * whoever attached the agent, not to somebody looking at one snippet.
     */
    private static Captured separate(String output) {
        StringBuilder program = new StringBuilder();
        StringBuilder recording = new StringBuilder();
        boolean insideDump = false;
        for (String line : output.split("\n", -1)) {
            if (!line.startsWith(AGENT_PREFIX)) {
                program.append(line).append('\n');
                continue;
            }
            String said = line.substring(AGENT_PREFIX.length());
            if (said.startsWith("trace for ")) {
                insideDump = true;
            } else if (!said.startsWith("+")) {
                insideDump = false;
            }
            if (insideDump) {
                recording.append(said).append('\n');
            }
        }
        return new Captured(program.toString().stripTrailing(), recording.toString().stripTrailing());
    }

    private record Captured(String program, String recording) {
    }

    /** The trace the agent wrote when an exception escaped, or null when nothing failed. */
    private static String newestTrace(Path traces) throws IOException {
        if (!Files.isDirectory(traces)) {
            return null;
        }
        try (Stream<Path> files = Files.list(traces)) {
            Path newest = files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(file -> file.getFileName().toString()))
                    .orElse(null);
            return newest == null ? null : Files.readString(newest);
        }
    }

    private static String truncate(String output) {
        return output.length() <= OUTPUT_LIMIT
                ? output
                : output.substring(0, OUTPUT_LIMIT) + System.lineSeparator() + "... output truncated";
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temporary directory is untidy, not a failure worth reporting.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
