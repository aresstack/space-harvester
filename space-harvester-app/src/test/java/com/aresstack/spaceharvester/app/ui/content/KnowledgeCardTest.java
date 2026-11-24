package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.AnimatedThoughtBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCardTest {

    @Test
    void theCardShowsKnowledgeStepsSourcesAndABarAndBurstsWithTheAnswer() throws Exception {
        // Angelo 24.11.2026: alles in der gelben Blase, nichts über dem Eingabefeld, Bereichs- und interne Suche gleich.
        List<String> opened = new ArrayList<>();
        AgentActivityBubblePanel[] bubble = new AgentActivityBubblePanel[1];
        String[] body = new String[3];
        SwingUtilities.invokeAndWait(() -> {
            KnowledgeCard card = new KnowledgeCard(true, (title, text) -> {
                opened.add(title);
                bubble[0] = new AgentActivityBubblePanel(BubbleSide.LEFT, BubblePalette.windowsPhoneInspired(), title,
                        text);
                return bubble[0];
            });
            card.knowledge("Wissen: Space Handbuch · 3 Seiten");
            card.open();
            card.open();
            body[0] = card.body();
            card.step("Sucht im Bereich");
            card.progress(1, 4);
            card.stepDone("Sucht im Bereich");
            card.released(2);
            card.sources(List.of("Nachtlauf", "Spool"));
            card.progress(0, 4);
            body[1] = card.body();
            card.finish(false);
            card.step("zu spät");
            body[2] = card.summary(false);
        });

        assertThat(opened).containsExactly(KnowledgeCard.KNOWLEDGE_TITLE);
        assertThat(body[0]).startsWith("Wissen: Space Handbuch · 3 Seiten").contains(KnowledgeCard.STARTING)
                .as("ohne Treffer kein Balken").doesNotContain("[[bar:");
        assertThat(body[1]).contains("✓ Sucht im Bereich", "Quellen: [1] Nachtlauf  [2] Spool",
                "2 Seiten für KI freigegeben").as("der Balken geht nie zurück").endsWith("[[bar:1/4]]");
        assertThat(body[2]).isEqualTo("Recherche abgeschlossen · 1 Schritt · 2 Quellen · 2 Seiten für KI freigegeben");
        assertThat(bubble[0].getVisualState()).isNotEqualTo(AnimatedThoughtBubblePanel.VisualState.RUNNING);
    }

    @Test
    void withoutKnowledgeTheCardOnlyPreparesTheAnswer() {
        KnowledgeCard card = new KnowledgeCard(false, (title, text) -> null);
        assertThat(card.title()).isEqualTo(KnowledgeCard.TITLE);
        assertThat(card.body()).startsWith(KnowledgeCard.THINKING);
    }
}
