package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionsTest {

    @DisplayName("the platform is never instrumented")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "java/lang/String",
            "java/util/concurrent/ConcurrentHashMap$Node",
            "javax/sql/DataSource",
            "jdk/internal/misc/Unsafe",
            "sun/nio/ch/FileChannelImpl",
            "com/sun/crypto/provider/AESCipher",
    })
    void excludesThePlatform(String internalName) {
        assertTrue(Exclusions.isExcluded(internalName));
    }

    @DisplayName("the agent never records itself")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "dev/hindsight/agent/Hindsight",
            "dev/hindsight/agent/ClassCounter",
            "dev/hindsight/runtime/Recorder",
            "dev/hindsight/shaded/bytebuddy/agent/builder/AgentBuilder",
    })
    void excludesTheAgentAndItsShadedDependencies(String internalName) {
        assertTrue(Exclusions.isExcluded(internalName));
    }

    @Test
    @DisplayName("an application's own Byte Buddy is left alone")
    void excludesAnApplicationsOwnByteBuddy() {
        assertTrue(Exclusions.isExcluded("net/bytebuddy/ByteBuddy"));
    }

    @Test
    @DisplayName("hidden classes arrive unnamed and are skipped rather than guessed at")
    void excludesHiddenClasses() {
        assertTrue(Exclusions.isExcluded(null));
    }

    @DisplayName("ordinary application classes remain candidates")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // The integration test target. If this ever starts being excluded, the attach test
            // would still pass while silently instrumenting nothing at all.
            "sample/testapp/TestApp",
            "com/example/orders/OrderService",
            "org/springframework/web/servlet/DispatcherServlet",
            // Close to the agent's prefix without being under it.
            "dev/hindsightful/Thing",
    })
    void doesNotExcludeApplicationClasses(String internalName) {
        assertFalse(Exclusions.isExcluded(internalName));
    }
}
