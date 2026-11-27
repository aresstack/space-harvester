package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The hard gate that keeps codec/transport meta-talk out of the visible chat. */
public class VisibleAssistantMessageValidatorTest {

    @Test
    public void warmScopingRepliesAreClean() {
        assertTrue(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Got it — you want to explore wearables. Which aspects matter most to you?"));
        assertTrue("everyday words are not banned", VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "You clearly have a good command of the topic; formatting the report comes later."));
    }

    @Test
    public void codecMetaTalkIsRejected() {
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "I apologize if my previous response was not formatted correctly."));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Please respond with one valid JSON object matching the schema."));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Ready to proceed with the structured research."));
    }

    @Test
    public void blankIsNeverClean() {
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(null));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage("   "));
    }

    /** The Meerschweinchen regression: the action spelled as visible text while conceptAction said none. */
    @Test
    public void anActionSpelledAsVisibleTextIsALeak() {
        assertEquals("add_cards", VisibleAssistantMessageValidator.actionLeakIn(
                "add_cards:\n    - Haltung und Sozialverhalten\n    - Ernährung und Futter\n"
                        + "    offer: Welche spezifischen Aspekte interessieren dich?"));
        assertEquals("offer", VisibleAssistantMessageValidator.actionLeakIn(
                "Gern!\n- offer: Meerschweinchen Grundlagen"));
        assertEquals("none", VisibleAssistantMessageValidator.actionLeakIn("none"));
        assertEquals("none", VisibleAssistantMessageValidator.actionLeakIn(" \"none\". "));
        assertEquals("type", VisibleAssistantMessageValidator.actionLeakIn(
                "Ich lege an: {\"type\":\"add_cards\",\"names\":[\"Haltung\"]}"));
        assertEquals("add_cards", VisibleAssistantMessageValidator.actionLeakIn(
                "Ich führe jetzt add_cards mit drei Bereichen aus."));
    }

    @Test
    public void ordinaryScopingProseIsNoLeak() {
        assertNull(VisibleAssistantMessageValidator.actionLeakIn(
                "Gern! Welche Aspekte interessieren dich: Haltung, Ernährung oder Gesundheit?"));
        assertNull(VisibleAssistantMessageValidator.actionLeakIn(
                "Ich habe die Bereiche Haltung und Ernährung angelegt. Ich biete dir außerdem Suchen an."));
        assertNull("a word inside a sentence is not a keyword",
                VisibleAssistantMessageValidator.actionLeakIn("Ich kann dir gern ein Angebot machen; "
                        + "du kannst das später noch einmal read- oder umbenennen."));
        assertNull(VisibleAssistantMessageValidator.actionLeakIn("Keine weiteren Fragen? Dann sag Bescheid."));
        assertNull(VisibleAssistantMessageValidator.actionLeakIn(null));
    }
}
