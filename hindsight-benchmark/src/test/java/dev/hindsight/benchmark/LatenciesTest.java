package dev.hindsight.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Percentile arithmetic is easy to get subtly wrong and impossible to notice by eye, and every
 * number this project puts on a CV comes through here.
 */
class LatenciesTest {

    private static Latencies of(long... nanos) {
        Latencies latencies = new Latencies(Math.max(nanos.length, 1));
        for (long sample : nanos) {
            latencies.record(sample);
        }
        return latencies;
    }

    @Test
    @DisplayName("nearest rank: the p50 of 1..100 is the 50th value")
    void percentilesOverAHundredSamples() {
        Latencies latencies = new Latencies(100);
        for (int i = 1; i <= 100; i++) {
            latencies.record(i);
        }

        Latencies.Summary summary = latencies.summarise(1_000_000_000L);

        assertEquals(50, summary.p50());
        assertEquals(90, summary.p90());
        assertEquals(99, summary.p99());
        assertEquals(100, summary.p999(), "p99.9 of a hundred samples can only be the largest");
        assertEquals(100, summary.max());
    }

    @Test
    @DisplayName("samples are ordered before they are read, not assumed to arrive sorted")
    void sortsBeforeReporting() {
        Latencies.Summary summary = of(9, 1, 7, 3, 5).summarise(1_000_000_000L);

        assertEquals(5, summary.p50());
        assertEquals(9, summary.max());
        assertEquals(5, summary.mean());
    }

    @Test
    @DisplayName("a single sample is every percentile of itself")
    void oneSample() {
        Latencies.Summary summary = of(42).summarise(1_000_000_000L);

        assertEquals(42, summary.p50());
        assertEquals(42, summary.p999());
        assertEquals(42, summary.max());
    }

    @Test
    @DisplayName("the tail is the tail, not an average that hides it")
    void outliersSurviveToThePercentiles() {
        Latencies latencies = new Latencies(1000);
        for (int i = 0; i < 999; i++) {
            latencies.record(10);
        }
        latencies.record(1_000_000);

        Latencies.Summary summary = latencies.summarise(1_000_000_000L);

        assertEquals(10, summary.p50());
        assertEquals(10, summary.p99());
        assertEquals(1_000_000, summary.p999(), "the one slow sample is exactly what p99.9 is for");
        assertEquals(1_009, summary.mean(), "and the mean quietly buries it");
    }

    @Test
    @DisplayName("throughput comes from the elapsed time, not from the samples")
    void throughput() {
        Latencies.Summary summary = of(1, 2, 3, 4).summarise(2_000_000_000L);

        assertEquals(2.0, summary.throughputPerSecond(), 0.0001);
    }

    @Test
    @DisplayName("summarising nothing is a bug in the harness, not an empty result")
    void refusesToSummariseNothing() {
        assertThrows(IllegalStateException.class, () -> new Latencies(4).summarise(1));
    }
}
