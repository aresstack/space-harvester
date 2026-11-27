package com.aresstack.spaceharvester.provingground.recorder;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Records the user's work in a browser context into a test case with the ORIGINAL Playwright recorder: Playwright
 * picks the locators (test id, role, label, text, CSS as Playwright ranks them), merges typing into one {@code fill}
 * per field, notices popups, downloads and dialogs, and shows its own toolbar in the page (record, pick a locator,
 * assert text/value/visibility/snapshot). Nothing of that is rebuilt here; this class only collects the steps
 * ({@link RecordingCollector}) and keeps passwords out of them.
 *
 * <p>Threading as in Playwright Java: every call from the one thread that uses the context. Playwright delivers the
 * recorder's events only while that thread is inside a Playwright call, so a recording UI lets the thread wait in
 * {@link #pump(BooleanSupplier)}; tests drive the page and call {@link #processPending()}. {@link #pause()} and
 * {@link #resume()} may come from any thread.</p>
 */
public final class Recorder implements AutoCloseable {

    private final BrowserContext context;
    private final TestCase target;
    private final RecorderOptions options;
    private final RecordingCollector collector;
    private final Queue<JsonObject> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final RecorderBridge bridge;
    private volatile Consumer<TestStep> onStep = step -> { };

    private Recorder(BrowserContext context, TestCase target, RecorderOptions options) {
        this.context = context;
        this.target = target;
        this.options = options;
        this.collector = new RecordingCollector(target, options.maskPasswords());
        this.bridge = RecorderBridge.attach(context, event -> {
            if (!paused.get()) {
                pending.add(event);
            }
        });
    }

    /**
     * Starts recording into {@code target} (steps are appended). Start it before the first page is opened, so that
     * Playwright records the page opening as well.
     */
    public static Recorder start(BrowserContext context, TestCase target, RecorderOptions options) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(target, "target");
        RecorderOptions effective = options == null ? RecorderOptions.defaults() : options;
        Recorder recorder = new Recorder(context, target, effective);
        JsonObject params = new JsonObject();
        params.addProperty("mode", "recording");
        params.addProperty("recorderMode", "api");
        params.addProperty("language", "jsonl");
        if (!effective.testIdAttribute().isEmpty()) {
            params.addProperty("testIdAttributeName", effective.testIdAttribute());
        }
        try {
            recorder.bridge.enable(params);
        } catch (RuntimeException failed) {
            recorder.bridge.close();
            throw failed;
        }
        return recorder;
    }

    /** Called for every step added or changed (on the recording thread). */
    public void onStep(Consumer<TestStep> listener) {
        onStep = listener == null ? step -> { } : listener;
    }

    /** The test case being recorded into. */
    public TestCase testCase() {
        return target;
    }

    /** What the user does from now on is not recorded, until {@link #resume()}. */
    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Waits on the calling (Playwright) thread and collects steps until {@code until} answers true or the recorder is
     * stopped; Playwright keeps delivering the browser's messages meanwhile.
     */
    public void pump(BooleanSupplier until) {
        while (!stopped.get() && !until.getAsBoolean()) {
            context.waitForCondition(() -> !pending.isEmpty() || stopped.get() || until.getAsBoolean(),
                    new BrowserContext.WaitForConditionOptions().setTimeout(0));
            processPending();
        }
        processPending();
    }

    /** Applies the events Playwright delivered so far to the test case. */
    public void processPending() {
        JsonObject event;
        while ((event = pending.poll()) != null) {
            TestStep step = collector.apply(event, this::isPasswordField);
            if (step != null) {
                onStep.accept(step);
            }
        }
    }

    /** Ends the recording: collects what is left and turns Playwright's recorder off. The context stays open. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            processPending();
            if (!context.pages().isEmpty()) {
                bridge.disable();
            }
        } catch (PlaywrightException contextGone) {
            // the browser was closed by the user; the steps so far are kept
        } finally {
            bridge.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Whether the element of a recorded {@code fill} is a password input (read once, right after the typing). */
    private boolean isPasswordField(TestStep step) {
        if (step.selector() == null || step.pageGuid() == null) {
            return false;
        }
        try {
            Page page = RecorderBridge.page(context, step.pageGuid());
            if (page == null) {
                return false;
            }
            Locator.EvaluateOptions evaluate = new Locator.EvaluateOptions();
            if (options.passwordCheckTimeout().toMillis() > 0) {
                evaluate.setTimeout(options.passwordCheckTimeout().toMillis());
            }
            Object answer = page.locator(step.selector()).evaluate(
                    "element => element instanceof HTMLInputElement && element.type === 'password'", null, evaluate);
            return Boolean.TRUE.equals(answer);
        } catch (PlaywrightException unknown) {
            // Unknown is treated as secret: better a variable too many than a password in the file.
            return true;
        }
    }
}
