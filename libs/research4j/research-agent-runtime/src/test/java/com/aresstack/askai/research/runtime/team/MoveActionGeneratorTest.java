package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The dedicated move generation strategy (move-gate ruling): the request ACTUALLY handed to
 * the model provider is inspected — a tiny schema with exactly two REQUIRED fields and no
 * competing action kinds — and its reply flows through the EXISTING action parser/validator
 * into the ordinary move funnel. No guessing: the unclear marker yields no action at all.
 */
public class MoveActionGeneratorTest {

    /** A fake provider capturing the exact structured request. */
    private static final class CapturingChat implements MainModelChat {
        List<ChatMessage> messages;
        String schemaJson;
        String reply = "{\"source\":[\"Scheduling\"],\"parent\":[\"FreeRTOS\"]}";

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            throw new AssertionError("the move generator must use the STRUCTURED call");
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                int maxOutputTokens, String schemaJson) {
            this.messages = messages;
            this.schemaJson = schemaJson;
            return MainModelChatResult.ok(reply);
        }

        public String modelName() {
            return "fake";
        }
    }

    @Test
    public void theDeliveredRequestCarriesTheTwoFieldRequiredSchemaAndTheUsersOrder() {
        CapturingChat chat = new CapturingChat();
        ConceptAction action = MoveActionGenerator.generate(chat,
                "Verschiebe Scheduling unter FreeRTOS.",
                "revision=4\n{\"concept\":[{\"Betriebssysteme\":[]}]}");

        Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(chat.schemaJson);
        Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) parsed).get("properties");
        assertEquals("EXACTLY two fields — no competing action kinds", 2, properties.size());
        assertTrue(properties.containsKey("source"));
        assertTrue(properties.containsKey("parent"));
        assertEquals("both fields REQUIRED", Arrays.asList("source", "parent"),
                ((Map<?, ?>) parsed).get("required"));
        assertTrue("the user's exact German order travels verbatim",
                chat.messages.get(1).getContent()
                        .contains("Verschiebe Scheduling unter FreeRTOS."));
        assertTrue("the current tree grounds the name copying",
                chat.messages.get(1).getContent().contains("Betriebssysteme"));

        // The reply became the EXISTING internal operation — same parser, same validator.
        assertEquals(ConceptAction.Type.MOVE, action.getType());
        assertEquals(Collections.singletonList("Scheduling"), action.getPath());
        assertEquals(Collections.singletonList("FreeRTOS"), action.getParent());
    }

    @Test
    public void unclearMarkerBrokenRepliesAndTransportFailuresYieldNoActionEver() {
        assertNull("the honest no-guess escape",
                MoveActionGenerator.parseReply("{\"source\":[\"?\"],\"parent\":[]}"));
        assertNull(MoveActionGenerator.parseReply("not json at all"));
        assertNull("an empty source fails the EXISTING validator",
                MoveActionGenerator.parseReply("{\"source\":[],\"parent\":[\"X\"]}"));

        CapturingChat chat = new CapturingChat();
        chat.reply = "{\"source\":[\"?\"],\"parent\":[]}";
        assertNull(MoveActionGenerator.generate(chat, "verschiebe irgendwas", ""));
    }

    /** Gate ruling #3: visible answers derive from the receipt — no internal markers ever. */
    @Test
    public void receiptAnswersAreLocalizedProductSentencesWithoutInternalMarkers() {
        String receipt = "APPLIED revision=6\nMOVED: Scheduling\n"
                + "FROM: [Betriebssysteme, Linux, Scheduling]\n"
                + "TO: [Betriebssysteme, FreeRTOS, Scheduling]\nID: 8069a696";
        assertEquals("„Scheduling“ wurde von „Linux“ nach „FreeRTOS“ verschoben.",
                TeamAgentPlaybook.moveAppliedAnswer(true, receipt));
        assertEquals("\"Scheduling\" was moved from \"Linux\" to \"FreeRTOS\".",
                TeamAgentPlaybook.moveAppliedAnswer(false, receipt));
        String rootReceipt = "APPLIED revision=7\nMOVED: Scheduling\n"
                + "FROM: [Linux, Scheduling]\nTO: [Scheduling]\nID: x";
        assertTrue(TeamAgentPlaybook.moveAppliedAnswer(true, rootReceipt)
                .contains("nach der obersten Ebene"));

        String branch = TeamAgentPlaybook.moveRejectedAnswer(true,
                "Error: BRANCH_GRAFT_FAILED SOURCE_NOT_LEAF \"Betriebssysteme\" — only a "
                        + "LEAF moves with this tool");
        assertTrue(branch, branch.contains("„Betriebssysteme“ ist ein Zweig"));
        for (String marker : new String[] {"Error", "BRANCH_GRAFT_FAILED", "SOURCE_NOT_LEAF"}) {
            assertTrue("no internal marker leaks: " + marker, !branch.contains(marker));
        }
        assertTrue(TeamAgentPlaybook.moveRejectedAnswer(true,
                "TARGET_PARENT_NOT_FOUND [Gibtsnicht]").contains("existiert nicht"));
        assertTrue(TeamAgentPlaybook.moveRejectedAnswer(true,
                "AMBIGUOUS_PARENT \"Linux\" — candidates: [A, Linux], [B, Linux]")
                .contains("mehrdeutig"));
        assertTrue(TeamAgentPlaybook.moveRejectedAnswer(true,
                "TARGET_NAME_COLLISION \"Scheduling\"").contains("bereits eine Karte"));
        assertTrue("unknown reasons stay a clean product sentence",
                TeamAgentPlaybook.moveRejectedAnswer(true, "whatever internal text")
                        .startsWith("Am Konzept wurde nichts verändert."));
    }

    @Test
    public void aRootTargetParsesAsTheEmptyParent() {
        ConceptAction action = MoveActionGenerator.parseReply(
                "{\"source\":[\"Scheduling\"],\"parent\":[]}");
        assertEquals(ConceptAction.Type.MOVE, action.getType());
        assertTrue(action.getParent().isEmpty());
    }
}
