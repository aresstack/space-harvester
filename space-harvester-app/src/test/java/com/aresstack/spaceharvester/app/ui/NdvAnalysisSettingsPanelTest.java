package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.deck.NdvAnalysisSettings;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Einstellungen › NDV, Analyse: Suchreihenfolge, STEPLIB-Zuordnung, Sperr- und Freigabeliste, wirken sofort. */
class NdvAnalysisSettingsPanelTest {

    @AfterEach
    void reset() {
        NdvAnalysisSettings.searchOrder(List.of());
        NdvAnalysisSettings.stepLibs(Map.of());
        NdvAnalysisSettings.rules().remove(NdvAnalysisSettings.rules().blacklistEntries());
        NdvAnalysisSettings.rules().remove(NdvAnalysisSettings.rules().whitelistEntries());
        NdvAnalysisSettings.rules().blacklistAll(false);
    }

    @Test
    void everyChangeIsStoredAtOnce() throws Exception {
        reset();
        SwingUtilities.invokeAndWait(() -> {
            NdvAnalysisSettingsPanel panel = new NdvAnalysisSettingsPanel();
            panel.orderComponent();
            panel.stepLibComponent();
            panel.rulesComponent();
            panel.orderField.setText("system");
            panel.addLibrary();
            panel.orderField.setText("kasse");
            panel.addLibrary();
            panel.orderField.setText("KASSE");
            panel.addLibrary();
            assertThat(NdvAnalysisSettings.searchOrder()).containsExactly("SYSTEM", "KASSE");
            panel.orderList.setSelectedIndex(1);
            panel.move(-1);
            assertThat(NdvAnalysisSettings.searchOrder()).containsExactly("KASSE", "SYSTEM");
            panel.removeLibrary();
            assertThat(NdvAnalysisSettings.searchOrder()).containsExactly("SYSTEM");

            panel.stepField.setText("abak-m");
            panel.libField.setText("abak-t");
            panel.addStepLib();
            assertThat(NdvAnalysisSettings.stepLibs()).isEqualTo(Map.of("ABAK-M", "ABAK-T"));
            panel.stepLibs.setValueAt("ABAK-X", 0, 1);
            assertThat(NdvAnalysisSettings.stepLibs()).isEqualTo(Map.of("ABAK-M", "ABAK-X"));
            panel.stepLibTable.setRowSelectionInterval(0, 0);
            panel.removeStepLib();
            assertThat(NdvAnalysisSettings.stepLibs()).isEmpty();

            panel.pathField.setText("LAGER");
            panel.addRule(true);
            panel.pathField.setText("ndv://KASSE/KASSE001");
            panel.addRule(false);
            assertThat(NdvAnalysisSettings.rules().blacklistEntries()).containsExactly("ndv://LAGER");
            assertThat(NdvAnalysisSettings.rules().whitelistEntries()).containsExactly("ndv://KASSE/KASSE001");
            assertThat(panel.rules.getRowCount()).isEqualTo(2);
            panel.blacklistAll.doClick();
            assertThat(NdvAnalysisSettings.rules().blacklistAll()).isTrue();
            panel.rules.setValueAt(true, 0, 0);
            panel.removeChecked();
            assertThat(NdvAnalysisSettings.rules().blacklistEntries()).isEmpty();
            assertThat(panel.rules.getRowCount()).isEqualTo(1);
        });
    }
}
