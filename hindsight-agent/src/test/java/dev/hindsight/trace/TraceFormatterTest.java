package dev.hindsight.trace;

import dev.hindsight.runtime.RingBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceFormatterTest {

    private static final String APP = "sample.testapp.TestApp";
    private static final String GREETING = "sample.testapp.Greeting";

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    private static RingBuffer nestedCall() {
        RingBuffer buffer = new RingBuffer(64, 16);
        buffer.recordEnter(APP, "main", "String[]");
        buffer.recordEnter(APP, "greet", "String");
        buffer.recordEnter(GREETING, "normalise", "");
        buffer.recordReturn(GREETING, "normalise", "String");
        buffer.recordReturn(APP, "greet", "String");
        buffer.recordReturn(APP, "main", "void");
        return buffer;
    }

    private static List<String> bodyOf(String rendered) {
        return rendered.lines().skip(1).map(line -> line.replace("[hindsight] ", "")).toList();
    }

    @Test
    @DisplayName("the tree is indented by call depth")
    void indentsByDepth() {
        List<String> body = bodyOf(TraceFormatter.render("main", nestedCall()));

        assertEquals(6, body.size());
        assertTrue(body.get(0).endsWith("-> sample.testapp.TestApp.main(String[])"), body.get(0));
        assertTrue(body.get(1).endsWith("  -> sample.testapp.TestApp.greet(String)"), body.get(1));
        assertTrue(body.get(2).endsWith("    -> sample.testapp.Greeting.normalise()"), body.get(2));
        assertTrue(body.get(3).endsWith("    <- sample.testapp.Greeting.normalise returned String"), body.get(3));
        assertTrue(body.get(4).endsWith("  <- sample.testapp.TestApp.greet returned String"), body.get(4));
        assertTrue(body.get(5).endsWith("<- sample.testapp.TestApp.main returned void"), body.get(5));
    }

    @Test
    @DisplayName("each kind reads as what it was")
    void rendersEachKind() {
        RingBuffer buffer = new RingBuffer(16, 8);
        buffer.recordEnter(APP, "greet", "String, int");
        buffer.recordThrow(APP, "greet", "IllegalStateException");

        assertEquals("-> sample.testapp.TestApp.greet(String, int)", TraceFormatter.event(buffer, 0));
        assertEquals("<! sample.testapp.TestApp.greet threw IllegalStateException",
                TraceFormatter.event(buffer, 1));
    }

    @Test
    @DisplayName("the first event is the origin, so offsets start at zero")
    void timesAreRelativeToTheFirstEvent() {
        List<String> body = bodyOf(TraceFormatter.render("main", nestedCall()));

        assertTrue(body.getFirst().startsWith("+  0.000ms"), body.getFirst());
    }

    @Test
    @DisplayName("a trace that outran its buffer says so rather than reading as a complete account")
    void headerReportsEventsDroppedToTheRing() {
        RingBuffer buffer = new RingBuffer(4, 64);
        for (int i = 0; i < 3; i++) {
            buffer.recordEnter(APP, "m" + i, "");
            buffer.recordReturn(APP, "m" + i, "void");
        }

        String header = TraceFormatter.header("main", buffer);

        assertTrue(header.contains("4 events"), header);
        assertTrue(header.contains("2 dropped to the ring"), header);
        assertFalse(header.contains("beyond max depth"), header);
    }

    @Test
    @DisplayName("frames too deep to record are reported separately from ones the ring lost")
    void headerReportsFramesTooDeepToRecord() {
        RingBuffer buffer = new RingBuffer(64, 2);
        for (int depth = 0; depth < 4; depth++) {
            buffer.recordEnter(APP, "deep" + depth, "");
        }

        String header = TraceFormatter.header("main", buffer);

        // Two frames fit inside the depth limit; the other two are counted, not stored.
        assertTrue(header.contains("2 events"), header);
        assertTrue(header.contains("2 beyond max depth"), header);
        assertFalse(header.contains("dropped to the ring"), header);
    }

    @Test
    @DisplayName("a clean trace says only how much of it there is")
    void headerStaysQuietWhenNothingWasLost() {
        assertEquals("[hindsight] trace for main: 6 events", TraceFormatter.header("main", nestedCall()));
    }

    @Test
    @DisplayName("one event is not one events")
    void headerCounts() {
        RingBuffer buffer = new RingBuffer(16, 8);
        buffer.recordEnter(APP, "only", "");

        assertEquals("[hindsight] trace for main: 1 event", TraceFormatter.header("main", buffer));
    }

    @Test
    @DisplayName("an empty buffer renders without falling over")
    void emptyBufferIsSafe() {
        assertEquals("[hindsight] trace for main: 0 events",
                TraceFormatter.render("main", new RingBuffer(16, 8)));
    }

    @Test
    @DisplayName("timings read the same wherever the agent is run")
    void timingsDoNotFollowTheDefaultLocale() {
        // A German default locale renders a decimal comma, which turns "+  0.123ms" into
        // "+  0,123ms" and makes the output depend on where the JVM happens to be.
        Locale.setDefault(Locale.GERMANY);

        assertEquals("+  1.500ms", TraceFormatter.elapsed(1_500_000L));
    }
}
