package com.aresstack.knowledge4j.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FenceTest {

    private static final Fence FENCE = new Fence(" Ein Sachbuch über Regenwasser im Garten. ", "Nur private Gärten.",
            List.of("Zisternen", " "), List.of("Industrie"), "Deutschland", "");

    @Test
    void thePromptShowsOnlyWhatIsSet() {
        String text = FENCE.describe(true, 1000);
        assertTrue(text.startsWith("Buchauftrag:\nEin Sachbuch über Regenwasser im Garten.\n\nRand des Themas:\n"));
        assertTrue(text.contains("Dazu gehört ausdrücklich:\n- Zisternen\n\n"));
        assertTrue(text.contains("Ausgeschlossen:\n- Industrie\n"));
        assertTrue(text.contains("Räumlicher Rahmen: Deutschland\n"));
        assertFalse(text.contains("Zeitlicher Rahmen"));
    }

    @Test
    void theBasisChangesWithEveryPartOfTheFence() {
        assertEquals(16, FENCE.basis().length());
        assertEquals(FENCE.basis(), new Fence("Ein Sachbuch über Regenwasser im Garten.", "Nur private Gärten.",
                List.of("Zisternen"), List.of("Industrie"), "Deutschland", null).basis());
        assertNotEquals(FENCE.basis(), new Fence("Ein Sachbuch über Regenwasser im Garten.", "Nur private Gärten.",
                List.of("Zisternen"), List.of("Industrie"), "Österreich", "").basis());
        assertFalse(Fence.of("Nur ein Auftrag").hasBounds());
        assertTrue(FENCE.hasBounds());
    }

    @Test
    void aLongMissionIsCutAtABlank() {
        assertEquals("eins zwei …", Fence.cut("eins zwei drei vier", 12));
    }
}
