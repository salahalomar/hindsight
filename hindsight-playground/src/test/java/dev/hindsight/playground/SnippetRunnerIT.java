package dev.hindsight.playground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs real snippets through a real compiler and a real agent, because every part of this is a
 * process boundary and none of it can be usefully faked.
 */
class SnippetRunnerIT {

    private static final String BURIED_NULL = """
            public class Orders {
                public static void main(String[] args) {
                    System.out.println(total("widget"));
                }
                static int total(String sku) {
                    Price price = lookup(sku);
                    return price.amount() * 2;
                }
                static Price lookup(String sku) {
                    return catalogue(sku);
                }
                static Price catalogue(String sku) {
                    return null;
                }
                record Price(int amount) { }
            }
            """;

    private static SnippetRunner runner() {
        String agent = System.getProperty("hindsight.agent.jar");
        if (agent == null) {
            throw new IllegalStateException(
                    "hindsight.agent.jar is not set; these tests are driven by failsafe, run ./mvnw verify");
        }
        return new SnippetRunner(Path.of(agent), Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("the recording names the frame that produced the null, and the stack trace does not")
    void recordsWhereTheNullCameFrom() {
        RunResult.Ran ran = assertInstanceOf(RunResult.Ran.class, runner().run(BURIED_NULL));

        assertNotEquals(0, ran.exitCode(), "the snippet was supposed to fail:\n" + ran.output());
        assertTrue(ran.recorded(), "an escaping exception should have left a trace");

        // The JVM blames total(), where the dereference happened.
        assertTrue(ran.output().contains("snippet.Orders.total"), ran.output());
        assertFalse(ran.output().contains("Orders.catalogue"),
                "the stack trace should not contain the frame that had already returned:\n" + ran.output());

        // The recording names catalogue(), which had returned long before anything went wrong.
        assertTrue(ran.recording().contains("Orders.catalogue returned null"),
                "the recording does not say where the null came from:\n" + ran.recording());
    }

    @Test
    @DisplayName("a snippet that works leaves output and no trace")
    void recordsNothingWhenNothingFails() {
        RunResult.Ran ran = assertInstanceOf(RunResult.Ran.class, runner().run("""
                public class Fine {
                    public static void main(String[] args) {
                        System.out.println("all good");
                    }
                }
                """));

        assertEquals(0, ran.exitCode());
        assertTrue(ran.output().contains("all good"), ran.output());
        assertFalse(ran.recorded(), "nothing failed, so nothing should have been written");
    }

    @Test
    @DisplayName("the agent's own commentary is kept out of the snippet's output")
    void separatesTheAgentFromTheProgram() {
        RunResult.Ran ran = assertInstanceOf(RunResult.Ran.class, runner().run("""
                public class Quiet {
                    public static void main(String[] args) {
                        System.out.println("only this");
                    }
                }
                """));

        assertEquals("only this", ran.output().strip(),
                "the banner, the configuration line and the class counter should not be here");
    }

    @Test
    @DisplayName("a compiler error is reported against the line that was written")
    void reportsCompilerErrors() {
        RunResult.DidNotCompile failed = assertInstanceOf(RunResult.DidNotCompile.class,
                runner().run("""
                        public class Broken {
                            public static void main(String[] args) {
                                int x = "not an int";
                            }
                        }
                        """));

        assertFalse(failed.errors().isEmpty());
        // The error is on line 3 of what was pasted, not line 4 of what was compiled.
        assertTrue(failed.errors().getFirst().startsWith("line 3:"), failed.errors().toString());
    }

    @Test
    @DisplayName("a snippet that will not stop is stopped")
    void stopsARunawaySnippet() {
        RunResult.Failed failed = assertInstanceOf(RunResult.Failed.class,
                new SnippetRunner(Path.of(System.getProperty("hindsight.agent.jar")), Duration.ofSeconds(2))
                        .run("""
                                public class Forever {
                                    public static void main(String[] args) {
                                        while (true) { }
                                    }
                                }
                                """));

        assertTrue(failed.problem().contains("still running"), failed.problem());
    }

    @Test
    @DisplayName("a missing agent is said plainly rather than producing an empty recording")
    void reportsAMissingAgent() {
        RunResult.Failed failed = assertInstanceOf(RunResult.Failed.class,
                new SnippetRunner(Path.of("nowhere/hindsight-agent.jar"), Duration.ofSeconds(5))
                        .run(BURIED_NULL));

        assertTrue(failed.problem().contains("No agent jar"), failed.problem());
    }
}
