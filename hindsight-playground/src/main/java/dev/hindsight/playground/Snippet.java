package dev.hindsight.playground;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A snippet of Java, normalised into something that can be compiled and instrumented.
 *
 * <p>The agent selects what to record by package prefix, and a class in the default package has no
 * dots in its binary name, so it can never match one. A snippet with no package declaration would
 * therefore run perfectly and record absolutely nothing, which looks exactly like a broken agent.
 * Any package the author wrote is replaced with a known one rather than honoured, and the caller is
 * expected to say so: silently moving somebody's code is worse than telling them it moved.
 *
 * <p>This lives in {@code dev.hindsight}, which the agent never instruments, so the machinery that
 * runs a snippet can never appear inside that snippet's own trace.
 */
public record Snippet(String className, String source) {

    /** The package every snippet is compiled into, and the one the agent is pointed at. */
    public static final String PACKAGE = "snippet";

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;\\s*$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern MAIN_METHOD = Pattern.compile("static\\s+void\\s+main\\s*\\(");

    /**
     * @throws IllegalArgumentException with a message written to be shown to whoever pasted it,
     *         rather than to a log
     */
    public static Snippet of(String pasted) {
        if (pasted == null || pasted.isBlank()) {
            throw new IllegalArgumentException("There is nothing to run.");
        }
        Matcher declared = CLASS_DECLARATION.matcher(pasted);
        if (!declared.find()) {
            throw new IllegalArgumentException(
                    "No class declaration found. A snippet has to be a Java class.");
        }
        if (!MAIN_METHOD.matcher(pasted).find()) {
            throw new IllegalArgumentException("No main method found. Add a "
                    + "`public static void main(String[] args)`, because hindsight records what a "
                    + "program did while running, not what its source says.");
        }
        String withoutPackage = PACKAGE_DECLARATION.matcher(pasted).replaceFirst("");
        return new Snippet(declared.group(1), "package " + PACKAGE + ";\n" + withoutPackage);
    }

    public String qualifiedName() {
        return PACKAGE + "." + className;
    }

    public String fileName() {
        return className + ".java";
    }

    /**
     * How many lines were added at the top, so a compiler diagnostic can be reported against the
     * line the author actually wrote.
     */
    public int addedLines() {
        return 1;
    }
}
