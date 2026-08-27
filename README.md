# hindsight

A time-travel debugger for the JVM, packaged as a java agent.

Attach it to an application. When an exception escapes a request, it writes out what that thread
was actually doing — every instrumented method entry, its arguments, its return value, the
exception that ended it — so you can step *backwards* from the failure to the point where the bad
value was born, instead of staring at the stack trace of the place it finally exploded.

## What this is not

True record-and-replay debugging — re-executing a program deterministically, thread interleaving
and all — is a research problem. Capturing why two threads reached a shared field in that
particular order, cheaply enough to leave switched on in production, is the part every previous
attempt has died on.

hindsight does not attempt it.

It records the **observable state** of an execution and lets you scrub through the recording. There
is no replay engine and no determinism guarantee. You are reading a flight recorder, not re-flying
the plane. That trade — most of the practical debugging value for a small fraction of the
difficulty — is the premise of the project, not a limitation it is embarrassed about.

## Status

**Step 2 of 8.** The agent attaches and traces one method's entry and exit through inlined Byte
Buddy advice, with the reentrancy guard and the exclusion list already in place. The target is
still hardcoded and nothing is written to a buffer yet.

| Step | | |
|---|---|---|
| 1 | Agent skeleton: premain, manifest, class observation | **done** |
| 2 | Instrument one method via Byte Buddy Advice; reentrancy guard | **done** |
| 3 | Package-prefix filtering and a bounded per-thread ring buffer | next |
| 4 | Value summarisation, with references never retained | |
| 5 | Dump-on-exception and a versioned trace schema | |
| 6 | HTML viewer: call tree, timeline scrubber, value inspector | |
| 7 | Overhead benchmark: throughput and p99, agent off vs on | |
| 8 | Demo: a null born three layers below where it throws | |

## Try it

Requires JDK 25. Maven is not required — the wrapper fetches it.

```bash
./mvnw verify
java -javaagent:hindsight-agent/target/hindsight-agent.jar \
     -jar hindsight-testapp/target/hindsight-testapp.jar
```

```
[hindsight] agent loaded - 0.1.0-SNAPSHOT on JVM 25.0.1
[hindsight] tracing sample.testapp.TestApp.greet
[hindsight] [main] -> sample.testapp.TestApp.greet(String)
[hindsight] [main] <- sample.testapp.TestApp.greet returned String
hello from testapp
[hindsight] 1078 classes loaded since attach, 1077 excluded, 1 candidate
```

Arguments and returns are reported by **type, not value**. Rendering a value means calling
`toString` on an application object, which can be slow, can throw, and can have side effects; that
needs the guarded summariser in step 4 rather than an unguarded call on the hot path.

The counts cover classes loaded *after* the agent attached, so the total omits everything already
in memory by the time `premain` runs. The single candidate is `TestApp`.

## Layout

| Module | |
|---|---|
| `hindsight-agent` | The agent. Shaded, with Byte Buddy relocated. |
| `hindsight-testapp` | A tiny application used as an instrumentation target by the tests. |

Inside the agent, `dev.hindsight.agent` is the part that decides what to instrument and runs once
at startup; `dev.hindsight.runtime` is the part that application threads call into, twice per
traced invocation. The split is a reminder of which code is allowed to be expensive.

## Design notes

**Byte Buddy is relocated into `dev.hindsight.shaded.bytebuddy`.** A `-javaagent` jar joins the
system class path, so an un-relocated copy would collide with the Byte Buddy bundled by Mockito,
Hibernate or Spring in the host application. Its multi-release JDK 24+ overlay is stripped as well:
the shade relocator does not rewrite paths beneath `META-INF/versions`, so those classes would
survive under their original names. An integration test asserts the packaged jar contains no
`net/bytebuddy/` entry, and asserts it contains the relocated ones, so the check cannot pass
against an empty jar.

**The agent never instruments `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`, `net/bytebuddy/`, or
anything under `dev/hindsight/`.** That last one is deliberately a single wholesale prefix rather
than a list of the agent's own packages: a list has to be maintained, and the cost of forgetting an
entry is a recorder that records itself. The test application therefore lives under `sample.testapp`
— inside the excluded prefix it would be skipped silently, and the attach test would go on passing
while proving nothing.

**The recorder cannot record itself.** A thread-local guard is held for the duration of a recorder
call and never across the body of the instrumented method. Closing it over the callee instead would
be simpler and would suppress every nested application frame, and a call tree with only its root is
not a call tree.

**Byte Buddy's `sun.misc.Unsafe` probe is switched off before the library is touched.** Initialising
its class injector calls `Unsafe::objectFieldOffset`, which on JDK 24+ prints a terminal-deprecation
warning — into the stderr of an application whose only crime was attaching an agent. Because shade
relocates the property name along with everything else, the setting reaches only the agent's private
copy of Byte Buddy and leaves a host application's own copy exactly as it was found. Suppressing it
required moving the method matcher out of a static field, since a static field of a Byte Buddy type
initialises during the agent class's own initialisation, before `premain` gets a chance to run.

**Step 1 counts classes rather than listing them.** Reading `getAllLoadedClasses()` at premain time
would report a few hundred classes loaded before the agent existed, which says nothing about
whether a transformer is wired into the class-loading path. A non-zero candidate count can only be
produced by the JVM actually calling `transform`.

## Scope boundaries

These are known and deliberate, not oversights:

- **Method boundaries only.** Arguments, return values and thrown exceptions. Mid-method local
  variables need line-level instrumentation and a `LocalVariableTable`, which requires the target to
  be compiled with `-g`. That is a stretch goal, not v1.
- **Single-threaded request handling.** Async and reactive flows are out of scope for v1.
- **A recording, not a replay.** Stated here, and it will be stated in the viewer.
- **Step 2 traces a single hardcoded method.** Selecting what to instrument is step 3, and is kept
  separate on purpose: proving that advice reaches a real method and proving that the right methods
  are chosen are different problems, and debugging them together is worse than debugging them apart.
- **Argument names depend on the target.** Names are read from the target's bytecode, so an
  application compiled without `-parameters` yields `arg0`, `arg1`. The agent cannot fix that from
  outside a third-party jar.

## Licence

MIT.
