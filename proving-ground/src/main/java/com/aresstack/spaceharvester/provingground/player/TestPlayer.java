package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionException;
import com.aresstack.spaceharvester.provingground.expressions.MapResolutionContext;
import com.aresstack.spaceharvester.provingground.expressions.ResolveTemplateUseCase;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserStartException;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.KeyboardModifier;
import com.microsoft.playwright.options.MouseButton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Plays a test case back over the original Playwright Java API, without any UI: each step becomes the call Playwright's
 * own code generator writes for it ({@code JavaLanguageGenerator}) and its recorder runner performs
 * ({@code recorderRunner.performAction}): locators from the recorded selectors (strict), auto-waiting actions and web-first
 * assertions, no sleeps. The first failing step ends the run; its page is photographed and the trace is kept.
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
     */
    public RunResult play(TestCase testCase, StepEnvironment environment, Pages pages, PlaybackOptions options) {
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
                perform(step, environment, pages, options);
                waitForMinimum(environment.minDuration(step), started);
                results.add(new StepResult(i, description, StepResult.Status.PASSED, millisSince(started), null));
            } catch (RuntimeException | AssertionError failure) {
                failed = true;
                results.add(new StepResult(i, description, StepResult.Status.FAILED, millisSince(started),
                        environment.redact(firstLine(failure))));
                screenshot = photograph(pages.get(step.pageGuid()), options.screenshotOnFailure());
            }
        }
        return new RunResult(testCase.name(), results, null, screenshot);
    }

    private void perform(TestStep step, StepEnvironment environment, Pages pages, PlaybackOptions options) {
        String name = step.name();
        BrowserContext context = environment.context(step);
        if ("openPage".equals(name)) {
            if (!pages.has(step.pageGuid())) {
                Page page = context.newPage();
                pages.put(step.pageGuid(), page);
                pages.setCurrent(page);
                String url = step.url();
                if (url != null && !url.equals("about:blank") && !url.startsWith("chrome://")) {
                    page.navigate(environment.resolve(url, step));
                }
            }
            return;
        }
        Page page = pages.get(step.pageGuid());
        if (page == null || page.context() != context) {
            // A test case that starts without openPage (edited, or recorded into an existing page).
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            pages.put(step.pageGuid(), page);
        }
        pages.setCurrent(page);
        Page subject = page;
        if (step.signal("dialog") != null) {
            // As Playwright's generator: the recorded dialog is dismissed.
            subject.onceDialog(dialog -> dialog.dismiss());
        }
        JsonObject popup = step.signal("popup");
        JsonObject download = step.signal("download");
        Runnable call = () -> act(step, subject, environment, options);
        if (popup != null) {
            Page opened = subject.waitForPopup(call);
            if (popup.has("popupPageGuid")) {
                pages.put(popup.get("popupPageGuid").getAsString(), opened);
            }
            pages.setCurrent(opened);
        } else if (download != null) {
            subject.waitForDownload(call);
        } else {
            call.run();
        }
    }

    private void act(TestStep step, Page page, StepEnvironment environment, PlaybackOptions options) {
        JsonObject action = step.actionJson();
        String name = step.name();
        // Assertions take the action timeout when one is set, else Playwright's assertion default.
        Double timeout = options.actionTimeout().toMillis() > 0 ? (double) options.actionTimeout().toMillis() : null;
        switch (name) {
            case "closePage" -> page.close();
            case "navigate" -> page.navigate(environment.resolve(step.url(), step));
            case "click" -> {
                Locator.ClickOptions click = new Locator.ClickOptions();
                String button = string(action, "button");
                if (button != null && !"left".equals(button)) {
                    click.setButton(MouseButton.valueOf(button.toUpperCase(java.util.Locale.ROOT)));
                }
                List<KeyboardModifier> modifiers = modifiers(action);
                if (!modifiers.isEmpty()) {
                    click.setModifiers(modifiers);
                }
                int clickCount = action.has("clickCount") ? action.get("clickCount").getAsInt() : 1;
                if (clickCount > 1) {
                    click.setClickCount(clickCount);
                }
                JsonObject position = action.has("position") && action.get("position").isJsonObject()
                        ? action.getAsJsonObject("position") : null;
                if (position != null) {
                    click.setPosition(position.get("x").getAsDouble(), position.get("y").getAsDouble());
                }
                locator(page, step).click(click);
            }
            case "hover" -> locator(page, step).hover();
            case "check" -> locator(page, step).check();
            case "uncheck" -> locator(page, step).uncheck();
            case "fill" -> locator(page, step).fill(environment.resolve(step.text(), step));
            case "press" -> {
                List<String> keys = new ArrayList<>();
                for (KeyboardModifier modifier : modifiers(action)) {
                    keys.add(modifier == KeyboardModifier.CONTROLORMETA ? "ControlOrMeta"
                            : modifier.name().charAt(0) + modifier.name().substring(1).toLowerCase(java.util.Locale.ROOT));
                }
                keys.add(string(action, "key"));
                locator(page, step).press(String.join("+", keys));
            }
            case "select" -> {
                List<String> values = new ArrayList<>();
                JsonElement chosen = action.get("options");
                if (chosen != null && chosen.isJsonArray()) {
                    for (JsonElement value : chosen.getAsJsonArray()) {
                        values.add(environment.resolve(value.getAsString(), step));
                    }
                }
                locator(page, step).selectOption(values.toArray(String[]::new));
            }
            case "setInputFiles" -> {
                List<Path> files = new ArrayList<>();
                JsonElement chosen = action.get("files");
                if (chosen != null && chosen.isJsonArray()) {
                    for (JsonElement file : chosen.getAsJsonArray()) {
                        files.add(Path.of(environment.resolve(file.getAsString(), step)));
                    }
                }
                locator(page, step).setInputFiles(files.toArray(Path[]::new));
            }
            case "assertText" -> {
                String text = environment.resolve(step.text(), step);
                if (action.has("substring") && action.get("substring").getAsBoolean()) {
                    LocatorAssertions.ContainsTextOptions o = new LocatorAssertions.ContainsTextOptions();
                    if (timeout != null) {
                        o.setTimeout(timeout);
                    }
                    assertThat(locator(page, step)).containsText(text, o);
                } else {
                    LocatorAssertions.HasTextOptions o = new LocatorAssertions.HasTextOptions();
                    if (timeout != null) {
                        o.setTimeout(timeout);
                    }
                    assertThat(locator(page, step)).hasText(text, o);
                }
            }
            case "assertValue" -> {
                String value = environment.resolve(step.value(), step);
                if (value == null || value.isEmpty()) {
                    LocatorAssertions.IsEmptyOptions o = new LocatorAssertions.IsEmptyOptions();
                    if (timeout != null) {
                        o.setTimeout(timeout);
                    }
                    assertThat(locator(page, step)).isEmpty(o);
                } else {
                    LocatorAssertions.HasValueOptions o = new LocatorAssertions.HasValueOptions();
                    if (timeout != null) {
                        o.setTimeout(timeout);
                    }
                    assertThat(locator(page, step)).hasValue(value, o);
                }
            }
            case "assertChecked" -> {
                boolean checked = !action.has("checked") || action.get("checked").getAsBoolean();
                LocatorAssertions.IsCheckedOptions o = new LocatorAssertions.IsCheckedOptions().setChecked(checked);
                if (timeout != null) {
                    o.setTimeout(timeout);
                }
                assertThat(locator(page, step)).isChecked(o);
            }
            case "assertVisible" -> {
                LocatorAssertions.IsVisibleOptions o = new LocatorAssertions.IsVisibleOptions();
                if (timeout != null) {
                    o.setTimeout(timeout);
                }
                assertThat(locator(page, step)).isVisible(o);
            }
            case "assertSnapshot" -> {
                LocatorAssertions.MatchesAriaSnapshotOptions o = new LocatorAssertions.MatchesAriaSnapshotOptions();
                if (timeout != null) {
                    o.setTimeout(timeout);
                }
                assertThat(locator(page, step)).matchesAriaSnapshot(string(action, "ariaSnapshot"), o);
            }
            default -> throw new IllegalStateException("Unbekannte Playwright-Aktion: " + name);
        }
    }

    /** The recorded selector as a strict locator (Playwright resolves its internal: selectors, frames included). */
    private static Locator locator(Page page, TestStep step) {
        if (step.selector() == null) {
            throw new IllegalStateException("Schritt ohne Selektor: " + step.describe());
        }
        return page.locator(step.selector());
    }

    /** Playwright's recorder bit field: 1 Alt, 2 Control, 4 Meta (both ControlOrMeta), 8 Shift. */
    private static List<KeyboardModifier> modifiers(JsonObject action) {
        int bits = action.has("modifiers") ? action.get("modifiers").getAsInt() : 0;
        List<KeyboardModifier> modifiers = new ArrayList<>();
        if ((bits & 1) != 0) {
            modifiers.add(KeyboardModifier.ALT);
        }
        if ((bits & 6) != 0) {
            modifiers.add(KeyboardModifier.CONTROLORMETA);
        }
        if ((bits & 8) != 0) {
            modifiers.add(KeyboardModifier.SHIFT);
        }
        return modifiers;
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

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
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
