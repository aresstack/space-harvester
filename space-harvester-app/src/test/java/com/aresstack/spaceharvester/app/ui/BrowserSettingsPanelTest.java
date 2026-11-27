package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.spaceharvester.app.chat.browser.BrowserSettings;
import org.junit.jupiter.api.Test;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserSettingsPanelTest {

    private final Map<String, String> values = new HashMap<>();
    private final BrowserSettings settings = new BrowserSettings(new BrowserSettings.Store() {
        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void putAll(Map<String, String> changes) {
            values.putAll(changes);
        }
    });

    @Test
    void leavingAFieldNeverOverwritesAHostTheChatRememberedMeanwhile() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        // Der Chat merkt sich „Immer erlauben“, während die Seite schon gebaut ist.
        settings.allow(BrowserProfile.EXTERNAL, "wetter.example");

        leave(panel.external.whitelist);

        assertThat(settings.load().profile(BrowserProfile.EXTERNAL).allow()).containsExactly("wetter.example");
    }

    @Test
    void reloadingShowsTheRememberedHostAndAnEditIsSaved() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        settings.allow(BrowserProfile.EXTERNAL, "wetter.example");

        panel.load();
        assertThat(panel.external.whitelist.getText()).contains("wetter.example");
        panel.external.whitelist.setText("wetter.example\nkarte.example");
        leave(panel.external.whitelist);

        assertThat(settings.load().profile(BrowserProfile.EXTERNAL).allow())
                .containsExactly("wetter.example", "karte.example");
    }

    @Test
    void edgeIsTheDefaultAndAnotherChoiceIsSavedAndReported() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        int[] reported = new int[1];
        panel.onChannelChanged(() -> reported[0]++);
        assertThat(panel.channel.getSelectedItem()).isEqualTo(BrowserSettings.DEFAULT_CHANNEL);

        panel.channel.setSelectedItem("chrome");
        panel.load();

        assertThat(settings.channel()).isEqualTo("chrome");
        assertThat(panel.channel.getSelectedItem()).isEqualTo("chrome");
        assertThat(reported[0]).as("Neuladen meldet nichts").isEqualTo(1);
    }

    @Test
    void aSearchPageAddedToInternIsSavedForInternOnly() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        panel.internal.engines.searchPageQuestion = current -> "https://portal.example/suche?q={query}";

        panel.internal.engines.addSearchPage();

        assertThat(settings.engines(BrowserProfile.INTERNAL).getEntries()).extracting(e -> e.getEngineId())
                .containsExactly("https://portal.example/suche?q={query}");
        assertThat(settings.engines(BrowserProfile.EXTERNAL).getEntries()).extracting(e -> e.getEngineId())
                .doesNotContain("https://portal.example/suche?q={query}");
    }

    @Test
    void anAddressWithoutQueryIsAskedAgainAndCancellingSavesNothing() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        java.util.Iterator<String> answers = java.util.Arrays.asList("https://portal.example/", null).iterator();
        panel.internal.engines.searchPageQuestion = current -> answers.next();

        panel.internal.engines.addSearchPage();

        assertThat(panel.internal.engines.statusText()).isEqualTo(SearchEngineTable.NOT_A_SEARCH_PAGE);
        assertThat(values).doesNotContainKey("browser.search.internal.engines");
    }

    @Test
    void switchingOffAnEngineInTheTableIsSaved() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        javax.swing.JTable table = panel.external.engines.table();
        int row = -1;
        for (int i = 0; i < table.getRowCount(); i++) {
            if (Boolean.TRUE.equals(table.getValueAt(i, SearchEngineTable.COL_ACTIVE))) {
                row = i;
                break;
            }
        }
        assertThat(row).as("Extern hat eingeschaltete Suchmaschinen").isNotNegative();

        table.setValueAt(Boolean.FALSE, row, SearchEngineTable.COL_ACTIVE);

        assertThat(settings.engines(BrowserProfile.EXTERNAL).getEntries()).filteredOn(e -> e.isEnabled())
                .hasSize(1);
    }

    @Test
    void theSearchWindowSwitchIsSaved() {
        BrowserSettingsPanel panel = new BrowserSettingsPanel(settings);
        assertThat(panel.searchWindow.isSelected()).isTrue();

        panel.searchWindow.doClick();

        assertThat(settings.searchWindowShown()).isFalse();
    }

    private static void leave(javax.swing.JTextArea area) {
        for (FocusListener listener : area.getFocusListeners()) {
            listener.focusLost(new FocusEvent(area, FocusEvent.FOCUS_LOST));
        }
    }
}
