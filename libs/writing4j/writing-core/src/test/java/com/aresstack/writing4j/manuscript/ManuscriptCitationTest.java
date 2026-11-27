package com.aresstack.writing4j.manuscript;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManuscriptCitationTest {

    private static ManuscriptCitation citing(String citedText) {
        return new ManuscriptCitation("1", "source-67", "sha-67", citedText, "p-67", null);
    }

    /** Live run 4: the passage joins its sentences with one space; the source had a no-break space there. */
    @Test
    public void theCitedTextIsFoundAcrossNoBreakSpacesAndLineBreaks() {
        ManuscriptCitation citation = citing("und warnen vor möglichen Gefahren. Bewegungs- und "
                + "Annäherungssensoren:");
        assertTrue(citation.isFoundIn("Helme messen die Körpertemperatur und warnen vor möglichen Gefahren. \u00A0"
                + "Bewegungs- und Annäherungssensoren: Diese Systeme verhindern Kollisionen."));
        assertTrue(citation.isFoundIn("und warnen vor\u202Fmöglichen Gefahren.\r\n\tBewegungs- und "
                + "Annäherungssensoren:"));
    }

    @Test
    public void anotherTextIsNotFound() {
        ManuscriptCitation citation = citing("und warnen vor möglichen Gefahren.");
        assertFalse(citation.isFoundIn("und warnen vor Gefahren."));
        assertFalse(citation.isFoundIn(null));
    }
}
