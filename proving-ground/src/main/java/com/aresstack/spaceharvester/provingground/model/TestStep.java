package com.aresstack.spaceharvester.provingground.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One step of a test case: Playwright's own {@code ActionInContext} as the recorder reports it
 * ({@code {"pageGuid": ..., "action": {"name": "click", "selector": "internal:role=button[name=\"Save\"i]", ...},
 * "startTime": ...}}). No own action model: the step keeps every field Playwright wrote, also those this class does
 * not know, and edits change single fields of the action. Immutable.
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

    /** A step from Playwright's {@code ActionInContext} (copied). */
    public static TestStep of(JsonObject actionInContext) {
        Objects.requireNonNull(actionInContext, "actionInContext");
        if (!actionInContext.has("action") || !actionInContext.get("action").isJsonObject()) {
            throw new IllegalArgumentException("not a Playwright ActionInContext: " + actionInContext);
        }
        return new TestStep(actionInContext.deepCopy());
    }

    /** A new action on the page {@code pageGuid}, in Playwright's format (for steps added in the editor). */
    public static TestStep action(String pageGuid, JsonObject action) {
        JsonObject json = new JsonObject();
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

    public TestStep withSelector(String selector) {
        return withActionField("selector", selector);
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

    /** The step with one field of the action set (or removed with {@code null}). */
    public TestStep withActionField(String field, String value) {
        JsonObject copy = json.deepCopy();
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
