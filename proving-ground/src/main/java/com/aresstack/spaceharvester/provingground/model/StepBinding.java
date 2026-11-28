package com.aresstack.spaceharvester.provingground.model;

import java.time.Duration;

/**
 * The subject matter of one step, kept apart from Playwright's action and linked to it by the step id (so inserting,
 * deleting and moving steps never breaks the link). Every field is optional.
 *
 * @param role        Given, When or Then; null = derived from the action ({@link StepRole#of})
 * @param user        the user the step runs as (a user of the project); null = the test case's user
 * @param description what the step means in the language of the application, shown instead of the action; may be empty
 * @param minDuration how long the step takes at least (WD4J's minimum duration); null or zero = as fast as possible
 */
public record StepBinding(StepRole role, String user, String description, Duration minDuration) {

    public static final StepBinding NONE = new StepBinding(null, null, "", Duration.ZERO);

    public StepBinding {
        user = user == null || user.isBlank() ? null : user.trim();
        description = description == null ? "" : description;
        minDuration = minDuration == null || minDuration.isNegative() ? Duration.ZERO : minDuration;
    }

    public StepBinding withRole(StepRole value) {
        return new StepBinding(value, user, description, minDuration);
    }

    public StepBinding withUser(String value) {
        return new StepBinding(role, value, description, minDuration);
    }

    public StepBinding withDescription(String value) {
        return new StepBinding(role, user, value, minDuration);
    }

    public StepBinding withMinDuration(Duration value) {
        return new StepBinding(role, user, description, value);
    }

    public boolean isEmpty() {
        return equals(NONE);
    }
}
