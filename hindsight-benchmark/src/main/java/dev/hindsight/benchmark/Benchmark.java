package dev.hindsight.benchmark;

import sample.workload.OrderService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Measures what attaching the agent costs.
 *
 * <p>Runs as two programs in one jar. The parent forks a JVM per configuration per repetition,
 * because an agent has to be present from the first class load and cannot be switched on inside a
 * running measurement. Each child runs a fixed workload and prints one line of results, which the
 * parent collates.
 *
 * <p>Three things this does that a naive timing loop does not, and without which the numbers would
 * not be worth reading:
 *
 * <ul>
 *   <li><b>Throughput and latency are measured in separate passes.</b> A {@code nanoTime} pair
 *       costs tens of nanoseconds, which is a rounding error against an instrumented request and a
 *       real fraction of an uninstrumented one. Timing every request while measuring throughput
 *       would tax the baseline hardest and quietly flatter the agent.
 *   <li><b>Everything is warmed up first.</b> Measurements taken before the JIT has compiled the
 *       workload describe the interpreter, not the program.
 *   <li><b>Results are consumed.</b> A workload whose result is discarded can be deleted outright
 *       by the compiler, and a benchmark of nothing is very fast indeed.
 * </ul>
 *
 * <p>Each configuration is run several times in fresh JVMs and the median reported, because
 * compilation decisions differ between runs and a single run is an anecdote.
 */
public final class Benchmark {

    private static final String RESULT = "hindsight-benchmark ";
    private static final String[] CUSTOMERS = {
            "acme", "globex", "initech", "umbrella", "soylent", "hooli", "vehement", "massive"
    };

    private Benchmark() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.child != null) {
            child(options);
        } else {
            parent(options);
        }
    }

    // ---- child: the thing actually being measured -----------------------------------------------

    private static void child(Options options) {
        OrderService service = new OrderService();

        if (options.probe) {
            // One request, so the parent can count how many methods the agent actually instrumented
            // rather than take the author's word for it.
            consume(request(service, 1));
            return;
        }

        long blackhole = 0;
        for (int i = 0; i < options.warmup; i++) {
            blackhole += request(service, i);
        }

        // Pass one: throughput, with no per-request timing in the loop at all.
        long throughputStart = System.nanoTime();
        for (int i = 0; i < options.requests; i++) {
            blackhole += request(service, i);
        }
        long elapsed = System.nanoTime() - throughputStart;

        // Pass two: latency, which necessarily pays for its own clock reads.
        Latencies latencies = new Latencies(options.requests);
        for (int i = 0; i < options.requests; i++) {
            long began = System.nanoTime();
            blackhole += request(service, i);
            latencies.record(System.nanoTime() - began);
        }

        Latencies.Summary summary = latencies.summarise(elapsed);
        consume(blackhole);
        System.out.println(RESULT
                + "config=" + options.child
                + " requests=" + summary.requests()
                + " elapsedNanos=" + summary.elapsedNanos()
                + " p50=" + summary.p50()
                + " p90=" + summary.p90()
                + " p99=" + summary.p99()
                + " p999=" + summary.p999()
                + " max=" + summary.max()
                + " mean=" + summary.mean()
                + " timerNanos=" + timerCost());
    }

    private static long request(OrderService service, int i) {
        return service.handle(i, CUSTOMERS[i & 7], (i & 15) + 1);
    }

    /**
     * The workload's result has to leave the method, or the compiler is entitled to notice that
     * nothing depends on it and delete the whole call.
     */
    private static void consume(long blackhole) {
        if (blackhole == Long.MIN_VALUE) {
            System.out.println("unreachable, and the compiler cannot prove it");
        }
    }

    /**
     * What one timed sample costs before the workload is even called. Reported, never subtracted:
     * subtracting a noisy estimate from a measurement produces a number that looks more precise
     * than either. Warmed up first, or it measures the interpreter reading a clock.
     */
    private static long timerCost() {
        clockLoop(100_000);
        return clockLoop(500_000);
    }

    private static long clockLoop(int rounds) {
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            long began = System.nanoTime();
            if (System.nanoTime() - began < 0) {
                throw new IllegalStateException("time ran backwards");
            }
        }
        return (System.nanoTime() - start) / rounds;
    }

    // ---- parent: forking, collating, reporting --------------------------------------------------

    private static void parent(Options options) throws IOException, InterruptedException {
        Path agent = Path.of(options.agentJar);
        if (!Files.isRegularFile(agent)) {
            System.err.println("No agent jar at " + agent.toAbsolutePath()
                    + "\nRun ./mvnw package first, or pass --agent <path>.");
            System.exit(2);
        }

        int callsPerRequest = probeInstrumentedCalls(options, agent);

        Map<Configuration, List<Run>> results = new LinkedHashMap<>();
        for (Configuration configuration : Configuration.values()) {
            results.put(configuration, new ArrayList<>());
        }

        /*
         * Round robin by fork, not by configuration. Running every "off" fork first and every
         * "summary" fork last would hand each configuration a different machine: thermal state and
         * whatever else is running drift over the course of a benchmark, and grouping the runs
         * turns that drift into a difference between configurations. Interleaving spreads it
         * evenly, so it shows up as spread rather than as signal.
         */
        for (int fork = 0; fork < options.forks; fork++) {
            for (Configuration configuration : Configuration.values()) {
                System.err.println("  fork " + (fork + 1) + " of " + options.forks
                        + ": " + configuration.label());
                results.get(configuration).add(runChild(options, agent, configuration));
            }
        }
        report(options, results, callsPerRequest);
    }

    /**
     * Asks the agent how many methods it instruments per request, by running one request with the
     * console dump on and counting the entries it printed. A hand-counted number in a comment goes
     * stale the first time the workload changes.
     */
    private static int probeInstrumentedCalls(Options options, Path agent) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                javaExecutable(),
                "-javaagent:" + agent,
                "-Dhindsight.packages=" + Workloads.PACKAGE,
                "-Dhindsight.dump=true",
                "-jar", options.jar, "--child", Configuration.SUMMARY.label(), "--probe"));
        String output = run(command);
        return (int) output.lines().filter(line -> line.contains("-> ")).count();
    }

    /** One child process: what it measured, and what its own clock cost while measuring it. */
    private record Run(Latencies.Summary summary, long timerNanos) {
    }

    private static Run runChild(Options options, Path agent, Configuration configuration)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(configuration.jvmArguments(agent.toString()));
        command.addAll(List.of("-jar", options.jar,
                "--child", configuration.label(),
                "--warmup", String.valueOf(options.warmup),
                "--requests", String.valueOf(options.requests)));

        String output = run(command);
        String line = output.lines()
                .filter(candidate -> candidate.startsWith(RESULT))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the " + configuration.label() + " run produced no result:\n" + output));
        return parse(line);
    }

    private static Run parse(String line) {
        Map<String, Long> fields = new LinkedHashMap<>();
        for (String pair : line.substring(RESULT.length()).split(" ")) {
            String[] halves = pair.split("=", 2);
            if (halves.length == 2) {
                try {
                    fields.put(halves[0], Long.parseLong(halves[1]));
                } catch (NumberFormatException notANumber) {
                    // config=<label>, which the caller already knows.
                }
            }
        }
        return new Run(new Latencies.Summary(
                fields.get("requests").intValue(), fields.get("elapsedNanos"),
                fields.get("p50"), fields.get("p90"), fields.get("p99"),
                fields.get("p999"), fields.get("max"), fields.get("mean")),
                fields.getOrDefault("timerNanos", 0L));
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("a benchmark run did not finish: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("a benchmark run failed:\n" + output);
        }
        return output;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    // ---- reporting ------------------------------------------------------------------------------

    private static void report(Options options,
                               Map<Configuration, List<Run>> results,
                               int callsPerRequest) {
        StringBuilder out = new StringBuilder("\nhindsight overhead benchmark\n\n");
        out.append(field("JVM", System.getProperty("java.version") + " ("
                + System.getProperty("java.vm.name") + ")"));
        out.append(field("Machine", System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " processors"));
        out.append(field("Protocol", options.forks + " forks of "
                + count(options.warmup) + " warmup + " + count(options.requests) + " throughput + "
                + count(options.requests) + " timed requests, median across forks"));
        out.append(field("Workload", Workloads.PACKAGE + ".OrderService.handle, "
                + callsPerRequest + " instrumented calls per request"));
        out.append(field("Timer", "one nanoTime pair costs about "
                + median(results.get(Configuration.OFF)).timerNanos()
                + "ns, and is inside every latency sample below"));

        Latencies.Summary baseline = median(results.get(Configuration.OFF)).summary();

        out.append("\n  ").append(String.format(Locale.ROOT, "%-9s %-34s %14s %9s %9s %9s %9s%n",
                "config", "", "throughput", "p50", "p90", "p99", "p99.9"));
        for (Map.Entry<Configuration, List<Run>> entry : results.entrySet()) {
            Latencies.Summary summary = median(entry.getValue()).summary();
            out.append("  ").append(String.format(Locale.ROOT, "%-9s %-34s %10s/s %9s %9s %9s %9s%n",
                    entry.getKey().label(), entry.getKey().description(),
                    count((long) summary.throughputPerSecond()),
                    micros(summary.p50()), micros(summary.p90()),
                    micros(summary.p99()), micros(summary.p999())));
        }

        out.append("\n  every fork, throughput in requests/s, so the spread is visible\n\n");
        for (Map.Entry<Configuration, List<Run>> entry : results.entrySet()) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-9s", entry.getKey().label()));
            for (Run run : entry.getValue()) {
                row.append(String.format(Locale.ROOT, " %12s",
                        count((long) run.summary().throughputPerSecond())));
            }
            out.append(row).append('\n');
        }

        // Derived from throughput rather than from p50. The throughput pass carries no per-request
        // clock reads, so it is the only figure here that is not partly a measurement of nanoTime.
        double baselineNanos = 1e9 / baseline.throughputPerSecond();
        out.append("\n  cost of the agent, against \"off\", derived from throughput\n\n  ")
                .append(String.format(Locale.ROOT, "%-9s %12s %16s %30s%n",
                        "config", "throughput", "added per request", "added per instrumented call"));
        for (Map.Entry<Configuration, List<Run>> entry : results.entrySet()) {
            if (entry.getKey() == Configuration.OFF) {
                continue;
            }
            Latencies.Summary summary = median(entry.getValue()).summary();
            double addedPerRequest = 1e9 / summary.throughputPerSecond() - baselineNanos;
            out.append("  ").append(String.format(Locale.ROOT, "%-9s %11s %16s %30s%n",
                    entry.getKey().label(),
                    String.format(Locale.ROOT, "%.2fx",
                            summary.throughputPerSecond() / baseline.throughputPerSecond()),
                    String.format(Locale.ROOT, "%.0fns", addedPerRequest),
                    callsPerRequest > 0
                            ? String.format(Locale.ROOT, "%.0fns", addedPerRequest / callsPerRequest)
                            : "?"));
        }

        out.append("\n  An uninstrumented request here costs about ")
                .append(String.format(Locale.ROOT, "%.0fns", baselineNanos))
                .append(", which is close enough to the cost of\n")
                .append("  reading the clock twice that the p50 column means little for off and attached.\n")
                .append("  The tail columns are meaningful wherever the agent's own cost dominates it.\n")
                .append("  Percentiles are nearest-rank over every sample in the median fork.\n")
                .append("  One machine, not a controlled environment. Treat as an order of magnitude.\n");
        System.out.println(out);
    }

    private static String field(String name, String value) {
        return String.format(Locale.ROOT, "  %-10s %s%n", name, value);
    }

    /** The middle run by throughput. A single fork is an anecdote; the fastest one is marketing. */
    private static Run median(List<Run> runs) {
        List<Run> sorted = new ArrayList<>(runs);
        sorted.sort((left, right) -> Double.compare(
                left.summary().throughputPerSecond(), right.summary().throughputPerSecond()));
        return sorted.get(sorted.size() / 2);
    }

    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.2fus", nanos / 1000.0);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Minimal argument handling; this is a benchmark, not a command line tool. */
    private record Options(String child, boolean probe, String agentJar, String jar,
                           int warmup, int requests, int forks) {

        static Options parse(String[] args) {
            String child = null;
            boolean probe = false;
            String agent = "hindsight-agent/target/hindsight-agent.jar";
            String jar = "hindsight-benchmark/target/hindsight-benchmark.jar";
            int warmup = 50_000;
            int requests = 200_000;
            int forks = 5;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--child" -> child = args[++i];
                    case "--probe" -> probe = true;
                    case "--agent" -> agent = args[++i];
                    case "--jar" -> jar = args[++i];
                    case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                    case "--requests" -> requests = Integer.parseInt(args[++i]);
                    case "--forks" -> forks = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(child, probe, agent, jar, warmup, requests, forks);
        }
    }
}
