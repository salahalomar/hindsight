package sample.testapp;

/**
 * The instrumentation target for the agent's integration tests.
 *
 * <p>This class is intentionally small and intentionally unaware of the agent. It exists so the
 * agent can be exercised against a real, separately packaged application launched in its own JVM,
 * rather than against classes that happen to already be on the agent's own classpath.
 *
 * <p>It deliberately lives outside {@code dev.hindsight}, the prefix the agent refuses to
 * instrument. A target inside that prefix would be silently skipped, and the test that proves
 * instrumentation works would quietly prove nothing.
 *
 * <p>The call chain is three frames deep on purpose. A single method proves that advice is applied;
 * only nesting proves that call depth is tracked, that entries and exits stay paired, and that the
 * buffer holds a tree rather than a list.
 */
public final class TestApp {

    private TestApp() {
    }

    /** Passed as the first argument to run the branch that hands the agent a hostile object. */
    private static final String HOSTILE = "hostile";

    /** Passed as the first argument to run the branch that fails. */
    private static final String FAIL = "fail";

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "testapp";
        if (FAIL.equals(name)) {
            // The null is introduced here and does not explode until two frames below, which is
            // the shape of the bug this whole project exists to make visible.
            System.out.println(greet(null));
            return;
        }
        System.out.println(greet(name));
        if (HOSTILE.equals(name)) {
            System.out.println(describe(new Unprintable()));
        }
    }

    static String describe(Object subject) {
        // Deliberately does not touch the argument. The agent will, and that is the point.
        return "described one object";
    }

    static String greet(String name) {
        Greeting greeting = new Greeting(name);
        return greeting.render(greeting.normalise());
    }
}
