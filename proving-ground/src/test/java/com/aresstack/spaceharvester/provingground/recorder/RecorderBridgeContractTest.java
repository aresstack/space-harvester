package com.aresstack.spaceharvester.provingground.recorder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the internal Playwright interfaces {@link RecorderBridge} depends on, so that a Playwright update that changes
 * them fails here and not at a user's recording: the programmatic recorder in the Playwright Core bundled by
 * playwright4j, and the package-private members of Playwright Java.
 */
class RecorderBridgeContractTest {

    @Test
    void thePlaywrightCoreOfPlaywright4jHasTheProgrammaticRecorder() throws IOException {
        String core;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("driver/package/lib/coreBundle.js")) {
            assertNotNull(in, "Playwright Core of playwright4j on the class path");
            core = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(core.contains("\"BrowserContext." + RecorderBridge.ENABLE + "\""), "enableRecorder");
        assertTrue(core.contains("\"BrowserContext." + RecorderBridge.DISABLE + "\""), "disableRecorder");
        assertTrue(core.contains("recorderMode: tOptional(tEnum([\"default\", \"api\"]))"), "recorderMode api");
        assertTrue(core.contains("event: tEnum([\"actionAdded\", \"actionUpdated\", \"signalAdded\"])"),
                "recorderEvent kinds");
        assertTrue(core.contains("_dispatchEvent(\"" + RecorderBridge.EVENT + "\""), "recorderEvent dispatched");
    }

    @Test
    void playwrightJavaHasTheMembersTheBridgeUses() {
        assertDoesNotThrow(() -> {
            Class<?> channelOwner = Class.forName("com.microsoft.playwright.impl.ChannelOwner");
            channelOwner.getDeclaredMethod("sendMessage", String.class, com.google.gson.JsonObject.class, Double.class);
            channelOwner.getDeclaredField("connection");
            channelOwner.getDeclaredField("guid");
            Class.forName("com.microsoft.playwright.impl.Connection").getDeclaredField("transport");
            Class<?> transport = Class.forName("com.microsoft.playwright.impl.Transport");
            transport.getMethod("poll", java.time.Duration.class);
        });
    }
}
