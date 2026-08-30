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

**All eight steps done.** The agent records, writes a trace when a request fails, and the viewer
opens it. The overhead is measured rather than asserted, and there is a Spring Boot service with a
planted bug to show what the trace answers that a stack trace cannot.

| Step | | |
|---|---|---|
| 1 | Agent skeleton: premain, manifest, class observation | **done** |
| 2 | Instrument one method via Byte Buddy Advice; reentrancy guard | **done** |
| 3 | Package-prefix filtering and a bounded per-thread ring buffer | **done** |
| 4 | Value summarisation, with references never retained | **done** |
| 5 | Dump-on-exception and a versioned trace schema | **done** |
| 6 | HTML viewer: call tree, timeline scrubber, value inspector | **done** |
| 7 | Overhead benchmark: throughput and p99, agent off vs on | **done** |
| 8 | Demo: a null born three layers below where it throws | **done** |

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

## What a trace is for

`hindsight-demo` is a Spring Boot service with one planted bug. `AddressBook.addressFor` returns
`null` for an unknown order; that null is stored inside a `Customer`, handed back up, and only
dereferenced later by `ShippingCalculator`.

```bash
java -Dhindsight.packages=sample.shop -javaagent:hindsight-agent/target/hindsight-agent.jar -jar hindsight-demo/target/hindsight-demo.jar
```

```
curl localhost:8080/checkout/order-1   ->  200  {"orderId":"order-1","postcode":"EH1 1AA","pence":420}
curl localhost:8080/checkout/order-2   ->  500
```

The JVM's account of the failure:

```
java.lang.NullPointerException: Cannot invoke "sample.shop.Address.postcode()"
        because the return value of "sample.shop.Customer.address()" is null
    at sample.shop.ShippingCalculator.quote(ShippingCalculator.java:14)
    at sample.shop.CheckoutService.checkout(CheckoutService.java:18)
    at sample.shop.CheckoutController.checkout(CheckoutController.java:22)
```

Three frames, and **the method that produced the null is not one of them**. It returned long before
anything dereferenced it, so no stack trace can mention it. The trace written alongside can:

```
seq  0    0.000ms  -> CheckoutController.checkout(String "order-2")
seq  1    0.002ms    -> CheckoutService.checkout(String "order-2")
seq  2    0.003ms      -> CustomerRepository.findById(String "order-2")
seq  5    0.009ms        -> AddressBook.addressFor(String "order-2")
seq  6    0.011ms        <- AddressBook.addressFor returned null              <-- born here
seq  7    0.059ms      <- CustomerRepository.findById returned Customer[..., address=null]
seq  8    0.066ms      -> ShippingCalculator.quote(Customer[..., address=null])
seq 11    0.165ms      <! ShippingCalculator.quote threw NullPointerException  <-- died here
seq 13    0.176ms  <! CheckoutController.checkout threw NullPointerException
```

Five events and 154 microseconds separate where the null was created from where it became fatal,
and you can watch it travel: returned bare, then carried inside a `Customer`, then passed along.

An integration test asserts both halves of that claim — that `AddressBook.addressFor` is absent from
the stack trace, and present in the recording, returning `null`, before the throw.

## Trace format

Traces are written to `hindsight-traces/` when an exception escapes the outermost instrumented
frame. The document names its own schema on the first line:

```json
{
  "schema": "hindsight.trace/1",
  "agent": "0.1.0-SNAPSHOT",
  "recordedAt": "2026-08-28T12:05:04.232049Z",
  "thread": "main",
  "entryPoint": {"type": "sample.testapp.TestApp", "method": "main"},
  "truncation": {"droppedToRing": 0, "beyondMaxDepth": 0},
  "events": [ ... ]
}
```

A reader that does not recognise the version is expected to refuse rather than guess. The number
gets bumped for anything a reader could misinterpret — a renamed field, a removed one, a changed
meaning; adding an optional field that older readers can ignore does not need it.

Each event carries `seq`, `kind` (`enter`, `return`, `throw`), `depth`, `offsetNanos`, `type` and
`method`, plus exactly one of `arguments`, `returned` or `thrown`. Naming the payload rather than
using one generic `detail` field means a reader never has to consult the kind to know what it is
looking at. Times are offsets from the first event in nanoseconds; `recordedAt` is the only
absolute clock in the document, because a `nanoTime` reading means nothing outside the process that
took it. `entryPoint` is recorded rather than derived from event zero, which stops being the entry
point as soon as the ring has dropped anything.

## What it costs

```bash
./mvnw package && java -jar hindsight-benchmark/target/hindsight-benchmark.jar
```

Five forks per configuration, interleaved, on an M-series Mac under JDK 25. The workload is a
service-layer request — validate against a set, look up a map, build a couple of strings — with
**14 instrumented method calls per request**, a figure the harness gets by asking the agent rather
than by counting into a comment.

| configuration | throughput | p50 | p99 | p99.9 | added per instrumented call |
|---|---|---|---|---|---|
| no agent | 13,587,000/s | — | 0.17µs | 0.96µs | — |
| attached, nothing selected | 13,561,000/s | — | 0.17µs | 0.88µs | **0ns** |
| recording, types only | 920,900/s | 1.04µs | 1.38µs | 2.58µs | **72ns** |
| recording, values summarised | 654,400/s | 1.29µs | 3.29µs | 4.42µs | **104ns** |

**Attaching the agent without selecting any packages costs nothing measurable.** Recording costs
roughly **70–100ns per instrumented method call**, depending on whether values are rendered.

That per-call figure is the number worth quoting, because it is the only one that transfers. The
throughput ratio belongs to this workload: an uninstrumented request here costs about 74ns, so
fourteen recorded method boundaries dominate it completely. Code that does any I/O at all would
show a ratio nowhere near this one. Multiply 70–100ns by however many of your own methods sit
inside a request and you have the answer for your service.

Honesty notes, because a benchmark without them is a marketing claim:

- Throughput and latency are measured in **separate passes**. A `nanoTime` pair costs ~17ns, which
  is a rounding error against an instrumented request and a large fraction of an uninstrumented
  one; timing every request while measuring throughput would tax the baseline hardest and flatter
  the agent.
- Forks are **interleaved**, not grouped by configuration. Grouped, thermal drift put the idle
  agent at 1.16x *faster* than no agent. Interleaved, it sits at 1.00x, which is the truth.
- The p50 column is blank for the two fast configurations because a 74ns request cannot be
  meaningfully timed with a 17ns clock pair.
- Every fork's throughput is printed by the harness so the spread can be judged rather than trusted.
- One machine, not a controlled environment. Treat as an order of magnitude.

Memory is the other half of the cost: a traced thread holds one ring buffer, about 38KB at the
default 1024 events, allocated on that thread's first recorded event and collected when it dies.

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
| `hindsight.trace.dir` | `hindsight-traces` | Where trace files are written. |
| `hindsight.trace.max` | `50` | Trace files per JVM run. `0` switches tracing off. |

`hindsight.packages` chooses what the agent *may* record. It never overrides the exclusion list:
asking for `java` does not get you an instrumented `java.lang.String`.

## Layout

| Module | |
|---|---|
| `hindsight-agent` | The agent. Shaded, with Byte Buddy relocated. |
| `hindsight-testapp` | A tiny application used as an instrumentation target by the tests. |
| `hindsight-viewer` | One HTML file. Not a Maven module; there is nothing to build. |
| `hindsight-benchmark` | The overhead harness and the workload it measures. |
| `hindsight-demo` | A Spring Boot service with the planted bug, and the test that pins the claim. |

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

**Advice reaches the recorder from inside a Spring Boot fat jar.** This was the open risk from the
very first commit: Byte Buddy inlines advice into the target method, so the recorder has to be
resolvable by the class loader of the class being instrumented — and Spring Boot loads application
classes from `BOOT-INF` with its own loader while the agent sits on the system class path. The
delegation holds, and `hindsight-demo` exercises it rather than reasoning about it. Had it not held,
every instrumented method would have failed with `NoClassDefFoundError`.

**The viewer is one file with no build step and no dependencies.** It has to open from a `file://`
URL on a laptop with no network, because that is the situation somebody reading a trace is usually
in. A test asserts the file contains no `://` anywhere at all — one stray stylesheet reference and
it silently renders as unstyled text at exactly the wrong moment.

**The trace is written synchronously, on the failing thread.** That cost is deliberate. It happens
only on a request that has already failed, and a queue with a writer thread would trade the latency
for the chance of losing the trace at shutdown — which is exactly when a failing process is most
likely to be going away. The file count is capped for the same reason in reverse: an application
failing in a loop must not be able to fill a disk, because an agent that turns a bug into an outage
has made things worse than it found them.

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
- **A trace covers one thread.** Buffers are `ThreadLocal`, so a dump is the failing thread's own
  history and nothing else. For request-scoped work that is the whole story; for work that hops
  threads it is not, and saying so is better than implying otherwise.
- **A slow `toString` is not bounded.** See above; `-Dhindsight.values=type` is the escape hatch.
- **Argument names depend on the target.** Names are read from the target's bytecode, so an
  application compiled without `-parameters` yields `arg0`, `arg1`. The agent cannot fix that from
  outside a third-party jar.

## Licence

MIT.
