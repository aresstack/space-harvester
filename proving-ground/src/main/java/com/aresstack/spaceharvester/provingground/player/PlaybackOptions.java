package com.aresstack.spaceharvester.provingground.player;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * How a test case is played back.
 *
 * @param actionTimeout        how long one action or assertion may wait for its element; zero = Playwright's default
 * @param variables            values of the {@code {{name}}} references in the steps (passwords:
 *                             {@code secret.<field>}); secrets never appear in results or logs
 * @param traceFile            where the Playwright trace of the run goes (open it in the Playwright Trace Viewer), or
 *                             null
 * @param screenshotOnFailure  where the screenshot of the page at the failing step goes, or null; actual and diff
 *                             images of a failed screenshot comparison go next to it
 * @param screenshotReferences where the reference images of the test case's screenshot comparisons are
 *                             ({@code <case>-snapshots}, see {@link Screenshots}), or null
 */
public record PlaybackOptions(Duration actionTimeout, Map<String, String> variables, Path traceFile,
                              Path screenshotOnFailure, Path screenshotReferences) {

    public PlaybackOptions {
        actionTimeout = actionTimeout == null ? Duration.ZERO : actionTimeout;
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** Playwright's timeouts, no variables, no trace, no screenshot, no references. */
    public static PlaybackOptions defaults() {
        return new PlaybackOptions(Duration.ZERO, Map.of(), null, null, null);
    }

    public PlaybackOptions withTrace(Path file) {
        return new PlaybackOptions(actionTimeout, variables, file, screenshotOnFailure, screenshotReferences);
    }

    public PlaybackOptions withScreenshotOnFailure(Path file) {
        return new PlaybackOptions(actionTimeout, variables, traceFile, file, screenshotReferences);
    }

    public PlaybackOptions withScreenshotReferences(Path directory) {
        return new PlaybackOptions(actionTimeout, variables, traceFile, screenshotOnFailure, directory);
    }

    public PlaybackOptions withVariables(Map<String, String> values) {
        return new PlaybackOptions(actionTimeout, values, traceFile, screenshotOnFailure, screenshotReferences);
    }

    public PlaybackOptions withActionTimeout(Duration timeout) {
        return new PlaybackOptions(timeout, variables, traceFile, screenshotOnFailure, screenshotReferences);
    }
}
