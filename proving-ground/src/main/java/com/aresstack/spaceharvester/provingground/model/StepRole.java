package com.aresstack.spaceharvester.provingground.model;

/** The part a step plays in the test case (WD4J's Given/When/Then). */
public enum StepRole {
    /** Brings the application into the starting state. */
    GIVEN,
    /** The action under test. */
    WHEN,
    /** Checks the outcome. */
    THEN;

    /** The role of a step without a binding: an assertion checks, everything else acts. */
    public static StepRole of(TestStep step) {
        return step.isAssertion() ? THEN : WHEN;
    }
}
