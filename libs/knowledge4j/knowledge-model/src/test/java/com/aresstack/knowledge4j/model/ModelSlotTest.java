package com.aresstack.knowledge4j.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ModelSlotTest {

    @Test
    public void acceptsSettingsKeyNames() {
        assertEquals("judge", ModelSlot.of("judge").getName());
        assertEquals(ModelSlot.of("scope.probe-v2"), ModelSlot.of("scope.probe-v2"));
    }

    @Test
    public void rejectsModelNamesAndJunk() {
        for (String bad : new String[]{null, "", "Judge", "gemma4:e2b", "1judge", "a b"}) {
            try {
                ModelSlot.of(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
