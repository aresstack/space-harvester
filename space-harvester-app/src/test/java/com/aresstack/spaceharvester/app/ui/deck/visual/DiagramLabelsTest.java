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
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "gemma4:12b", "none"));

        assertThat(labels.enabled()).isFalse();
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        assertThat(labels.enabled()).isTrue();

        String label = labels.summarize("IF #KUNDE = ' '\n  ESCAPE ROUTINE\nEND-IF", 60, "UML-Zusammenfassung");
        assertThat(label).isEqualTo("Kundendaten prüfen");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).model()).isEqualTo("gemma4:12b");
        assertThat(requests.get(0).reasoningEffort()).isEqualTo("none");
        assertThat(requests.get(0).messages().get(0).text()).isEqualTo(DiagramLabels.SYSTEM_PROMPT);

        // zweites Mal aus dem Zwischenspeicher, kein neuer Aufruf
        labels.summarize("IF #KUNDE = ' '\n  ESCAPE ROUTINE\nEND-IF", 60, "UML-Zusammenfassung");
        assertThat(requests).hasSize(1);
        assertThat(labels.takeProblem()).isNull();
    }

    @Test
    void failureLeavesPlainLabelAndReportsReasonOnce() {
        AiChat chat = (request, listener) -> {
            listener.failed(new com.aresstack.spaceharvester.ai.AiChatException(
                    com.aresstack.spaceharvester.ai.AiChatException.Kind.PROVIDER_ERROR, 503, "Dienst antwortet 503", null));
            return () -> { };
        };
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        DiagramLabels labels = new DiagramLabels(() -> new DiagramLabels.Target(chat, "m", null));

        assertThat(labels.summarize("MOVE 1 TO #A", 60, "x")).isNull();
        assertThat(labels.takeProblem()).contains("503");
        assertThat(labels.takeProblem()).isNull();
    }

    @Test
    void offWithoutModel() {
        AppState.shared().put(DiagramLabels.ENABLED_KEY, true);
        assertThat(new DiagramLabels(() -> null).enabled()).isFalse();
        assertThat(new DiagramLabels(() -> new DiagramLabels.Target(null, "m", null)).enabled()).isFalse();
    }
}
