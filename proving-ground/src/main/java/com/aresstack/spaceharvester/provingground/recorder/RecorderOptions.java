package com.aresstack.spaceharvester.provingground.recorder;

import java.time.Duration;

/**
 * How the recorder records.
 *
 * @param testIdAttribute     the attribute Playwright's locator generator prefers as test id ({@code data-testid} when
 *                            empty, Playwright's own default)
 * @param maskPasswords       whether text typed into a password field is replaced by a variable reference
 *                            ({@code ${secret.<field>}}) instead of being stored
 * @param passwordCheckTimeout how long the check "is this field a password field?" may wait for the element; zero =
 *                            Playwright's default timeout of the context
 */
public record RecorderOptions(String testIdAttribute, boolean maskPasswords, Duration passwordCheckTimeout) {

    public RecorderOptions {
        testIdAttribute = testIdAttribute == null ? "" : testIdAttribute.trim();
        passwordCheckTimeout = passwordCheckTimeout == null ? Duration.ZERO : passwordCheckTimeout;
    }

    /** Playwright's test id attribute, passwords masked, Playwright's default timeout. */
    public static RecorderOptions defaults() {
        return new RecorderOptions("", true, Duration.ZERO);
    }
}
