package com.aresstack.spaceharvester.provingground.player;

/**
 * The outcome of one step.
 *
 * @param index          position in the test case, from 0
 * @param description    the step in readable form (secrets masked as their variable reference)
 * @param status         passed, failed, or skipped after an earlier failure
 * @param durationMillis how long the step took
 * @param error          why it failed (first line of Playwright's message), or null
 */
public record StepResult(int index, String description, Status status, long durationMillis, String error) {

    public enum Status { PASSED, FAILED, SKIPPED }

    public String render() {
        String mark = switch (status) {
            case PASSED -> "✓";
            case FAILED -> "✗";
            case SKIPPED -> "○";
        };
        return mark + " " + (index + 1) + ". " + description + (error == null ? "" : " – " + error);
    }
}
