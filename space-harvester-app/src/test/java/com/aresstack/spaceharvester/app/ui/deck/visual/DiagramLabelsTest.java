package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.ui.AppState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** KI-Kurzbeschriftung zusammengefasster Blöcke: nur eingeschaltet, gestreamt, bereinigt und zwischengespeichert. */
class DiagramLabelsTest {

    @AfterEach
    void off() {
        AppState.shared().put(DiagramLabels.ENABLED_KEY, false);
    }

    @Test
    void streamsShortLabelOnlyWhenSwitchedOn() {
        List<AiChat.Request> requests = new ArrayList<>();
        AiChat chat = (request, listener) -> {
            requests.add(request);
            listener.delta("„Kunden");
            listener.delta("daten\nprüfen“");
            listener.completed("„Kundendaten\nprüfen“");
            return () -> { };
        };
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "gemma4:12b", "none", null));

        AppState.shared().put(DiagramLabels.ENABLED_KEY, false);
        assertThat(labels.enabled()).isFalse();
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        assertThat(labels.enabled()).isTrue();

        String label = labels.summarize("IF #KUNDE = ' '\n  ESCAPE ROUTINE\nEND-IF", 60, "UML-Zusammenfassung");
        assertThat(label).isEqualTo("Kundendaten prüfen");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).model()).isEqualTo("gemma4:12b");
        assertThat(requests.get(0).reasoningEffort()).isEqualTo("none");
        assertThat(requests.get(0).maxTokens()).as("ohne Budget die Einstellung des Dienstes").isNull();
        assertThat(requests.get(0).messages().get(0).text()).isEqualTo(DiagramLabels.SYSTEM_PROMPT);

        // zweites Mal aus dem Zwischenspeicher, kein neuer Aufruf
        labels.summarize("IF #KUNDE = ' '\n  ESCAPE ROUTINE\nEND-IF", 60, "UML-Zusammenfassung");
        assertThat(requests).hasSize(1);
        assertThat(labels.takeProblem()).isNull();
    }

    @Test
    void sentencesAndDetailedDescriptionsAskForTheirStyleAndAreCachedPerPurpose() {
        List<AiChat.Request> requests = new ArrayList<>();
        AiChat chat = (request, listener) -> {
            requests.add(request);
            listener.completed("Liest den nächsten Satz. Zählt ihn mit.");
            return () -> { };
        };
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "m", null, null));

        assertThat(labels.describe("READ WORK FILE 1 #SATZ\nADD 1 TO #N", 600, "Block"))
                .isEqualTo("Liest den nächsten Satz. Zählt ihn mit.");
        assertThat(requests.get(0).messages().get(1).text()).startsWith("STIL: DETAILS\n");
        labels.explain("CALLNAT 'X'", 140, "Zweck an Stelle 1");
        assertThat(requests.get(1).messages().get(1).text()).startsWith("STIL: SENTENCE\n");
        // derselbe Code mit anderem Zweck (andere Aufrufstelle) ist eine neue Frage
        labels.explain("CALLNAT 'X'", 140, "Zweck an Stelle 2");
        assertThat(requests).hasSize(3);
        labels.explain("CALLNAT 'X'", 140, "Zweck an Stelle 2");
        assertThat(requests).hasSize(3);
    }

    @Test
    void failureLeavesPlainLabelAndReportsReasonOnce() {
        AiChat chat = (request, listener) -> {
            listener.failed(new com.aresstack.spaceharvester.ai.AiChatException(
                    com.aresstack.spaceharvester.ai.AiChatException.Kind.PROVIDER_ERROR, 503, "Dienst antwortet 503", null));
            return () -> { };
        };
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "m", null, null));

        assertThat(labels.summarize("MOVE 1 TO #A", 60, "x")).isNull();
        assertThat(labels.takeProblem()).contains("503");
        assertThat(labels.takeProblem()).isNull();
    }

    /** F41-7: Langer Code wird nicht abgeschnitten, sondern in Teilen beschriftet; jede Zeile kommt beim Modell an. */
    @Test
    void longCodeIsLabelledInPartsWithoutLosingALine() {
        List<AiChat.Request> requests = new ArrayList<>();
        AiChat chat = (request, listener) -> {
            requests.add(request);
            listener.completed("Teil " + requests.size());
            return () -> { };
        };
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            code.append("MOVE #FELD-").append(i).append(" TO #ZIEL-").append(i).append('\n');
        }
        com.aresstack.spaceharvester.app.runtime.ModelBudget budget =
                new com.aresstack.spaceharvester.app.runtime.ModelBudget(4096, 512);
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "m", null, budget));

        String label = labels.summarize(code.toString(), 60, "UML-Zusammenfassung");

        assertThat(label).isNotBlank();
        assertThat(requests.size()).isGreaterThan(2);
        String sent = requests.subList(0, requests.size() - 1).stream()
                .map(request -> request.messages().get(1).text()).reduce("", String::concat);
        assertThat(sent).contains("#FELD-0 ", "#FELD-1999 ");
        for (AiChat.Request request : requests) {
            int tokens = com.aresstack.spaceharvester.app.runtime.ModelBudget.tokens(DiagramLabels.SYSTEM_PROMPT)
                    + com.aresstack.spaceharvester.app.runtime.ModelBudget.tokens(request.messages().get(1).text());
            assertThat(tokens).isLessThanOrEqualTo(budget.contextTokens() - budget.answerTokens());
        }
        assertThat(DiagramLabels.parts("MOVE 1 TO #A", 60, "x", budget)).hasSize(1);
        assertThat(DiagramLabels.parts(code.toString(), 60, "x", null)).hasSize(1);
    }

    @Test
    void offWithoutModel() {
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        assertThat(new DiagramLabels(() -> null).enabled()).isFalse();
        assertThat(new DiagramLabels(() -> new DiagramLabels.Target(null, "m", null, null)).enabled()).isFalse();
    }

    @Test
    void theOutputLimitOfTheSourceCodeRowGoesIntoEveryRequest() {
        List<AiChat.Request> requests = new ArrayList<>();
        AiChat chat = (request, listener) -> {
            requests.add(request);
            listener.completed("Prüft den Kunden.");
            return () -> { };
        };
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "m", null,
                new com.aresstack.spaceharvester.app.runtime.ModelBudget(32_000, 2048), 2048));

        labels.explain("IF #KUNDE = ' '\n  ESCAPE ROUTINE\nEND-IF", 60, "Zweck");

        assertThat(requests).singleElement().satisfies(r -> assertThat(r.maxTokens()).isEqualTo(2048));
    }
}
