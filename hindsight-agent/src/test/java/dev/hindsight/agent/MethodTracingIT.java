package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that advice reaches a real method in a separately packaged application, running in its own
 * JVM, loaded by the application class loader.
 */
class MethodTracingIT {

    private static final String ENTRY = "[hindsight] [main] -> sample.testapp.TestApp.greet(String)";
    private static final String EXIT = "[hindsight] [main] <- sample.testapp.TestApp.greet returned String";

    @Test
    @DisplayName("entry and exit of the traced method are both recorded, in that order")
    void tracesTheTargetMethod() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runInstrumentedApplication();

        assertEquals(0, result.exitCode(), result.toString());
        assertEquals(List.of(ENTRY, EXIT), result.traceLines(),
                "expected exactly one entry and one exit, in order:\n" + result);
    }

    @Test
    @DisplayName("the traced method still does its job")
    void preservesApplicationBehaviour() throws Exception {
        ForkedJvm.Result withAgent = ForkedJvm.runInstrumentedApplication();
        ForkedJvm.Result withoutAgent = ForkedJvm.runBareApplication();

        // Advice is spliced into greet(). Its return value must survive that intact.
        assertTrue(withoutAgent.stdout().contains("hello from testapp"));
        assertTrue(withAgent.stdout().contains("hello from testapp"),
                "instrumenting a method must not change what it returns:\n" + withAgent);
    }

    @Test
    @DisplayName("methods outside the target are left alone")
    void doesNotTraceUntargetedMethods() throws Exception {
        ForkedJvm.Result result = ForkedJvm.runInstrumentedApplication();

        // main() runs in the same class and is not the configured target. If it appears, the
        // method matcher is not actually narrowing anything.
        assertTrue(result.traceLines().stream().noneMatch(line -> line.contains(".main")),
                "an untargeted method was instrumented:\n" + result);
    }
}
