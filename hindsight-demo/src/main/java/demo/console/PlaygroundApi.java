package demo.console;

import dev.hindsight.playground.RunResult;
import dev.hindsight.playground.SnippetRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The paste-and-run page's back end.
 *
 * <p><b>This compiles and runs whatever is posted to it.</b> There is no way to offer a debugger
 * playground that does not, and on the machine you are sitting at it is no different from running
 * {@code javac} yourself. It is a different thing entirely once it is reachable from a network, so
 * the application binds to loopback, the page says so in as many words, and neither of those is
 * decoration.
 *
 * <p>All the work is {@link SnippetRunner}'s, which the {@code hindsight-run} command uses too.
 * Sharing it is the point: a playground that behaved differently from the command line tool would
 * be demonstrating something nobody can reproduce.
 *
 * <p>Sits in {@code demo.console} rather than {@code sample.shop}, so an agent pointed at the shop
 * does not record this class compiling other people's code.
 */
@RestController
@RequestMapping("/api/playground")
class PlaygroundApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> run(@RequestBody Map<String, String> request) {
        Path agent = attachedAgent();
        if (agent == null) {
            return failed("This demo was started without -javaagent, so there is no agent to record "
                    + "with. Restart it with the agent attached.");
        }
        return describe(new SnippetRunner(agent, TIMEOUT).run(request.get("source")));
    }

    private static Map<String, Object> describe(RunResult result) {
        return switch (result) {
            case RunResult.Rejected rejected -> failed(rejected.problem());
            case RunResult.Failed failure -> failed(failure.problem());
            case RunResult.DidNotCompile didNotCompile ->
                    Map.of("ok", false, "compileErrors", didNotCompile.errors());
            case RunResult.Ran ran -> {
                Map<String, Object> body = new HashMap<>();
                body.put("ok", true);
                body.put("exitCode", ran.exitCode());
                body.put("output", ran.output());
                body.put("recording", ran.recording());
                // Absent when nothing failed, which is the agent behaving as documented rather than
                // anything going wrong, so the page says so instead of showing an error.
                if (ran.recorded()) {
                    body.put("trace", ran.trace());
                }
                yield body;
            }
        };
    }

    /**
     * This JVM's own {@code -javaagent} argument, so a snippet is recorded by exactly the agent that
     * is already attached rather than by some other copy found on disk.
     */
    private static Path attachedAgent() {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-javaagent:")) {
                return Path.of(argument.substring("-javaagent:".length()).split("=", 2)[0]);
            }
        }
        return null;
    }

    private static Map<String, Object> failed(String problem) {
        return Map.of("ok", false, "problem", problem);
    }
}
