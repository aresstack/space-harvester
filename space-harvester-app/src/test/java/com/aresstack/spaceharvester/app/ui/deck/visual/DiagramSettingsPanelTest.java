package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.mainframe.diagram.SystemFunction;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunctions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Einstellungen › Diagramme › Systemfunktionen: Vorgabe, Bearbeiten in der Tabelle, sofort gespeichert. */
class DiagramSettingsPanelTest {

    @TempDir
    Path folder;

    @Test
    void startsWithMainframeMateDefaultsAndSavesEdits() throws Exception {
        Path file = folder.resolve("system-functions.json");
        SystemFunctionsStore store = new SystemFunctionsStore(file);
        assertThat(store.names()).contains("IDCAMS", "IEFBR14", "SORT").hasSize(SystemFunctions.DEFAULTS.size());

        AtomicReference<DiagramSettingsPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new DiagramSettingsPanel(store)));
        SwingUtilities.invokeAndWait(() -> {
            var model = panel.get().table().getModel();
            int last = model.getRowCount();
            assertThat(last).isEqualTo(SystemFunctions.DEFAULTS.size());
            // Programmname wird groß geschrieben und sofort gespeichert
            model.setValueAt("myutil", 0, 0);
        });
        assertThat(Files.readString(file)).contains("MYUTIL");
        assertThat(new SystemFunctionsStore(file).names()).contains("MYUTIL").doesNotContain("IDCAMS");

        // Änderungen von außen (anderes Fenster) zeigt die Tabelle nach dem Speichern
        store.save(List.of(new SystemFunction("MYUTIL", "", "", "eigenes Programm")));
        SwingUtilities.invokeAndWait(() -> panel.get().render());
        assertThat(store.names()).containsExactly("MYUTIL");
        SwingUtilities.invokeAndWait(() -> assertThat(panel.get().table().getRowCount()).isEqualTo(1));
    }

    @Test
    void wikipediaLinkLikeMainframeMate() {
        assertThat(new SystemFunction("IDCAMS", "Access Method Services", "", "").wikipediaUrl())
                .isEqualTo("https://de.wikipedia.org/wiki/Access_Method_Services");
        assertThat(new SystemFunction("XYZ", "", "", "").wikipediaUrl()).contains("search=XYZ");
    }
}
