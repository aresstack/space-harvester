package com.aresstack.research4j.judge;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.research4j.run.ModelAsk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceJudgeTest {

    private static final Fence FENCE = new Fence("Bienenhaltung im Stadtgarten", "", List.of(),
            List.of("Honigvermarktung"), "", "");

    private final List<String> prompts = new ArrayList<>();

    private SourceJudge judge(String... answers) {
        MainModelChat chat = new MainModelChat() {
            int i;

            @Override
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
                prompts.add(messages.get(messages.size() - 1).getContent());
                return MainModelChatResult.ok(answers[Math.min(i++, answers.length - 1)]);
            }

            @Override
            public String modelName() {
                return "test";
            }
        };
        return new SourceJudge(new ModelAsk(chat), new SourceJudge.Settings(20, 1200, 400, 1, 0), "de", FENCE);
    }

    private static FetchedContent content() {
        return new FetchedContent(SourceRef.of(SourceKind.WEB_PAGE, "https://bienen.example/stadt")
                .withTitle("Bienen in der Stadt"), "Ein Bienenvolk auf dem Balkon braucht vor allem Ruhe und Wasser.",
                Map.of("stand", "2026"), List.of(), null);
    }

    @Test
    void theProfileSaysWhereTheSourceLiesAndWhy() {
        SourceProfile profile = judge("{\"subject\":\"Bienen auf dem Balkon\",\"fence\":\"innen\","
                + "\"reason\":\"Haltung in der Stadt\"}").judge(content());

        assertEquals(FenceFit.INNEN, profile.fit());
        assertEquals("Bienen auf dem Balkon", profile.subject());
        assertTrue(prompts.get(0).contains("Bienenhaltung im Stadtgarten"));
        assertTrue(prompts.get(0).contains("Honigvermarktung"));
        assertTrue(prompts.get(0).contains("https://bienen.example/stadt"));
        assertFalse(prompts.get(0).contains("Ruhe und Wasser"), "only the beginning as set");
    }

    @Test
    void anUnknownFitGoesBackOnce() {
        SourceProfile profile = judge("{\"subject\":\"x\",\"fence\":\"VIELLEICHT\",\"reason\":\"r\"}",
                "{\"subject\":\"x\",\"fence\":\"AUSSEN\",\"reason\":\"Honigverkauf\"}").judge(content());

        assertEquals(FenceFit.AUSSEN, profile.fit());
        assertEquals(2, prompts.size());
    }

    @Test
    void withoutAValidAnswerThereIsNoJudgementAndTheReasonSaysWhy() {
        SourceProfile profile = judge("kein JSON").judge(content());

        assertNull(profile.fit());
        assertFalse(profile.judged());
        assertTrue(profile.reason().startsWith("invalid answer"), profile.reason());
    }

    @Test
    void onlyAClearlyOutsideSourceStaysOutOfTheBasket() {
        assertTrue(FenceFit.INNEN.belongsInBasket());
        assertTrue(FenceFit.GEMISCHT.belongsInBasket());
        assertTrue(FenceFit.UNKLAR.belongsInBasket());
        assertFalse(FenceFit.AUSSEN.belongsInBasket());
    }
}
