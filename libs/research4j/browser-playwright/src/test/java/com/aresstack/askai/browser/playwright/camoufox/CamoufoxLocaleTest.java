package com.aresstack.askai.browser.playwright.camoufox;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** AskAI starts the built-in profile in the system language, as Chrome did (search engines answer in it). */
class CamoufoxLocaleTest {

    @Test
    void theBuiltInProfileSpeaksTheSystemLanguage() throws Exception {
        JsonObject profile = CamoufoxLaunch.localized(CamoufoxLaunch.defaultProfile(), Locale.GERMANY);

        assertEquals("de-DE", profile.getAsJsonObject("firefoxUserPrefs").get("intl.locale.requested").getAsString());
        assertEquals("de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3",
                profile.getAsJsonObject("firefoxUserPrefs").get("intl.accept_languages").getAsString());
        assertEquals("de", profile.getAsJsonObject("config").get("locale:language").getAsString());
        assertEquals("DE", profile.getAsJsonObject("config").get("locale:region").getAsString());
        assertEquals("de-DE,de,en-US,en", profile.getAsJsonObject("config").get("locale:all").getAsString());
        assertEquals("de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3",
                profile.getAsJsonObject("config").get("headers.Accept-Language").getAsString());
        // the rest of the fingerprint stays as Argos ships it
        assertEquals("Win32", profile.getAsJsonObject("config").get("navigator.platform").getAsString());
    }

    @Test
    void anUndeterminedLocaleKeepsTheProfile() throws Exception {
        JsonObject profile = CamoufoxLaunch.defaultProfile();
        assertSame(profile, CamoufoxLaunch.localized(profile, Locale.ROOT));
        assertEquals("en-US", profile.getAsJsonObject("firefoxUserPrefs").get("intl.locale.requested").getAsString());
    }
}
