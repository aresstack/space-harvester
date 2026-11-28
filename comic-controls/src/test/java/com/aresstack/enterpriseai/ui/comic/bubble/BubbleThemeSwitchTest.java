package com.aresstack.enterpriseai.ui.comic.bubble;

import com.aresstack.enterpriseai.ui.comic.theme.AskAiTheme;
import com.aresstack.enterpriseai.ui.comic.theme.ThemeColor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Colors a bubble derives from its text color (header, details toggle, details text) follow a theme switch. */
public class BubbleThemeSwitchTest {

    private AskAiTheme before;

    @Before
    public void remember() {
        before = AskAiTheme.active();
    }

    @After
    public void restore() {
        AskAiTheme.setActive(before);
    }

    @Test
    public void derivedBubbleColorsAreRecalculatedAfterTheSwitch() throws Exception {
        Map<String, Color> table = new LinkedHashMap<String, Color>();
        table.put("bubble.userForeground", new Color(0x102030));
        AskAiTheme.registerTheme("bubble-switch-test", table);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                AskAiTheme.setActive(AskAiTheme.classic());
                SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.LEFT,
                        ThemeColor.of("bubble.userBackground"), ThemeColor.of("bubble.userForeground"),
                        "Assistent", "Text");
                bubble.setDetailsLabels("Details anzeigen", "Details ausblenden");
                bubble.setDetails("mehr");
                assertEquals(0xDCFFFFFF, bubble.detailsToggle().getForeground().getRGB());
                AskAiTheme.setActive(AskAiTheme.resolve("bubble-switch-test"));
                assertEquals(0xDC102030, bubble.detailsToggle().getForeground().getRGB());
                // the hover wash of the copy and QR buttons is derived live as well
                AskAiTheme.setActive(AskAiTheme.classic());
                Color wash = MessageCopyButton.overlay(ThemeColor.of("bubble.userForeground"));
                assertEquals(0x24FFFFFF, wash.getRGB());
                AskAiTheme.setActive(AskAiTheme.resolve("bubble-switch-test"));
                assertEquals(0x24102030, wash.getRGB());
            }
        });
    }
}
