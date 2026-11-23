package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewDefaults;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Einstellungen › BetaView › Standard-Suchfilter wie in MainframeMate. */
class BetaViewSettingsPanelTest {

    @Test
    void savesTheStandardFilterAndResetsToTheMainframeMateValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppState.shared().put("deck.betaview.filter.favorite", "ALT1");
            AppState.shared().put("deck.betaview.filter.unit", "HOURS");
            BetaViewSettingsPanel panel = new BetaViewSettingsPanel();
            panel.favorite.setText("F002");
            panel.extension.setText("WV00BANK");
            panel.daysBack.setText("14");
            panel.save();
            BetaViewDefaults saved = BetaViewDefaults.load();
            assertThat(saved).isEqualTo(new BetaViewDefaults("F002", "de", "WV00BANK", "APZF", 14));
            // Gilt für die nächste Suchmaske: die zuletzt benutzte Favorit-Angabe ist vergessen.
            assertThat(AppState.shared().get("deck.betaview.filter.favorite")).isNull();
            // Die Einheit des letzten Zeitraums gilt nicht für die neue Zahl in Tagen.
            assertThat(AppState.shared().get("deck.betaview.filter.unit")).isNull();

            panel.daysBack.setText("zwei");
            panel.save();
            assertThat(BetaViewDefaults.load().daysBack()).isEqualTo(14);

            panel.render();
            panel.favorite.setText("");
            panel.extension.setText("");
            panel.daysBack.setText("");
            panel.save();
            assertThat(BetaViewDefaults.load()).isEqualTo(BetaViewDefaults.MAINFRAMEMATE);
            assertThat(BetaViewDefaults.MAINFRAMEMATE)
                    .isEqualTo(new BetaViewDefaults("A158", "de", "*", "APZF", 60));
        });
    }

    @Test
    void betaViewConnectsDirectlyUnlessTheAppProxyIsSwitchedOn() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppState.shared().put(BetaViewAccess.USE_PROXY, (String) null);
            // Vorgabe wie MainframeMate: kein Proxy.
            assertThat(BetaViewAccess.useProxy()).isFalse();
            BetaViewSettingsPanel panel = new BetaViewSettingsPanel();
            assertThat(panel.useProxy.isSelected()).isFalse();
            panel.useProxy.doClick();
            assertThat(BetaViewAccess.useProxy()).isTrue();
            assertThat(new BetaViewSettingsPanel().useProxy.isSelected()).isTrue();
            panel.useProxy.doClick();
            assertThat(BetaViewAccess.useProxy()).isFalse();
        });
    }
}
