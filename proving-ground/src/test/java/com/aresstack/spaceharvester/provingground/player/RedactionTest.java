package com.aresstack.spaceharvester.provingground.player;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedactionTest {

    @Test
    void secretValuesLeaveFailureMessagesAsTheirReference() {
        String message = "Locator expected to have value · Expected: geheim · Received: falsch";
        assertEquals("Locator expected to have value · Expected: ${secret.Passwort} · Received: falsch",
                TestPlayer.redacted(message, Map.of("secret.Passwort", "geheim", "user", "falsch")));
    }
}
