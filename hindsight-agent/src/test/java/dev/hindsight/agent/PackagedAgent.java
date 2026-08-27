package dev.hindsight.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the artefacts the integration tests run against.
 *
 * <p>The paths are handed down by failsafe. When they are missing it is almost always because a
 * single module was built in isolation, so the failure says that rather than reporting a file that
 * is merely not there.
 */
final class PackagedAgent {

    private PackagedAgent() {
    }

    static Path agentJar() {
        return require("hindsight.agent.jar");
    }

    static Path applicationJar() {
        return require("hindsight.testapp.jar");
    }

    static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static Path require(String property) {
        String configured = System.getProperty(property);
        if (configured == null) {
            throw new IllegalStateException(
                    property + " is not set. These tests are driven by failsafe: run ./mvnw verify.");
        }
        Path jar = Path.of(configured);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "Missing " + jar + ". Run ./mvnw verify from the repository root so the whole "
                            + "reactor is packaged, rather than building one module on its own.");
        }
        return jar;
    }
}
