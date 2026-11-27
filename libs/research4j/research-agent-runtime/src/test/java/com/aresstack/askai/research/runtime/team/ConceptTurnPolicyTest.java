package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The machine-side intent separation (safety-slice gate 2), pinned with the LIVE sentences that
 * broke the gate: the compound rewrite that became partial adds, and the delete order that
 * silently became a scope exclusion.
 */
public class ConceptTurnPolicyTest {

    @Test
    public void theGatesLiveSentencesClassifyExactlyAsRuled() {
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY, ConceptTurnPolicy.modeFor(
                "Lösche den kompletten Buch-Hauptzweig „FreeRTOS Grundlagen“."));
        assertEquals(ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY, ConceptTurnPolicy.modeFor(
                "Überarbeite den gesamten Konzeptzweig und ersetze seine bisherigen "
                        + "Unterkarten durch „Tasks“, „Synchronisation“ und „Debugging“."));
        assertEquals("the exclusion wording stays a FULL turn — the facade must keep working",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("PlatformIO möchte ich nicht behandeln."));
    }

    @Test
    public void deleteWinsOverRestructureAndPlainBuildingStaysFull() {
        assertEquals("delete is the stricter mode when both match",
                ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Ersetze nichts, lösche den Zweig einfach."));
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Entferne die Karte Toolchain aus dem Konzept."));
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Please remove the ESP-IDF card."));
        assertEquals("a bare 'entferne X' stays conversational — the exclusion drill owns it",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Entferne ESP-IDF."));
        assertEquals("a bare 'lösche X' too: with the answer now host-replaced, DELETE demands "
                + "verb AND structure noun", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Lösche PlatformIO."));
        // move_leaf slice: a move order is a REAL capability now — full permissions, but the
        // receipt-truth guard arms ("verschoben" only with a MOVED receipt of the turn).
        assertEquals(ConceptTurnPolicy.Mode.MOVE_TRUTH,
                ConceptTurnPolicy.modeFor("Verschiebe Toolchain unter Grundlagen."));
        assertEquals(ConceptTurnPolicy.Mode.MOVE_TRUTH,
                ConceptTurnPolicy.modeFor("Please move Scheduling under FreeRTOS."));
        assertEquals("'remove' must never arm the move guard", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Entferne ESP-IDF."));
        assertEquals("'movement' is not 'move'", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Describe the movement of data between tasks."));
        // The gate's negation finding: a DENIED move mention must never arm the guard — it
        // once flipped a successful add turn into a false 'nothing changed' host answer.
        assertEquals(ConceptTurnPolicy.Mode.FULL, ConceptTurnPolicy.modeFor(
                "Die Karte „Scheduling“ existiert noch nicht. Lege sie neu unter dem "
                        + "vorhandenen Parent „Linux“ an. Das ist ein Hinzufügen, kein "
                        + "Verschieben."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Bitte nicht verschieben, nur lesen."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Ergänze Tasks, ohne etwas zu verschieben."));
        assertEquals("one UNNEGATED mention still arms the guard",
                ConceptTurnPolicy.Mode.MOVE_TRUTH, ConceptTurnPolicy.modeFor(
                        "Nicht löschen! Verschiebe Scheduling unter FreeRTOS."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Füge unter FreeRTOS bitte Tasks hinzu."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Schreibe ein Buch über FreeRTOS auf dem ESP32."));
        assertEquals(ConceptTurnPolicy.Mode.FULL, ConceptTurnPolicy.modeFor(null));
    }

    /** The connector gate's live sentence: an explicit exclusion order arms the truth guard. */
    @Test
    public void anExplicitExclusionOrderArmsTheExcludeTruthGuard() {
        assertEquals(ConceptTurnPolicy.Mode.EXCLUDE_TRUTH, ConceptTurnPolicy.modeFor(
                "Schließe bitte „FreeRTOS Architektur“ ausdrücklich aus der Recherche aus. "
                        + "Ändere sonst nichts."));
        assertEquals(ConceptTurnPolicy.Mode.EXCLUDE_TRUTH,
                ConceptTurnPolicy.modeFor("Bitte ESP-IDF ausschließen."));
        assertEquals(ConceptTurnPolicy.Mode.EXCLUDE_TRUTH,
                ConceptTurnPolicy.modeFor("Exclude the architecture card from research."));
    }

    /** A false positive would replace a legitimate answer — every non-order stays FULL. */
    @Test
    public void exclusionMentionsThatAreNoOrderNeverArm() {
        assertEquals("'ausschließlich' is an adverb, not an order", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Betrachte ausschließlich die Doku aus dem Wiki."));
        assertEquals("questions never order", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Was schließen wir eigentlich aus?"));
        assertEquals("a negated mention never arms", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Bitte nichts ausschließen, nur sammeln."));
        assertEquals("'schließen' without 'aus' is closing, not excluding",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Schließe die Sitzung."));
        assertEquals("a past-tense report is no order", ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Wir haben ESP-IDF bereits ausgeschlossen."));
        assertEquals("the drill's own wording stays FULL — the facade keeps working",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("PlatformIO möchte ich nicht behandeln."));
    }
}
