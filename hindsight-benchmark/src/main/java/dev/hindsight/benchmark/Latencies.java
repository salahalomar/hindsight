package dev.hindsight.benchmark;

import java.util.Arrays;

/**
 * Every measured sample, kept so the percentiles are exact.
 *
 * <p>No histogram library. A histogram earns its place when samples cannot all be kept -- millions
 * per second, indefinitely -- and it buys that by bucketing, which makes every percentile an
 * approximation. A benchmark takes a known number of samples, so keeping them all costs a few
 * megabytes and gives exact answers instead of nearly-right ones.
 *
 * <p>The array is allocated up front. Growing it mid-run would put an allocation and a copy inside
 * the thing being measured.
 */
final class Latencies {

    private final long[] samples;
    private int count;

    Latencies(int capacity) {
        this.samples = new long[capacity];
    }

    void record(long nanos) {
        samples[count++] = nanos;
    }

    int count() {
        return count;
    }

    /** Sorts in place; call once, after measuring. */
    Summary summarise(long elapsedNanos) {
        if (count == 0) {
            throw new IllegalStateException("nothing was measured");
        }
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += samples[i];
        }
        Arrays.sort(samples, 0, count);
        return new Summary(
                count,
                elapsedNanos,
                percentile(50),
                percentile(90),
                percentile(99),
                percentile(99.9),
                samples[count - 1],
                total / count);
    }

    /**
     * Nearest rank: the smallest value at or below which at least the given share of samples fall.
     * Stated because "the p99" is ambiguous across tools, and an unstated definition is how two
     * honest measurements end up disagreeing.
     */
    private long percentile(double percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * count) - 1;
        return samples[Math.min(Math.max(rank, 0), count - 1)];
    }

    record Summary(int requests, long elapsedNanos,
                   long p50, long p90, long p99, long p999, long max, long mean) {

        double throughputPerSecond() {
            return requests / (elapsedNanos / 1e9);
        }
    }
}
