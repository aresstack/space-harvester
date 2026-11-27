package com.aresstack.spaceharvester.provingground.player;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VariablesTest {

    @Test
    void referencesAreResolvedAndAMissingOneNamesOnlyItsName() {
        Map<String, String> values = Map.of("secret.Passwort", "geheim", "user", "angelo");
        assertEquals("angelo:geheim", Variables.resolve("${user}:${secret.Passwort}", values));
        assertEquals("${user}", Variables.resolve("$${user}", values));
        assertEquals("plain", Variables.resolve("plain", values));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Variables.resolve("${secret.Pin}", values));
        assertEquals("Variable fehlt: secret.Pin", missing.getMessage());
        assertFalse(missing.getMessage().contains("geheim"));
    }
}
