package sample.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argument for the whole project, run against a real Spring Boot service in a fat jar.
 *
 * <p>Booting the application at all is itself a result. Advice is inlined into the application's
 * own classes, which Spring Boot loads with its own class loader out of {@code BOOT-INF}, and the
 * recorder those inlined calls reach lives on the system class path. If that delegation did not
 * hold, every instrumented method would fail with a {@code NoClassDefFoundError} rather than run.
 *
 * <p>Both requests are made once, before any assertion, so no test depends on another having run.
 */
class PlantedBugIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PORT = Pattern.compile("started on port (\\d+)");

    private static Path traceDirectory;
    private static Process application;
    private static final StringBuilder log = new StringBuilder();

    private static HttpResponse<String> workingOrder;
    private static HttpResponse<String> brokenOrder;
    private static List<Path> traces;

    @BeforeAll
    static void exerciseTheService() throws Exception {
        traceDirectory = Files.createTempDirectory("hindsight-demo-traces");
        application = start();
        try {
            int port = awaitPort();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            workingOrder = get(client, port, "order-1");
            brokenOrder = get(client, port, "order-2");
        } finally {
            stop();
        }
        traces = tracesWritten();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        for (Path trace : tracesWritten()) {
            Files.deleteIfExists(trace);
        }
        Files.deleteIfExists(traceDirectory);
    }

    @Test
    @DisplayName("the service runs normally under the agent, inside its own class loader")
    void theServiceStillWorks() {
        assertEquals(200, workingOrder.statusCode(),
                "an order with an address should have succeeded:\n" + log);
        assertTrue(workingOrder.body().contains("EH1 1AA"), workingOrder.body());
    }

    @Test
    @DisplayName("a request that succeeds leaves nothing behind")
    void successIsSilent() {
        assertEquals(1, traces.size(),
                "exactly one of the two requests failed, so there should be exactly one trace: " + traces);
    }

    @Test
    @DisplayName("the planted bug fails the request, and the failure is recorded")
    void theBrokenOrderFails() {
        assertEquals(500, brokenOrder.statusCode(), brokenOrder.body());
    }

    @Test
    @DisplayName("the stack trace does not name the method that created the null")
    void theStackTraceCannotAnswerTheQuestion() {
        // This is the premise of the project, asserted rather than claimed. The null is produced by
        // AddressBook.addressFor, which returns long before anything dereferences it, so by the
        // time the exception exists that frame is gone and no stack trace can mention it.
        String stackTrace = log.substring(log.indexOf("NullPointerException"));

        assertTrue(stackTrace.contains("ShippingCalculator.quote"),
                "the stack trace should name where the exception surfaced");
        assertFalse(stackTrace.contains("AddressBook.addressFor"),
                "if the stack trace names the origin of the null, this demo proves nothing:\n" + stackTrace);
        assertFalse(stackTrace.contains("CustomerRepository.findById"),
                "the frame that assembled the bad object is also gone by the time it throws");
    }

    @Test
    @DisplayName("the trace does name it, several events before the throw")
    void theTraceAnswersIt() throws IOException {
        JsonNode trace = JSON.readTree(Files.readString(traces.getFirst()));
        JsonNode events = trace.get("events");

        assertEquals("sample.shop.CheckoutController", trace.get("entryPoint").get("type").asText());

        JsonNode nullWasBorn = find(events, "return", "AddressBook", "addressFor");
        assertEquals("null", nullWasBorn.get("returned").asText(),
                "the recording should show exactly what that method handed back");

        JsonNode threw = find(events, "throw", "ShippingCalculator", "quote");
        assertTrue(nullWasBorn.get("seq").asInt() < threw.get("seq").asInt(),
                "the null must be recorded as created before it is recorded as fatal");

        // And the object carrying it is visible on the way up, which is how you follow it.
        JsonNode carried = find(events, "return", "CustomerRepository", "findById");
        assertTrue(carried.get("returned").asText().contains("address=null"), carried.toString());
    }

    @Test
    @DisplayName("a trace covers one request on one worker thread")
    void theTraceIsRequestScoped() throws IOException {
        JsonNode trace = JSON.readTree(Files.readString(traces.getFirst()));

        assertTrue(trace.get("thread").asText().contains("exec"),
                "expected a Tomcat worker thread, got " + trace.get("thread").asText());
        assertEquals(0, trace.get("truncation").get("droppedToRing").asLong(),
                "a request this small should fit in the buffer many times over");
        // The successful request ran on the same pool and must not appear in this document.
        assertFalse(Files.readString(traces.getFirst()).contains("order-1"),
                "a second request leaked into this trace");
    }

    // ---- harness --------------------------------------------------------------------------------

    private static Process start() throws IOException {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + required("hindsight.agent.jar"),
                "-Dhindsight.packages=sample.shop",
                "-Dhindsight.trace.dir=" + traceDirectory,
                "-jar", required("hindsight.demo.jar"),
                "--server.port=0")
                .redirectErrorStream(true)
                .start();
        Thread reader = new Thread(() -> {
            try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (log) {
                        log.append(line).append('\n');
                    }
                }
            } catch (IOException closed) {
                // The process went away; whatever was read is what there is.
            }
        }, "demo-output");
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    private static int awaitPort() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (log) {
                Matcher port = PORT.matcher(log);
                if (port.find()) {
                    return Integer.parseInt(port.group(1));
                }
            }
            if (!application.isAlive()) {
                throw new AssertionError("the demo exited before it started listening:\n" + log);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the demo never started listening:\n" + log);
    }

    private static HttpResponse<String> get(HttpClient client, int port, String order)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/checkout/" + order))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void stop() throws InterruptedException {
        application.destroy();
        if (!application.waitFor(30, TimeUnit.SECONDS)) {
            application.destroyForcibly();
        }
    }

    private static List<Path> tracesWritten() throws IOException {
        if (traceDirectory == null || !Files.isDirectory(traceDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(traceDirectory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
        }
    }

    private static JsonNode find(JsonNode events, String kind, String simpleType, String method) {
        for (JsonNode event : events) {
            if (kind.equals(event.get("kind").asText())
                    && event.get("type").asText().endsWith("." + simpleType)
                    && method.equals(event.get("method").asText())) {
                return event;
            }
        }
        throw new AssertionError("no " + kind + " event for " + simpleType + "." + method
                + " in " + events.toPrettyString());
    }

    private static String required(String property) {
        String configured = System.getProperty(property);
        if (configured == null) {
            throw new IllegalStateException(
                    property + " is not set; these tests are driven by failsafe, run ./mvnw verify");
        }
        return configured;
    }
}
