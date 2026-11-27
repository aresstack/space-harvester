package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** The per-turn context carries the host-supplied current date, so the model never invents a year. */
public class PhaseContextAssemblerTest {

    @Test
    public void theHostSuppliedCurrentDateIsPartOfTheContext() {
        PhaseContextAssembler assembler = new PhaseContextAssembler(new PhaseContextAssembler.CurrentDate() {
            public String today() {
                return "2026-08-02";
            }
        });
        PhaseAssistantProfile profile = PhaseAssistantProfileRegistry.defaults().forPhase("scoping");
        TeamAgentStateView state = new TeamAgentStateView("scoping", "new", Arrays.<String>asList());

        List<ChatMessage> messages = assembler.assemble(profile, state, null);

        boolean found = false;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM
                    && message.getContent().contains("Current date: 2026-08-02")) {
                found = true;
            }
        }
        assertTrue("the current date is supplied as runtime context", found);
    }

    /**
     * The Denglisch contract: the working language is read PER TURN from the live session value; a switch
     * between turns changes only the new turn's instruction while the running history stays untouched.
     */
    @Test
    public void theWorkingLanguageIsReadLivePerTurnAndHistoryStaysUntouched() {
        final String[] language = {"German"};
        PhaseContextAssembler assembler = new PhaseContextAssembler(
                new PhaseContextAssembler.CurrentDate() {
                    public String today() {
                        return "2026-08-03";
                    }
                },
                new PhaseContextAssembler.CurrentLanguage() {
                    public String displayName() {
                        return language[0];
                    }
                });
        PhaseAssistantProfile profile = PhaseAssistantProfileRegistry.defaults().forPhase("scoping");
        TeamAgentStateView state = new TeamAgentStateView("scoping", "running", Arrays.<String>asList());
        List<ChatMessage> history = Arrays.asList(
                ChatMessage.user("Wearables interessieren mich.").tagged("scoping", ChatMessage.Kind.USER),
                ChatMessage.assistant("Dann sollten wir zuerst den Fokus klären.")
                        .tagged("scoping", ChatMessage.Kind.FINAL));

        List<ChatMessage> turnOne = assembler.assemble(profile, state, history);
        assertTrue("turn 1 works in German",
                containsSystem(turnOne, "Current working language: German."));

        language[0] = "English"; // set_language between the turns — no new assembler, no model call
        List<ChatMessage> turnTwo = assembler.assemble(profile, state, history);
        assertTrue("turn 2 works in English",
                containsSystem(turnTwo, "Current working language: English."));
        assertTrue("the instruction protects historical content",
                containsSystem(turnTwo, "Do not translate or rewrite historical content"));
        // The REPLY follows the user (answering German input in English reads as a defect), while brief
        // and search queries follow the configured setting.
        assertTrue("the reply language follows the user's message",
                containsSystem(turnTwo, "ANSWER THE USER IN THE LANGUAGE THEY WRITE IN"));
        assertTrue("the configured language still governs the generated artifacts",
                containsSystem(turnTwo, "Use English for all other newly generated content"));
        assertTrue("the German history is still part of the context, untranslated",
                turnTwo.get(turnTwo.size() - 2).getContent().contains("Wearables interessieren mich."));
        assertTrue(turnTwo.get(turnTwo.size() - 1).getContent()
                .contains("Dann sollten wir zuerst den Fokus klären."));
    }

    private static boolean containsSystem(List<ChatMessage> messages, String text) {
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM && message.getContent().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
