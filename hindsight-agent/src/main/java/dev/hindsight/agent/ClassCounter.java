package dev.hindsight.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts classes as the JVM offers them for transformation, and transforms none of them.
 *
 * <p>This is the step 1 proof of plumbing. The obvious alternative -- reading
 * {@link Instrumentation#getAllLoadedClasses()} at premain time -- reports a few hundred core
 * classes that were loaded before the agent existed and says nothing whatsoever about whether a
 * transformer is actually wired into the class-loading path. A non-zero candidate count here
 * cannot be produced by anything except the JVM calling {@link #transform}.
 */
final class ClassCounter implements ClassFileTransformer {

    /*
     * AtomicLong rather than LongAdder, despite LongAdder being the better counter under
     * contention. LongAdder inflates lazily into Striped64.Cell, which would mean loading a class
     * from inside transform(), on the class-loading path, from a transformer -- exactly the kind
     * of re-entrant bootstrap that makes agents fail in ways that are miserable to debug.
     * AtomicLong pulls in nothing after construction, and an unsynchronised counter is not worth
     * the risk for a diagnostic that runs once.
     */
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong excluded = new AtomicLong();

    /**
     * Constructs the counter <em>before</em> registering it, so that every class it touches is
     * already loaded and initialised by the time the JVM first calls into it.
     */
    static ClassCounter installOn(Instrumentation instrumentation) {
        ClassCounter counter = new ClassCounter();
        instrumentation.addTransformer(counter, false);
        return counter;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        try {
            seen.incrementAndGet();
            if (Exclusions.isExcluded(className)) {
                excluded.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // The JVM swallows a throwing transformer, but only after the class load has already
            // paid for it. Counting must never be the reason a class fails to appear.
        }
        return null; // null means "unchanged"; step 1 observes and nothing more.
    }

    long seen() {
        return seen.get();
    }

    long excluded() {
        return excluded.get();
    }

    long candidates() {
        return seen.get() - excluded.get();
    }

    String summary() {
        return seen() + plural(seen(), " class", " classes") + " loaded since attach, "
                + excluded() + " excluded, "
                + candidates() + plural(candidates(), " candidate", " candidates");
    }

    private static String plural(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }
}
