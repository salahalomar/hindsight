package demo.console;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lets the console page read the traces the agent wrote, so a failing request and the recording it
 * produced can be looked at side by side.
 *
 * <p>Deliberately outside {@code sample.shop}. The agent is pointed at that package, so putting the
 * console there would record the console reading the traces, into the traces.
 *
 * <p>This is demo tooling and is not part of the agent. It is also, for the same reason, the one
 * place in this repository that takes a file name from a URL, so it treats that name as hostile.
 */
@RestController
@RequestMapping("/api/traces")
class TraceApi {

    /** Exactly the shape TraceWriter produces, and nothing else. */
    private static final Pattern TRACE_NAME = Pattern.compile("hindsight-[0-9A-Za-z_-]{1,120}\\.json");

    private final Path directory = Path.of(
            System.getProperty("hindsight.trace.dir", "hindsight-traces")).toAbsolutePath().normalize();

    @GetMapping
    List<Map<String, Object>> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> TRACE_NAME.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.<Path, String>comparing(file -> file.getFileName().toString()).reversed())
                    .map(TraceApi::describe)
                    .toList();
        }
    }

    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> read(@PathVariable String name) throws IOException {
        Path file = resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(file));
    }

    /**
     * Returns null for anything that is not a plain trace file directly inside the trace directory.
     * The pattern already forbids dots and separators, and the containment check is the belt to its
     * braces: a name is not trusted just because it looked reasonable.
     */
    private Path resolve(String name) {
        if (name == null || !TRACE_NAME.matcher(name).matches()) {
            return null;
        }
        Path candidate = directory.resolve(name).toAbsolutePath().normalize();
        return candidate.getParent() != null && candidate.getParent().equals(directory) ? candidate : null;
    }

    private static Map<String, Object> describe(Path file) {
        try {
            return Map.of("name", file.getFileName().toString(), "bytes", Files.size(file));
        } catch (IOException unreadable) {
            return Map.of("name", file.getFileName().toString(), "bytes", -1L);
        }
    }
}
