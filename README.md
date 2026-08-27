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

**Step 1 of 8.** The agent attaches, observes class loading, and reports what it could instrument.
It does not instrument anything yet.

| Step | | |
|---|---|---|
| 1 | Agent skeleton: premain, manifest, class observation | **done** |
| 2 | Instrument one method via Byte Buddy Advice; reentrancy guard | next |
| 3 | Package-prefix filtering and a bounded per-thread ring buffer | |
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
hello from testapp
[hindsight] 29 classes loaded since attach, 28 excluded, 1 candidate
```

The counts cover classes loaded *after* the agent attached, which is why the total is small: most
of the platform is already in memory by the time `premain` runs. The candidate is `TestApp`.

## Layout

| Module | |
|---|---|
| `hindsight-agent` | The agent. Shaded, with Byte Buddy relocated. |
| `hindsight-testapp` | A tiny application used as an instrumentation target by the tests. |

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
- **Argument names depend on the target.** Names are read from the target's bytecode, so an
  application compiled without `-parameters` yields `arg0`, `arg1`. The agent cannot fix that from
  outside a third-party jar.

## Licence

MIT.
