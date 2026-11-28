package com.aresstack.spaceharvester.app.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.theme.AskAiTheme;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThemeSwitcherTest {

    private AskAiTheme before;

    @BeforeEach
    void remember() {
        AppColors.of("app.Comic.notice");
        before = AskAiTheme.active();
    }

    @AfterEach
    void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> ThemeSwitcher.apply(AskAiTheme.classic()));
        AppState.shared().put(ThemeSwitcher.KEY, (String) null);
        AskAiTheme.setActive(before);
    }

    @Test
    void theDefaultThemeIsClassicShownAsStandardAndListedFirst() {
        assertThat(ThemeSwitcher.names().get(0)).isEqualTo(AskAiTheme.CLASSIC);
        assertThat(ThemeSwitcher.label(AskAiTheme.CLASSIC)).isEqualTo("Standard");
    }

    @Test
    void anUnknownOrMissingStoredThemeMeansClassic() {
        AppState.shared().put(ThemeSwitcher.KEY, "gibt-es-nicht");
        assertThat(ThemeSwitcher.activateStored().name()).isEqualTo(AskAiTheme.CLASSIC);
        AppState.shared().put(ThemeSwitcher.KEY, (String) null);
        assertThat(ThemeSwitcher.activateStored().name()).isEqualTo(AskAiTheme.CLASSIC);
    }

    @Test
    void applyingAThemeChangesLiveColorsOnceNotifiesListenersAndRemembersTheChoice() throws Exception {
        Map<String, Color> table = new LinkedHashMap<>();
        table.put("app.Comic.notice", new Color(0x112233));
        AskAiTheme.registerTheme("switcher-test", table);
        AtomicInteger changed = new AtomicInteger();
        Runnable listener = changed::incrementAndGet;
        AskAiTheme.addChangeListener(listener);
        try {
            assertThat(Comic.NOTICE.getRGB() & 0xFFFFFF).isEqualTo(0xFFF6D5);
            SwingUtilities.invokeAndWait(() -> ThemeSwitcher.apply(AskAiTheme.resolve("switcher-test")));
            assertThat(Comic.NOTICE.getRGB() & 0xFFFFFF).isEqualTo(0x112233);
            assertThat(changed.get()).isEqualTo(1);
            assertThat(AppState.shared().get(ThemeSwitcher.KEY)).isEqualTo("switcher-test");
            assertThat(ThemeSwitcher.activateStored().name()).isEqualTo("switcher-test");
            // a token the theme leaves out keeps the classic value
            assertThat(Comic.PODCAST_GAP.getRGB() & 0xFFFFFF).isEqualTo(0xE6E8EB);
        } finally {
            AskAiTheme.removeChangeListener(listener);
        }
    }
}
