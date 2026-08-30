package demo.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

/**
 * Turns a failed request into a body the console can show, including the stack trace.
 *
 * <p>Owned by the demo rather than configured through Spring's {@code server.error} properties,
 * which is both more portable across Spring versions and more honest: exposing stack traces over
 * HTTP is a thing this demo does deliberately to make a point, not a setting anyone should carry
 * into a real service.
 *
 * <p>This runs after the exception has already escaped the controller method, so the agent has
 * recorded the throw and written its trace before this is reached. Handling the exception here does
 * not hide it from the recording.
 */
@RestControllerAdvice
class ConsoleErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleErrors.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> failed(Exception failure) {
        // Handling an exception is not the same as making it disappear. Spring only logs what
        // reaches it unhandled, so once this advice exists the logging is this advice's job.
        LOG.error("request failed", failure);

        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", failure.getClass().getName(),
                "message", String.valueOf(failure.getMessage()),
                "trace", rendered.toString()));
    }
}
