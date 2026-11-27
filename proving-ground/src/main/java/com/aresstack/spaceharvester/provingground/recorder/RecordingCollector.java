package com.aresstack.spaceharvester.provingground.recorder;

import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.google.gson.JsonObject;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the recorder's events to a test case, as Playwright's own recorder app does: {@code actionAdded} appends a
 * step, {@code actionUpdated} replaces the last one (Playwright merged it: further typing into the same field, a
 * double click), {@code signalAdded} attaches a popup, download or dialog to the last step of that page. The merging
 * itself is Playwright's ({@code shouldMergeAction}: same action AND same selector), so typing into two fields in a
 * row keeps both.
 *
 * <p>Text typed into a password field never reaches the test case: it becomes {@code ${secret.<field>}}, a variable
 * the player resolves at run time.</p>
 */
final class RecordingCollector {

    static final String SECRET_PREFIX = "secret.";
    private static final Pattern NAMED = Pattern.compile("(?:name|label|placeholder)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern LABEL = Pattern.compile("internal:(?:label|attr=\\[placeholder)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final TestCase target;
    private final boolean maskPasswords;
    private boolean lastIsSecret;
    /** Whether Playwright's current (last added) action was dropped because it came during a pause. */
    private boolean lastDropped;

    RecordingCollector(TestCase target, boolean maskPasswords) {
        this.target = target;
        this.maskPasswords = maskPasswords;
    }

    /**
     * Applies one {@code recorderEvent}; {@code isPasswordField} is asked only for a newly added {@code fill}.
     * Answers the step that was added or changed, or null.
     */
    TestStep apply(JsonObject event, Predicate<TestStep> isPasswordField) {
        return apply(event, true, isPasswordField);
    }

    /**
     * As {@link #apply(JsonObject, Predicate)}; {@code recorded = false} for an event of a pause: it is not taken
     * over, and a later merge into that action (typing on after the pause) becomes a new step instead of replacing
     * the last recorded one.
     */
    TestStep apply(JsonObject event, boolean recorded, Predicate<TestStep> isPasswordField) {
        String kind = event.has("event") ? event.get("event").getAsString() : "";
        JsonObject data = event.has("data") && event.get("data").isJsonObject() ? event.getAsJsonObject("data") : null;
        if (data == null) {
            return null;
        }
        if (!recorded) {
            if ("actionAdded".equals(kind)) {
                lastDropped = true;
            }
            return null;
        }
        if ("actionUpdated".equals(kind) && lastDropped) {
            kind = "actionAdded"; // the action began during the pause: what follows it is a step of its own
        }
        switch (kind) {
            case "actionAdded" -> {
                lastDropped = false;
                TestStep step = TestStep.of(data);
                lastIsSecret = maskPasswords && "fill".equals(step.name()) && isPasswordField.test(step);
                step = masked(step);
                target.add(step);
                return step;
            }
            case "actionUpdated" -> {
                TestStep step = masked(TestStep.of(data));
                if (target.size() == 0) {
                    target.add(step);
                } else {
                    step = step.withIdOf(target.last());
                    target.replace(target.size() - 1, step);
                }
                return step;
            }
            case "signalAdded" -> {
                String page = data.has("pageGuid") ? data.get("pageGuid").getAsString() : null;
                JsonObject signal = data.has("signal") && data.get("signal").isJsonObject()
                        ? data.getAsJsonObject("signal") : null;
                if (signal == null) {
                    return null;
                }
                for (int i = target.size() - 1; i >= 0; i--) {
                    if (page == null || page.equals(target.step(i).pageGuid())) {
                        TestStep step = target.step(i).withSignal(signal);
                        target.replace(i, step);
                        return step;
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private TestStep masked(TestStep step) {
        if (!lastIsSecret || !"fill".equals(step.name())) {
            return step;
        }
        return step.withText("${" + SECRET_PREFIX + fieldName(step.selector()) + "}");
    }

    /** A variable name for the field of {@code selector}: its accessible name or label, else "password". */
    static String fieldName(String selector) {
        if (selector != null) {
            Matcher label = LABEL.matcher(selector);
            Matcher named = NAMED.matcher(selector);
            String name = label.find() ? label.group(1) : named.find() ? named.group(1) : null;
            if (name != null) {
                String cleaned = name.replace("\\\"", "\"").replaceAll("[^\\p{L}\\p{N}_-]+", "_")
                        .replaceAll("^_+|_+$", "");
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }
        return "password";
    }
}
