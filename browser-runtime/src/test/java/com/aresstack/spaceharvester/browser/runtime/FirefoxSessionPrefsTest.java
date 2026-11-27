package com.aresstack.spaceharvester.browser.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FirefoxSessionPrefsTest {

    @Test
    void addsNoBfcacheAndAutoAcceptRepost() {
        Map<String, Object> prefs = FirefoxSessionPrefs.apply(new LinkedHashMap<String, Object>());
        assertEquals(0, prefs.get("browser.sessionhistory.max_total_viewers"));
        assertEquals(Boolean.TRUE, prefs.get("dom.confirm_repost.testing.always_accept"));
    }

    @Test
    void keepsValuesTheLaunchProfileAlreadySets() {
        Map<String, Object> prefs = new LinkedHashMap<String, Object>();
        prefs.put("dom.confirm_repost.testing.always_accept", Boolean.FALSE);
        FirefoxSessionPrefs.apply(prefs);
        assertEquals(Boolean.FALSE, prefs.get("dom.confirm_repost.testing.always_accept"));
    }
}
