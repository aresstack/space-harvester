package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.Acquisition;
import com.aresstack.spaceharvester.app.runtime.Acquisition.Pause;
import com.aresstack.spaceharvester.app.runtime.Acquisition.SourceCounts;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundPlateTest {

    @Test
    void theStageIsNamedInWords() {
        assertThat(BackgroundPlate.stage(status(true, Pause.FOREGROUND, 0))).isEqualTo("pausiert: eine Suche lädt Seiten");
        assertThat(BackgroundPlate.stage(status(true, Pause.COOLDOWN, 0)))
                .isEqualTo("pausiert: wartet kurz nach der letzten Aktion");
        assertThat(BackgroundPlate.stage(status(true, Pause.NONE, 2))).isEqualTo("lädt im Hintergrund");
        assertThat(BackgroundPlate.stage(status(false, Pause.NONE, 0)))
                .isEqualTo("abgeschaltet (Einstellungen › Cache)");
        assertThat(BackgroundPlate.stage(status(true, Pause.IDLE, 0))).isEqualTo("nichts zu laden: alle Seiten aktuell");
    }

    @Test
    void thePlateShowsTotalsAndEachSource() throws Exception {
        List<String> values = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BackgroundPlate plate = new BackgroundPlate(Optional::empty);
            assertThat(texts(plate)).contains("noch nicht gestartet");
            plate.render(status(true, Pause.NONE, 2));
            values.addAll(texts(plate));
        });
        assertThat(values).contains("lädt im Hintergrund", "12 Seiten · davon 1 für Suche oder Aktion",
                "1 für Suche oder Aktion · 2 im Hintergrund (höchstens 2)", "40 · 3 vorgezogen, weil eine Suche sie brauchte",
                "5", "10 wartend · 30 geladen · 5 fehlgeschlagen");
    }

    private static Acquisition status(boolean enabled, Pause pause, int background) {
        return new Acquisition(enabled, pause, 12, 1 + background, 1, 1, background, 40, 5, 3, 2,
                Instant.parse("2026-10-18T20:00:00Z"), List.of(new SourceCounts("wiki", 10, 30, 5)));
    }

    private static List<String> texts(BackgroundPlate plate) {
        List<String> texts = new ArrayList<>();
        for (Component c : plate.facts().getComponents()) {
            if (c instanceof JTextField field) {
                texts.add(field.getText());
            }
        }
        return texts;
    }
}
