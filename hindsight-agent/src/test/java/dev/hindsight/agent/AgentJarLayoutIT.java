package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Shade rewrites string constants along with type references, so a literal
     * {@code "net/bytebuddy/"} in the exclusion list would be rewritten into the agent's own shaded
     * package during packaging. The rule protecting the host application's Byte Buddy would quietly
     * become a duplicate of the rule above it, and nothing in the source would look wrong.
     *
     * <p>Only the packaged artefact can show this, so the class is loaded back out of the jar,
     * in isolation from the copy on the test classpath, and asked.
     */
    @Test
    @DisplayName("the exclusion list survives relocation with its meaning intact")
    void exclusionsStillCoverTheHostsByteBuddyAfterShading() throws Exception {
        URL jar = PackagedAgent.agentJar().toUri().toURL();

        // A null parent means the bootstrap loader, so this resolves out of the jar rather than
        // finding the unshaded class already sitting on the test classpath.
        try (URLClassLoader shaded = new URLClassLoader(new URL[]{jar}, null)) {
            Class<?> exclusions = shaded.loadClass("dev.hindsight.agent.Exclusions");
            Method isExcludedType = exclusions.getMethod("isExcludedType", String.class);

            assertTrue((Boolean) isExcludedType.invoke(null, "net.bytebuddy.ByteBuddy"),
                    "the packaged agent would instrument the host application's own Byte Buddy");
            assertTrue((Boolean) isExcludedType.invoke(null, "dev.hindsight.shaded.bytebuddy.ByteBuddy"),
                    "the packaged agent would instrument its own copy of Byte Buddy");
            assertFalse((Boolean) isExcludedType.invoke(null, "sample.testapp.TestApp"),
                    "relocation has widened the exclusion list into swallowing application classes");
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
