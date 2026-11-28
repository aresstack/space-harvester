package com.aresstack.spaceharvester.provingground.run;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * How a project is run.
 *
 * @param actionTimeout   how long one action or assertion may wait for its element; zero = Playwright's default
 * @param outputDirectory where traces ({@code <suite>/<case>/<user>.zip}) and failure screenshots go; null = none
 * @param only            the test cases to run as {@code <suite>/<case>}; empty = all
 * @param clock           the clock for one-time codes and dates
 */
public record RunOptions(Duration actionTimeout, Path outputDirectory, List<String> only, Clock clock) {

    public RunOptions {
        actionTimeout = actionTimeout == null ? Duration.ZERO : actionTimeout;
        only = only == null ? List.of() : List.copyOf(only);
        clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public static RunOptions defaults() {
        return new RunOptions(Duration.ZERO, null, List.of(), null);
    }

    public RunOptions withActionTimeout(Duration value) {
        return new RunOptions(value, outputDirectory, only, clock);
    }

    public RunOptions withOutputDirectory(Path value) {
        return new RunOptions(actionTimeout, value, only, clock);
    }

    public RunOptions withOnly(List<String> value) {
        return new RunOptions(actionTimeout, outputDirectory, value, clock);
    }

    public RunOptions withClock(Clock value) {
        return new RunOptions(actionTimeout, outputDirectory, only, value);
    }
}
