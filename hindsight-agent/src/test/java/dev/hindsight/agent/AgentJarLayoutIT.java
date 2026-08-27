package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the shape of the packaged agent, which is the part of the build most able to break
 * quietly. A missing manifest attribute or a leaked dependency package produces a jar that still
 * builds, still passes every unit test, and fails only inside somebody else's application.
 */
class AgentJarLayoutIT {

    @Test
    @DisplayName("the manifest makes the jar loadable as an agent")
    void manifestDeclaresTheAgentContract() throws Exception {
        try (JarFile jar = new JarFile(PackagedAgent.agentJar().toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();

            assertEquals("dev.hindsight.agent.Hindsight", attributes.getValue("Premain-Class"));
            assertEquals("true", attributes.getValue("Can-Retransform-Classes"));
            assertEquals("true", attributes.getValue("Can-Redefine-Classes"));
            assertTrue(attributes.getValue("Implementation-Version") != null,
                    "the agent reports its own version from this attribute at startup");
        }
    }

    @Test
    @DisplayName("no un-relocated Byte Buddy escapes into the host application")
    void byteBuddyIsFullyRelocated() throws Exception {
        try (JarFile jar = new JarFile(PackagedAgent.agentJar().toFile())) {
            List<String> leaked = jar.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.contains("net/bytebuddy/"))
                    .collect(Collectors.toList());

            assertTrue(leaked.isEmpty(),
                    "A -javaagent jar joins the system class path, so these would collide with the "
                            + "host application's own Byte Buddy: " + leaked);

            assertTrue(jar.stream().anyMatch(e -> e.getName().startsWith("dev/hindsight/shaded/bytebuddy/")),
                    "Byte Buddy is missing entirely, which means the relocation check above is vacuous");
        }
    }

    @Test
    @DisplayName("no module descriptor confuses the class-path launch")
    void noModuleInfoSurvives() throws Exception {
        try (JarFile jar = new JarFile(PackagedAgent.agentJar().toFile())) {
            assertTrue(jar.stream().noneMatch(e -> e.getName().endsWith("module-info.class")));
        }
    }
}
