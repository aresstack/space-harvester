package com.aresstack.spaceharvester.provingground.player;

import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.microsoft.playwright.BrowserContext;

import java.time.Duration;

/**
 * What a step needs around it when it is played: the browser context it runs in (one per user, so a test can switch
 * between users), the values of its {@code {{...}}} expressions (resolved only now, never written back), and how
 * secrets are kept out of messages. The test runner supplies it; a single test case in one context uses
 * {@link TestPlayer#play(com.aresstack.spaceharvester.provingground.model.TestCase, BrowserContext, PlaybackOptions)}.
 */
public interface StepEnvironment {

    /** The browser context of the user the step runs as. */
    BrowserContext context(TestStep step);

    /** {@code value} with its expressions resolved for {@code step}; fails with the missing name, never a value. */
    String resolve(String value, TestStep step);

    /** {@code text} with every secret that was resolved replaced by its reference. */
    String redact(String text);

    /** The step as it is listed in the result (its description from the test model, else Playwright's action). */
    default String describe(TestStep step) {
        return step.describe();
    }

    /** How long the step takes at least (WD4J's minimum duration, for slow applications); zero = as fast as possible. */
    default Duration minDuration(TestStep step) {
        return Duration.ZERO;
    }
}
