package sample.testapp;

import java.util.Locale;

/** The innermost frames of the test application's call chain. */
final class Greeting {

    private final String name;

    Greeting(String name) {
        this.name = name;
    }

    String normalise() {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    String render(String normalised) {
        return "hello from " + normalised;
    }
}
