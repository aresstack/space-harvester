package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ControlLocatorTest {

    private final List<RawControl> controls = Arrays.asList(
            new RawControl("r1", "input", "Betrag (EUR)", "", "", "", "Neue eRechnung", true,
                    "label=\"Betrag (EUR)\"", "Mitte"),
            new RawControl("r2", "button", "Speichern", "", "", "", "Neue eRechnung", false,
                    "role=button name=\"Speichern\"", "unten"),
            new RawControl("r3", "button", "Speichern und schließen", "", "", "", "", false,
                    "role=button name=\"Speichern und schließen\"", "unten"),
            new RawControl("r4", "search", "Suche", "", "", "", "", false, "role=searchbox name=\"Suche\"", "oben"));

    @Test
    public void findsByEqualLocatorLabelAndRole() throws BrowserException {
        assertEquals("r1", ControlLocator.find(controls, "label=\"Betrag (EUR)\"").ref());
        assertEquals("r2", ControlLocator.find(controls, "role=button name=\"Speichern\"").ref());
        assertEquals("r2", ControlLocator.find(controls, "Speichern").ref());
        assertEquals("r4", ControlLocator.find(controls, "role=textbox name=\"Suche\"").ref());
        assertEquals("r1", ControlLocator.find(controls, "Betrag").ref());
    }

    @Test
    public void rejectsUnknownAndAmbiguous() {
        for (String locator : new String[]{"Löschen", "role=button name=\"Speich\"", " "}) {
            try {
                ControlLocator.find(controls, locator);
                fail(locator);
            } catch (BrowserException expected) {
                assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
            }
        }
    }
}
