package com.aresstack.askai.agent.model.port;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
