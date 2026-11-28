package com.aresstack.spaceharvester.provingground.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.UUID;

/**
 * One step of a test case: Playwright's own {@code ActionInContext} as the recorder reports it
 * ({@code {"pageGuid": ..., "action": {"name": "click", "selector": "internal:role=button[name=\"Save\"i]", ...},
 * "startTime": ...}}). No own action model: the step keeps every field Playwright wrote, also those this class does
 * not know, and edits change single fields of the action. Immutable.
 *
 * <p>Only addition to Playwright's format: {@code stepId}, a stable id next to {@code pageGuid}. Subject matter kept
 * apart from the actions (Given/When/Then, variables, the user a step runs as) refers to a step by it, so inserting,
 * deleting and moving steps never breaks that link. Edits keep the id.</p>
 *
 * <p>Action names (Playwright 1.62): {@code openPage}, {@code closePage}, {@code navigate}, {@code click},
 * {@code hover}, {@code fill}, {@code press}, {@code check}, {@code uncheck}, {@code select}, {@code setInputFiles},
 * {@code assertText}, {@code assertValue}, {@code assertChecked}, {@code assertVisible}, {@code assertSnapshot}.</p>
 */
public final class TestStep {

    private final JsonObject json;

    private TestStep(JsonObject json) {
        this.json = json;
    }

    /** Field of the stable step id. */
    public static final String STEP_ID = "stepId";
    /** Quotes like Playwright's code generators (a JSON string, no HTML escaping). */
    private static final com.google.gson.Gson QUOTE = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    /** Playwright's generated code of the step (see {@link #code()}). */
    public static final String CODE = "code";

    /** A step from Playwright's {@code ActionInContext} (copied); without a step id it gets a new one. */
    public static TestStep of(JsonObject actionInContext) {
        Objects.requireNonNull(actionInContext, "actionInContext");
        if (!actionInContext.has("action") || !actionInContext.get("action").isJsonObject()) {
            throw new IllegalArgumentException("not a Playwright ActionInContext: " + actionInContext);
        }
        JsonObject copy = actionInContext.deepCopy();
        if (!copy.has(STEP_ID) || !copy.get(STEP_ID).isJsonPrimitive()) {
            copy.addProperty(STEP_ID, UUID.randomUUID().toString());
        }
        return new TestStep(copy);
    }

    /** A new action on the page {@code pageGuid}, in Playwright's format (for steps added in the editor). */
    public static TestStep action(String pageGuid, JsonObject action) {
        JsonObject json = new JsonObject();
        json.addProperty(STEP_ID, UUID.randomUUID().toString());
        json.addProperty("pageGuid", pageGuid);
        JsonObject copy = action.deepCopy();
        if (!copy.has("signals")) {
            copy.add("signals", new JsonArray());
        }
        json.add("action", copy);
        return new TestStep(json);
    }

    /** {@code assertText}: the element shows {@code text} (whole text, or a part of it with {@code substring}). */
    public static TestStep assertText(String pageGuid, String selector, String text, boolean substring) {
        JsonObject action = named("assertText", selector);
        action.addProperty("text", text);
        action.addProperty("substring", substring);
        return action(pageGuid, action);
    }

    /** {@code assertVisible}: the element is visible. */
    public static TestStep assertVisible(String pageGuid, String selector) {
        return action(pageGuid, named("assertVisible", selector));
    }

    /** {@code assertValue}: the input has {@code value} (empty = the input is empty). */
    public static TestStep assertValue(String pageGuid, String selector, String value) {
        JsonObject action = named("assertValue", selector);
        action.addProperty("value", value);
        return action(pageGuid, action);
    }

    /** {@code assertChecked}: the checkbox or radio button is (not) checked. */
    public static TestStep assertChecked(String pageGuid, String selector, boolean checked) {
        JsonObject action = named("assertChecked", selector);
        action.addProperty("checked", checked);
        return action(pageGuid, action);
    }

    /** Action name of the screenshot comparison (Playwright's recorder has none; Playwright Test's toHaveScreenshot). */
    public static final String ASSERT_SCREENSHOT = "assertScreenshot";
    /** Field of the screenshot name. */
    public static final String SNAPSHOT = "snapshot";

    /**
     * {@code assertScreenshot}: the page (or, with {@code selector}, the element) looks like the reference image
     * {@code snapshot}. Its code takes the screenshot with Playwright and compares like Playwright Test's
     * {@code toHaveScreenshot} (see {@code player.Screenshots}).
     */
    public static TestStep assertScreenshot(String pageGuid, String selector, String snapshot) {
        JsonObject action = new JsonObject();
        action.addProperty("name", ASSERT_SCREENSHOT);
        if (selector != null && !selector.isBlank()) {
            action.addProperty("selector", selector);
        }
        action.addProperty(SNAPSHOT, snapshot);
        return action(pageGuid, action).withCode(screenshotCode(selector, snapshot));
    }

    /** The code of a screenshot comparison: Playwright's screenshot without animations and caret, then compared. */
    public static String screenshotCode(String selector, String snapshot) {
        boolean element = selector != null && !selector.isBlank();
        String options = element ? "Locator.ScreenshotOptions" : "Page.ScreenshotOptions";
        String subject = element ? "page.locator(" + QUOTE.toJson(selector) + ")" : "page";
        return "screenshots.expect(" + QUOTE.toJson(snapshot) + ", () -> " + subject + ".screenshot(new " + options
                + "()" + (element ? "" : ".setFullPage(true)")
                + "\n    .setAnimations(ScreenshotAnimations.DISABLED).setCaret(ScreenshotCaret.HIDE)));";
    }

    /** {@code text} as a Java string literal, quoted as Playwright's generator quotes. */
    public static String javaString(String text) {
        return QUOTE.toJson(text);
    }

    /** The reference image name of {@code assertScreenshot}, or null. */
    public String snapshot() {
        return string(action(), SNAPSHOT);
    }

    /** {@code navigate} to {@code url}. */
    public static TestStep navigate(String pageGuid, String url) {
        JsonObject action = new JsonObject();
        action.addProperty("name", "navigate");
        action.addProperty("url", url);
        return action(pageGuid, action);
    }

    private static JsonObject named(String name, String selector) {
        JsonObject action = new JsonObject();
        action.addProperty("name", name);
        action.addProperty("selector", selector);
        return action;
    }

    /**
     * The Java code Playwright's own generator wrote for this action while recording (language {@code java}),
     * e.g. {@code page.getByRole(AriaRole.BUTTON, ...).click();}, or null for steps recorded before.
     */
    public String code() {
        return string(json, CODE);
    }

    /** This step with Playwright's code for it (null removes it). */
    public TestStep withCode(String code) {
        JsonObject copy = json.deepCopy();
        if (code == null || code.isBlank()) {
            copy.remove(CODE);
        } else {
            copy.addProperty(CODE, code);
        }
        return new TestStep(copy);
    }

    /** The stable id of the step (survives edits, inserting, deleting and moving). */
    public String id() {
        return string(json, STEP_ID);
    }

    /** This step carrying the id of {@code other} (Playwright merged further input into that step). */
    public TestStep withIdOf(TestStep other) {
        JsonObject copy = json.deepCopy();
        copy.addProperty(STEP_ID, other.id());
        return new TestStep(copy);
    }

    /** The page the step runs on (Playwright's page guid of the recording; only its identity matters). */
    public String pageGuid() {
        return string(json, "pageGuid");
    }

    /** Playwright's action name, e.g. {@code click}. */
    public String name() {
        return string(action(), "name");
    }

    /** Playwright's selector of the element ({@code internal:role=...}, {@code internal:label=...}), or null. */
    public String selector() {
        return string(action(), "selector");
    }

    /** The typed text of {@code fill}, the expected text of {@code assertText}, or null. */
    public String text() {
        return string(action(), "text");
    }

    /** The url of {@code navigate} and {@code openPage}, or null. */
    public String url() {
        return string(action(), "url");
    }

    /** The expected value of {@code assertValue}, or null. */
    public String value() {
        return string(action(), "value");
    }

    /** Whether the step checks something ({@code assert...}) instead of acting. */
    public boolean isAssertion() {
        String name = name();
        return name != null && name.startsWith("assert");
    }

    /** The signal of this name ({@code popup}, {@code download}, {@code dialog}, {@code navigation}), or null. */
    public JsonObject signal(String name) {
        JsonElement signals = action().get("signals");
        if (signals == null || !signals.isJsonArray()) {
            return null;
        }
        for (JsonElement signal : signals.getAsJsonArray()) {
            if (signal.isJsonObject() && name.equals(string(signal.getAsJsonObject(), "name"))) {
                return signal.getAsJsonObject();
            }
        }
        return null;
    }

    /** A copy of the action ({@code name}, {@code selector}, options ...). */
    public JsonObject actionJson() {
        return action().deepCopy();
    }

    /** A copy of the whole step in Playwright's format. */
    public JsonObject toJson() {
        return json.deepCopy();
    }

    /** Another element: Playwright's code of the step no longer fits and is dropped (record the step again). */
    public TestStep withSelector(String selector) {
        TestStep changed = withActionField("selector", selector);
        if (java.util.Objects.equals(selector, selector())) {
            return changed;
        }
        // Our own code (not Playwright's recording) is simply written again for the new element.
        return changed.withCode(ASSERT_SCREENSHOT.equals(name()) ? screenshotCode(selector, snapshot()) : null);
    }

    public TestStep withText(String text) {
        return withActionField("text", text);
    }

    public TestStep withUrl(String url) {
        return withActionField("url", url);
    }

    public TestStep withValue(String value) {
        return withActionField("value", value);
    }

    /**
     * The step with one field of the action set (or removed with {@code null}). Playwright's code follows a changed
     * text, url or value: its quoted literal is replaced; if the old value is not found there, the code is dropped.
     */
    public TestStep withActionField(String field, String value) {
        String old = string(action(), field);
        JsonObject copy = json.deepCopy();
        String code = code();
        if (code != null && !java.util.Objects.equals(old, value) && !"selector".equals(field)) {
            String quotedOld = old == null ? null : QUOTE.toJson(old);
            if (quotedOld != null && value != null && code.contains(quotedOld)) {
                copy.addProperty(CODE, code.replace(quotedOld, QUOTE.toJson(value)));
            } else {
                copy.remove(CODE);
            }
        }
        JsonObject action = copy.getAsJsonObject("action");
        if (value == null) {
            action.remove(field);
        } else {
            action.addProperty(field, value);
        }
        return new TestStep(copy);
    }

    /** The step with a signal appended (the recorder reports popups, downloads and dialogs after the action). */
    public TestStep withSignal(JsonObject signal) {
        JsonObject copy = json.deepCopy();
        JsonObject action = copy.getAsJsonObject("action");
        if (!action.has("signals") || !action.get("signals").isJsonArray()) {
            action.add("signals", new JsonArray());
        }
        action.getAsJsonArray("signals").add(signal.deepCopy());
        return new TestStep(copy);
    }

    /** Readable one-line form for lists and reports. */
    public String describe() {
        StringBuilder line = new StringBuilder(String.valueOf(name()));
        if (selector() != null) {
            line.append(' ').append(selector());
        }
        if (url() != null) {
            line.append(' ').append(url());
        }
        if (text() != null) {
            line.append(" = \"").append(text()).append('"');
        }
        if (value() != null) {
            line.append(" = \"").append(value()).append('"');
        }
        if (snapshot() != null) {
            line.append(" Referenzbild ").append(snapshot());
        }
        return line.toString();
    }

    private JsonObject action() {
        return json.getAsJsonObject("action");
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestStep step && step.json.equals(json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return describe();
    }
}
