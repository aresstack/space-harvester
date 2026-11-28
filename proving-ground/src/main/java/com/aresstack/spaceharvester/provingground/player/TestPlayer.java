package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionException;
import com.aresstack.spaceharvester.provingground.expressions.MapResolutionContext;
import com.aresstack.spaceharvester.provingground.expressions.ResolveTemplateUseCase;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonObject;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserStartException;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Plays a test case back by running the Java code Playwright's own generator wrote for each step while recording
 * (recorder language {@code java}), compiled with the JDK and executed over the Playwright Java API, i.e. by
 * Playwright4j. Nothing of Playwright is rebuilt here: no own action mapping, only page binding, run-time values and
 * results. The first failing step ends the run; its page is photographed and the trace is kept.
 *
 * <p>The caller owns the browser context (fresh for a run, so the test starts from a defined state) and calls from the
 * thread that uses it, like the rest of Playwright Java.</p>
 */
public final class TestPlayer {

    /**
     * Plays {@code testCase} in a browser of its own: started by the browser runtime with a throw-away profile and
     * WITHOUT accepting Firefox's "resend data" prompt (a test never re-submits a form unasked), one fresh context
     * with {@code contextOptions} (viewport, downloads, storage state, locale ... as Playwright offers them; null =
     * Playwright's defaults). Browser and context are closed afterwards.
     */
    public RunResult playInFreshBrowser(BrowserRuntime runtime, boolean headless, Browser.NewContextOptions contextOptions,
                                        TestCase testCase, PlaybackOptions options) throws BrowserStartException {
        try (RunningBrowser browser = runtime.ephemeral(headless, null, false);
             BrowserContext context = browser.browser().newContext(contextOptions)) {
            return play(testCase, context, options);
        }
    }

    /**
     * Plays {@code testCase} in {@code context}; values come from {@link PlaybackOptions#variables()} (a value of a
     * {@code secret.*} variable never shows in the result).
     */
    public RunResult play(TestCase testCase, BrowserContext context, PlaybackOptions options) {
        if (options.actionTimeout().toMillis() > 0) {
            context.setDefaultTimeout(options.actionTimeout().toMillis());
        }
        boolean tracing = options.traceFile() != null;
        if (tracing) {
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true)
                    .setTitle(testCase.name()));
        }
        RunResult result;
        try {
            result = play(testCase, new MapEnvironment(context, options.variables()), new Pages(), options);
        } finally {
            if (tracing) {
                context.tracing().stop(new Tracing.StopOptions().setPath(options.traceFile()));
            }
        }
        return new RunResult(result.testCase(), result.steps(), options.traceFile(), result.screenshot());
    }

    /**
     * Plays {@code testCase} with the contexts and values of {@code environment}, on the pages of {@code pages} (a
     * precondition's pages stay open for the test case). Tracing is the caller's (it owns the contexts); the trace
     * file of {@code options} is not used here.
     *
     * <p>What runs is the Java code Playwright's own generator wrote for each step while recording, compiled with the
     * JDK ({@link PlaywrightCode}); this class only binds the pages, resolves {@code {{...}}} and collects the
     * results.</p>
     */
    public RunResult play(TestCase testCase, StepEnvironment environment, Pages pages, PlaybackOptions options) {
        if (options.actionTimeout().toMillis() > 0) {
            // Playwright's generated assertions (assertThat ...) wait as long as an action may
            PlaywrightAssertions.setDefaultAssertionTimeout(options.actionTimeout().toMillis());
        }
        PlaywrightCode code = PlaywrightCode.compile(testCase);
        Path failureShot = options.screenshotOnFailure();
        Screenshots screenshots = new Screenshots(options.screenshotReferences(),
                failureShot == null ? null : failureShot.toAbsolutePath().getParent(), options.actionTimeout());
        List<StepResult> results = new ArrayList<>();
        Path screenshot = null;
        boolean failed = false;
        for (int i = 0; i < testCase.size(); i++) {
            TestStep step = testCase.step(i);
            String description = environment.describe(step);
            if (failed) {
                results.add(new StepResult(i, description, StepResult.Status.SKIPPED, 0, null));
                continue;
            }
            long started = System.nanoTime();
            try {
                perform(i, step, code, environment, pages, screenshots);
                waitForMinimum(environment.minDuration(step), started);
                results.add(new StepResult(i, description, StepResult.Status.PASSED, millisSince(started), null));
            } catch (Throwable failure) {
                if (failure instanceof Error error && !(failure instanceof AssertionError)) {
                    throw error;
                }
                failed = true;
                results.add(new StepResult(i, description, StepResult.Status.FAILED, millisSince(started),
                        environment.redact(firstLine(failure))));
                screenshot = photograph(pages.get(step.pageGuid()), options.screenshotOnFailure());
            }
        }
        return new RunResult(testCase.name(), results, null, screenshot);
    }

    private void perform(int index, TestStep step, PlaywrightCode code, StepEnvironment environment, Pages pages,
                         Screenshots screenshots) throws Throwable {
        String problem = code.problem(index);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        BrowserContext context = environment.context(step);
        screenshots.bind(context);
        java.util.function.UnaryOperator<String> resolve = value -> environment.resolve(value, step);
        if ("openPage".equals(step.name())) {
            if (pages.has(step.pageGuid())) {
                return; // a precondition opened it already
            }
            List<Page> opened = code.run(index, context, null, resolve, screenshots);
            if (!opened.isEmpty()) {
                pages.put(step.pageGuid(), opened.get(0));
                pages.setCurrent(opened.get(0));
            }
            return;
        }
        Page page = pages.get(step.pageGuid());
        boolean ownPage = step.signal("popup") == null && PlaywrightCode.opensOwnPage(step.code());
        if (!ownPage && (page == null || page.isClosed() || page.context() != context)) {
            // A test case that starts without openPage (edited, or recorded into an existing page), or another user.
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            pages.put(step.pageGuid(), page);
        }
        if (!ownPage) {
            pages.setCurrent(page);
        }
        List<Page> opened = code.run(index, context, page, resolve, screenshots);
        if (opened.isEmpty()) {
            return;
        }
        JsonObject popup = step.signal("popup");
        if (popup == null) {
            // the step's code opened its page itself (Page page = context.newPage()): later steps act on that page
            pages.put(step.pageGuid(), opened.get(0));
        } else if (popup.has("popupPageGuid")) {
            pages.put(popup.get("popupPageGuid").getAsString(), opened.get(0));
        }
        pages.setCurrent(opened.get(0));
    }

    private static Path photograph(Page page, Path target) {
        if (page == null || target == null || page.isClosed()) {
            return null;
        }
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(true));
            return target;
        } catch (Exception notTaken) {
            return null;
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** {@code text} with the value of every {@code secret.*} variable replaced by its reference. */
    static String redacted(String text, Map<String, String> variables) {
        String result = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String value = variable.getValue();
            if (variable.getKey().startsWith("secret.") && value != null && !value.isEmpty()) {
                result = result.replace(value, "{{" + variable.getKey() + "}}");
            }
        }
        return result;
    }

    private static void waitForMinimum(Duration minimum, long startedNanos) {
        long remaining = minimum.toNanos() - (System.nanoTime() - startedNanos);
        if (remaining > 0) {
            try {
                Thread.sleep(Duration.ofNanos(remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Abgebrochen", interrupted);
            }
        }
    }

    /** A single test case in one context, values from a map. */
    private record MapEnvironment(BrowserContext context, Map<String, String> variables) implements StepEnvironment {

        @Override
        public BrowserContext context(TestStep step) {
            return context;
        }

        @Override
        public String resolve(String value, TestStep step) {
            try {
                return ResolveTemplateUseCase.resolve(value, new MapResolutionContext(variables));
            } catch (ExpressionException missing) {
                throw new IllegalStateException(missing.getMessage(), missing);
            }
        }

        @Override
        public String redact(String text) {
            return redacted(text, variables);
        }
    }

    /** Playwright's message up to its call log (expected and received value), as one line. */
    static String firstLine(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        StringBuilder line = new StringBuilder();
        for (String part : message.strip().split("\\R")) {
            String trimmed = part.strip();
            if (trimmed.startsWith("Call log:") || trimmed.startsWith("====")) {
                break;
            }
            if (!trimmed.isEmpty()) {
                line.append(line.length() == 0 ? "" : " · ").append(trimmed);
            }
        }
        return line.length() == 0 ? failure.getClass().getSimpleName() : line.toString();
    }
}
