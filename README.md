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

**Step 4 of 8.** The agent records the call tree of any package you select, with arguments, return
values and thrown exceptions summarised safely, into a bounded per-thread ring buffer. Traces are
printed on demand; writing them to a file on an escaping exception is step 5.

| Step | | |
|---|---|---|
| 1 | Agent skeleton: premain, manifest, class observation | **done** |
| 2 | Instrument one method via Byte Buddy Advice; reentrancy guard | **done** |
| 3 | Package-prefix filtering and a bounded per-thread ring buffer | **done** |
| 4 | Value summarisation, with references never retained | **done** |
| 5 | Dump-on-exception and a versioned trace schema | next |
| 6 | HTML viewer: call tree, timeline scrubber, value inspector | |
| 7 | Overhead benchmark: throughput and p99, agent off vs on | |
| 8 | Demo: a null born three layers below where it throws | |

## Try it

Requires JDK 25. Maven is not required — the wrapper fetches it.

```bash
./mvnw verify
```

```bash
java -Dhindsight.packages=sample.testapp -Dhindsight.dump=true -javaagent:hindsight-agent/target/hindsight-agent.jar -jar hindsight-testapp/target/hindsight-testapp.jar
```

```
[hindsight] agent loaded - 0.1.0-SNAPSHOT on JVM 25.0.1
[hindsight] packages=sample.testapp, buffer=1024 events/thread, maxDepth=256, values=summary/64, dump=true
hello from testapp
[hindsight] trace for main: 8 events
[hindsight] +  0.000ms -> sample.testapp.TestApp.main(String[0]@26adfd2d)
[hindsight] +  0.137ms   -> sample.testapp.TestApp.greet(String "testapp")
[hindsight] +  2.814ms     -> sample.testapp.Greeting.normalise()
[hindsight] +  2.829ms     <- sample.testapp.Greeting.normalise returned String "testapp"
[hindsight] +  2.833ms     -> sample.testapp.Greeting.render(String "testapp")
[hindsight] +  2.883ms     <- sample.testapp.Greeting.render returned String "hello from testapp"
[hindsight] +  2.886ms   <- sample.testapp.TestApp.greet returned String "hello from testapp"
[hindsight] +  2.914ms <- sample.testapp.TestApp.main returned void
```

Run it without `-Dhindsight.packages` and the agent attaches, records nothing, and says so. Nothing
is instrumented by default — an agent that picks something sensible-looking on its own is an agent
that surprises people in production.

## Configuration

All settings are `-Dhindsight.*` system properties, read once at startup. A mistyped value is
reported and replaced with the default; the agent never refuses to start over one.

| Property | Default | |
|---|---|---|
| `hindsight.packages` | *(none)* | Comma-separated package prefixes to record. Nothing is recorded until this is set. |
| `hindsight.buffer.events` | `1024` | Events held per thread, rounded up to a power of two. Roughly 38KB per traced thread at the default. |
| `hindsight.depth.max` | `256` | Call depth past which frames are counted but not stored. |
| `hindsight.values` | `summary` | `summary` renders values; `type` reports type names and calls nothing belonging to the application. |
| `hindsight.value.length` | `64` | Characters kept per rendered value. A value that was cut reports its real length. |
| `hindsight.dump` | `false` | Print each completed outermost frame as a call tree. |

`hindsight.packages` chooses what the agent *may* record. It never overrides the exclusion list:
asking for `java` does not get you an instrumented `java.lang.String`.

## Layout

| Module | |
|---|---|
| `hindsight-agent` | The agent. Shaded, with Byte Buddy relocated. |
| `hindsight-testapp` | A tiny application used as an instrumentation target by the tests. |

Inside the agent, `dev.hindsight.agent` decides what to instrument and runs once at startup;
`dev.hindsight.runtime` is what application threads call into, twice per traced invocation;
`dev.hindsight.trace` renders what was recorded and runs on demand. The split is a standing
reminder of which code is allowed to be expensive.

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

**Values are summarised, and the rules are the interesting part.** Reading a value means calling
application code, so:

- **Collections, maps and arrays are never rendered through `toString`.** A million-element list
  builds the entire string before any length cap could apply. They are described structurally
  instead — `ArrayList[1000000]@3f1a2b` — which is what makes a huge container genuinely safe rather
  than merely trimmed after the damage. `size()` is application code too, and is guarded.
- **Boxed primitives are matched by exact class**, not by `instanceof Number`. A user-written
  `Number` subclass has a user-written `toString`, and the entire point of that list is that its
  renderings cannot be overridden. Enum constants are read through `name()`, which is final, and
  through `getDeclaringClass()`, since a constant with a body is an anonymous subclass with no
  simple name.
- **Classes that do not override `toString` are never asked.** The inherited implementation only
  repeats the identity already being printed. The check is cached per class with `ClassValue`.
- **Anything that does get called is wrapped.** A throwing `toString` is reported as having thrown,
  which is itself worth knowing; two objects whose `toString` implementations call each other end in
  a caught `StackOverflowError` rather than a dead thread.

What is deliberately *not* defended against is a `toString` that is merely slow. Bounding that needs
a watchdog thread and the ability to interrupt application code mid-call, which is more dangerous
than the problem. `-Dhindsight.values=type` is the answer there, and it calls nothing at all.

**The buffer cannot hold an application object.** Every slot in it is a `String`, `int`, `long` or
`byte`; values are reduced to text at capture time, by the caller, before they reach it. That turns
"never retain a captured object" from a rule someone has to remember into a property of the type.
It is held as parallel arrays rather than an array of event objects for the same reason — an object
per event would mean two allocations per traced invocation, which is precisely the collection
pressure a tool for diagnosing production has no business adding.

**Buffers are `ThreadLocal` and there is no registry of them.** A registry would need weak
references and a cleanup story, and holding strong ones would leak badly against virtual threads.
The cost is that a dump can only ever be of the calling thread's own history — which is the right
shape for request-scoped tracing, and if recording across threads is ever wanted it deserves a real
design rather than a wider data structure now.

**The buffer resets when the outermost instrumented frame returns**, so it means "this request"
rather than a rolling mixture of unrelated ones. That is the property step 5 needs to dump
something coherent when an exception escapes.

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
- **Constructors are not traced.** Advice on a constructor is workable — arguments are readable,
  and only `this` is unavailable before initialisation — but it doubles the instrumentation surface
  and belongs with a step that is about widening what gets recorded, not choosing it.
- **Every method in scope is traced, including trivial accessors.** Skipping getters would be a
  heuristic, and a tool that silently omits frames is worse than a noisy one. Scope is the control.
- **A slow `toString` is not bounded.** See above; `-Dhindsight.values=type` is the escape hatch.
- **Argument names depend on the target.** Names are read from the target's bytecode, so an
  application compiled without `-parameters` yields `arg0`, `arg1`. The agent cannot fix that from
  outside a third-party jar.

## Licence

MIT.
