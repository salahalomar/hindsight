package sample.testapp;

/**
 * The instrumentation target for the agent's integration tests.
 *
 * <p>This class is intentionally trivial and intentionally unaware of the agent. It exists so the
 * agent can be exercised against a real, separately packaged application launched in its own JVM,
 * rather than against classes that happen to already be on the agent's own classpath.
 *
 * <p>It deliberately lives outside {@code dev.hindsight}, the prefix the agent refuses to
 * instrument. A target inside that prefix would be silently skipped, and the test that proves
 * instrumentation works would quietly prove nothing.
 *
 * <p>{@link #greet(String)} is the method step 2 will instrument first: it takes an argument,
 * returns a value, and is called exactly once, which makes an entry/exit trace easy to eyeball.
 */
public final class TestApp {

    private TestApp() {
    }

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "testapp";
        System.out.println(greet(name));
    }

    static String greet(String name) {
        return "hello from " + name;
    }
}
