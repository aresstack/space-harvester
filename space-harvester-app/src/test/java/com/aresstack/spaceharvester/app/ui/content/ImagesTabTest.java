package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Reiter „Bilder“ der Leseansicht (#362). */
class ImagesTabTest {

    @Test
    void eachImageShowsItsStatusAndWithoutAVisionModelTheTabSaysSoInsteadOfFailing() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ImagesTab tab = new ImagesTab();
            tab.onDescribe(() -> seen.add("beschreiben"));
            tab.show(PassagesTab.State.CURRENT, null, List.of(
                    new ImagesTab.Image("architektur.png", ImagesTab.Status.DESCRIBED,
                            "Ein Schaubild mit drei Schichten.", null, null),
                    new ImagesTab.Image("logo.svg", ImagesTab.Status.SKIPPED, null, "zu klein", null)));
            seen.add(tab.stateLabel());
            seen.addAll(tab.lines());
            seen.add(tab.hint());
            tab.clickDescribe();
            tab.show(PassagesTab.State.MISSING, "vision-model-missing", List.of());
            seen.add(tab.hint());
            seen.add(String.valueOf(tab.describeOffered()));
            tab.show(PassagesTab.State.MISSING, "source-not-released", List.of());
            seen.add(tab.hint());
        });
        assertThat(seen).containsExactly("Aktuell", "architektur.png · Beschrieben", "logo.svg · Übersprungen: zu klein",
                " ", "beschreiben", ImagesTab.NO_MODEL, "false", ImagesTab.NOT_RELEASED);
    }
}
