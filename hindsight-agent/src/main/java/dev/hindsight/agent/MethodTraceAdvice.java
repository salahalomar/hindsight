package dev.hindsight.agent;

import dev.hindsight.runtime.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * The bodies spliced into every traced method.
 *
 * <p>Byte Buddy <em>inlines</em> advice: this bytecode is copied into the target rather than
 * called, which is what makes it cheap and also what constrains it. The methods must be static,
 * they cannot touch instance state, and they cannot use lambdas or method references, because the
 * resulting call sites would have to resolve in the target's context rather than this one.
 *
 * <p>Keeping the bodies to a single delegating call is deliberate. Whatever is written here is
 * duplicated into every instrumented method in the application, so logic belongs in
 * {@link Recorder}, where it exists once.
 *
 * <p>{@code suppress} makes Byte Buddy wrap each body in a handler that swallows throwables. It is
 * the outer of two nets -- {@link Recorder} catches as well -- because an exception escaping here
 * surfaces inside a method that has no idea it was instrumented.
 */
final class MethodTraceAdvice {

    private MethodTraceAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(@Advice.Origin("#t") String type,
                        @Advice.Origin("#m") String method,
                        @Advice.AllArguments Object[] arguments) {
        Recorder.onEnter(type, method, arguments);
    }

    /**
     * {@code onThrowable} is what makes the exceptional path visible; without it this runs only on
     * a normal return, and the failures worth recording are precisely the ones that do not return.
     * Reading {@code @Advice.Thrown} does not consume the exception, which still propagates.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(@Advice.Origin("#t") String type,
                       @Advice.Origin("#m") String method,
                       @Advice.Origin("#r") String returnType,
                       @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                       @Advice.Thrown Throwable thrown) {
        Recorder.onExit(type, method, returnType, returned, thrown);
    }
}
