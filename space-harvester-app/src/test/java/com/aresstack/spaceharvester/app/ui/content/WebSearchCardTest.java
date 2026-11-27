package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchCardTest {

    @Test
    void theCardNamesStepsAndCountsLikeTheEnterpriseAiClient() {
        WebExplorationProgress progress = new WebExplorationProgress(true, true, 12, 37, 3, 1, 0, 2,
                List.of(new WebExplorationProgress.Step(WebExplorationProgress.StepKind.OPENED, "Wetter", 0),
                        new WebExplorationProgress.Step(WebExplorationProgress.StepKind.LINKS, "Regen", 5),
                        new WebExplorationProgress.Step(WebExplorationProgress.StepKind.CHALLENGE, "login", 0)),
                "");
        assertThat(WebSearchCard.body(progress)).isEqualTo("""
                3 Seiten besucht · 37 Links geprüft · 2 relevant · 1 übersprungen
                „Wetter“ geöffnet
                5 Links passend zu „Regen“
                Anmeldung nötig: bitte im Browserfenster anmelden
                Browser arbeitet …""");
        assertThat(WebSearchCard.summary(progress, false))
                .isEqualTo("Browserrecherche abgeschlossen · 3 Seiten besucht · 37 Links geprüft · 2 relevante "
                        + "Seiten verwendet");
        assertThat(WebSearchCard.summary(progress, true)).startsWith("Browserrecherche abgebrochen");
    }

    @Test
    void aTurnWithoutBrowserCallsShowsNoCard() {
        int[] opened = {0};
        WebSearchCard card = new WebSearchCard(body -> {
            opened[0]++;
            return null;
        });
        card.update(WebExplorationProgress.none());
        card.finish(null, false);
        assertThat(opened[0]).isZero();
    }
}
