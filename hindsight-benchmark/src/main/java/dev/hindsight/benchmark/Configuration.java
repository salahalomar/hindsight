package dev.hindsight.benchmark;

import java.util.List;

/**
 * The four ways the agent can be present, from absent to fully recording.
 *
 * <p>Splitting "attached" from "recording" is the point of having four rather than three. It
 * separates what the agent costs merely by being on the class-loading path from what it costs per
 * call, and those are different decisions for whoever is deciding whether to deploy it.
 */
enum Configuration {

    OFF("off", "no agent") {
        @Override
        List<String> jvmArguments(String agentJar) {
            return List.of();
        }
    },

    ATTACHED("attached", "agent attached, no packages selected") {
        @Override
        List<String> jvmArguments(String agentJar) {
            return List.of("-javaagent:" + agentJar);
        }
    },

    TYPE("type", "recording, type names only") {
        @Override
        List<String> jvmArguments(String agentJar) {
            return List.of("-javaagent:" + agentJar,
                    "-Dhindsight.packages=" + Workloads.PACKAGE,
                    "-Dhindsight.values=type");
        }
    },

    SUMMARY("summary", "recording, values summarised") {
        @Override
        List<String> jvmArguments(String agentJar) {
            return List.of("-javaagent:" + agentJar,
                    "-Dhindsight.packages=" + Workloads.PACKAGE);
        }
    };

    private final String label;
    private final String description;

    Configuration(String label, String description) {
        this.label = label;
        this.description = description;
    }

    abstract List<String> jvmArguments(String agentJar);

    String label() {
        return label;
    }

    String description() {
        return description;
    }

    static Configuration byLabel(String label) {
        for (Configuration configuration : values()) {
            if (configuration.label.equals(label)) {
                return configuration;
            }
        }
        throw new IllegalArgumentException("unknown configuration: " + label);
    }
}
