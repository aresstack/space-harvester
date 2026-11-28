package com.aresstack.enterpriseai.ui.comic.theme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The classic theme is pinned to the values the library had as hardcoded colors before the token table
 * (the literals below are copied from the pre-theme sources), and living colors follow a theme switch.
 */
public class AskAiThemeTest {

    private AskAiTheme before;

    @Before
    public void remember() {
        before = AskAiTheme.active();
    }

    @After
    public void restore() {
        AskAiTheme.setActive(before);
    }

    private static Map<String, Integer> classicLiterals() {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        m.put("comic.surface", 0xF4F4F4);
        m.put("comic.ink", 0x252525);
        m.put("comic.navigationBlue", 0x1676D2);
        m.put("comic.agentPetrol", 0x15827A);
        m.put("comic.accentYellow", 0xF2C94C);
        m.put("comic.accentOrange", 0xE39A18);
        m.put("comic.accentRed", 0xC94C4C);
        m.put("bubble.transcriptBackground", 0xF4F4F4);
        m.put("bubble.userBackground", 0x1676D2);
        m.put("bubble.userForeground", 0xFFFFFF);
        m.put("bubble.assistantBackground", 0x15827A);
        m.put("bubble.assistantForeground", 0xFFFFFF);
        m.put("bubble.activityBackground", 0xF2C94C);
        m.put("bubble.activityForeground", 0x252525);
        m.put("bubble.activityAccent", 0xE39A18);
        m.put("bubble.failureAccent", 0xC94C4C);
        m.put("bubble.infoForeground", 0x6B6F76);
        m.put("comic.buttonPlate", 0xFFFFFF);
        m.put("comic.fieldBg", 0xFFFFFF);
        m.put("comic.searchBarBorder", 0xBBBBBB);
        m.put("comic.hintText", 0x888888);
        m.put("comic.hintTextSoft", 0xAAAAAA);
        m.put("comic.placeholderText", 0x6B6F76);
        m.put("chat.composerButtonDisabledFill", 0xB7BBC1);
        m.put("chat.composerButtonDisabledText", 0xA5A9AE);
        m.put("chat.composerButtonHoverShade", 0x000000);
        m.put("comic.checkBorderShade", 0x000000);
        m.put("comic.folderPaper", 0xFFF1C2);
        m.put("research.ACCENT_BLUE", 0x2979FF);
        m.put("research.BORDER_DARK", 0x5E6370);
        m.put("research.BORDER_WINDOW", 0x38404D);
        m.put("research.CHIP_CLOSE_HOVER", 0x2A3039);
        m.put("research.CHIP_HOVER_BORDER", 0x767D89);
        m.put("research.CHIP_HOVER_SURFACE", 0x151B23);
        m.put("research.CHIP_SURFACE", 0x0E1218);
        m.put("research.CLOUD_BORDER", 0xAFCDE8);
        m.put("research.CLOUD_CLOSE", 0x72879A);
        m.put("research.CLOUD_HOVER_BORDER", 0x8FB8DE);
        m.put("research.CLOUD_HOVER_SURFACE", 0xEAF4FC);
        m.put("research.CLOUD_SURFACE", 0xF7FBFF);
        m.put("research.CLOUD_TEXT", 0x40556B);
        m.put("research.COMPASS_RED", 0xF03A3A);
        m.put("research.DANGER_RED", 0xD32F2F);
        m.put("research.DARK_BASE", 0x0C1117);
        m.put("research.DARK_SURFACE", 0x12181F);
        m.put("research.FAVORITE_YELLOW", 0xFFC800);
        m.put("research.FAVORITE_YELLOW_EDGE", 0xC99A00);
        m.put("research.FIND_AMBER", 0xF57C00);
        m.put("research.LIGHT_CONTROL_BORDER", 0xC9CED6);
        m.put("research.LIGHT_CONTROL_TEXT", 0x44484D);
        m.put("research.LIGHT_TEXT_MUTED", 0x8A909B);
        m.put("research.RECORDING_ORANGE", 0xF57C00);
        m.put("research.RECORDING_SURFACE", 0xFFF8E1);
        m.put("research.SECONDARY_HOVER", 0x202832);
        m.put("research.SECONDARY_SURFACE", 0x141A21);
        m.put("research.SKY_BAR_SURFACE", 0xBFDCF2);
        m.put("research.SKY_CAPTION", 0x6E87A2);
        m.put("research.SKY_TOP", 0xF5FAFE);
        m.put("research.TAG_SUGGESTION_SURFACE", 0xF2C94C);
        m.put("research.TAG_SUGGESTION_TEXT", 0x252525);
        m.put("research.TEXT_DARK", 0x171C29);
        m.put("research.TEXT_MUTED", 0xADB5C7);
        m.put("research.TEXT_PRIMARY", 0xE8EBF2);
        m.put("research.WAVE_GREEN", 0x2E7D32);
        m.put("research.WORKSPACE_DIVIDER", 0x282E38);
        m.put("research.WORKSPACE_SURFACE", 0x1B2028);
        m.put("research.SCROLL_PAPER", 0xFFFFFF);
        m.put("research.LIGHT_CONTROL_BG", 0xFFFFFF);
        return m;
    }

    @Test
    public void classicTokensArePinnedToTheFormerLiterals() {
        AskAiTheme t = AskAiTheme.classic();
        for (Map.Entry<String, Integer> e : classicLiterals().entrySet()) {
            assertEquals(e.getKey(), new Color(e.getValue()), t.color(e.getKey()));
        }
        assertEquals(new Color(0, 0, 0, 110), t.overlayDim());
        assertEquals(new Color(0xF4, 0x8F, 0xB1, 72), t.color("research.ACRONYM_MARK"));
    }

    @Test
    public void paletteConstantsAndDefaultPalettesShowTheClassicValuesByDefault() {
        AskAiTheme.setActive(AskAiTheme.classic());
        for (Map.Entry<String, Integer> e : classicLiterals().entrySet()) {
            if (e.getKey().startsWith("research.") && e.getKey().matches("research\\.[A-Z0-9_]+")) {
                assertEquals(e.getKey(), new Color(e.getValue()).getRGB(),
                        ThemeColor.of(e.getKey()).getRGB() | 0xFF000000);
            }
        }
        ComicPalette p = ComicPalette.defaultPalette();
        assertEquals(new Color(0xF4F4F4), p.getSurface());
        assertEquals(new Color(0x252525), p.getInk());
        assertEquals(new Color(0x1676D2), p.getNavigationBlue());
        assertEquals(new Color(0x15827A), p.getAgentPetrol());
        assertEquals(new Color(0xF2C94C), p.getAccentYellow());
        assertEquals(new Color(0xE39A18), p.getAccentOrange());
        assertEquals(new Color(0xC94C4C), p.getAccentRed());
        assertEquals(new Color(0x6B6F76), BubblePalette_info());
    }

    private static Color BubblePalette_info() {
        return com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette.windowsPhoneInspired().getInfoForeground();
    }

    @Test
    public void resolveFallsBackToClassicAndRegisteredThemesAreFoundCaseInsensitively() {
        assertEquals("classic", AskAiTheme.resolve(null).name());
        assertEquals("classic", AskAiTheme.resolve("  nonsense ").name());
        Map<String, Color> table = new LinkedHashMap<String, Color>();
        table.put("comic.ink", new Color(0x102030));
        AskAiTheme.registerTheme("Unit-Test-Theme", table);
        AskAiTheme custom = AskAiTheme.resolve(" UNIT-test-THEME ");
        assertEquals("unit-test-theme", custom.name());
        assertEquals(new Color(0x102030), custom.color("comic.ink"));
        // a token the theme leaves out keeps the classic value
        assertEquals(new Color(0xF4F4F4), custom.color("comic.surface"));
        assertTrue(AskAiTheme.themeNames().contains("unit-test-theme"));
        assertEquals("classic", AskAiTheme.themeNames().get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theDefaultThemeCannotBeReplaced() {
        AskAiTheme.registerTheme(" Classic ", new LinkedHashMap<String, Color>());
    }

    @Test
    public void hostTokensFallBackToTheClassicHostValue() {
        Map<String, Color> classic = new LinkedHashMap<String, Color>();
        classic.put("host.unitTestMark", new Color(0x112233));
        AskAiTheme.registerTokens("classic", classic);
        Map<String, Color> table = new LinkedHashMap<String, Color>();
        AskAiTheme.registerTheme("host-fallback-theme", table);
        AskAiTheme theme = AskAiTheme.resolve("host-fallback-theme");
        assertEquals(new Color(0x112233), theme.color("host.unitTestMark"));
        Map<String, Color> own = new LinkedHashMap<String, Color>();
        own.put("host.unitTestMark", new Color(0x445566));
        AskAiTheme.registerTokens("host-fallback-theme", own);
        assertEquals(new Color(0x445566), theme.color("host.unitTestMark"));
        assertEquals(new Color(0x112233), AskAiTheme.classic().color("host.unitTestMark"));
        assertTrue(theme.hasToken("host.unitTestMark"));
    }

    @Test
    public void changeListenersFireOnlyOnFireChanged() {
        final AtomicInteger count = new AtomicInteger();
        Runnable listener = new Runnable() {
            @Override
            public void run() {
                count.incrementAndGet();
            }
        };
        AskAiTheme.addChangeListener(listener);
        AskAiTheme.addChangeListener(listener);
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(0, count.get());
        AskAiTheme.fireChanged();
        assertEquals(1, count.get());
        AskAiTheme.removeChangeListener(listener);
        AskAiTheme.fireChanged();
        assertEquals(1, count.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTokenIsRejected() {
        AskAiTheme.classic().color("comic.doesNotExist");
    }

    @Test
    public void livingColorsFollowTheSwitchAndAPanelRepaintsInTheNewTheme() {
        Map<String, Color> table = new LinkedHashMap<String, Color>();
        table.put("research.DARK_BASE", new Color(0x0B1220));
        table.put("comic.ink", new Color(0x24304A));
        AskAiTheme.registerTheme("switch-test-theme", table);
        ThemeColor ink = ThemeColor.of("comic.ink");
        assertSame(ink, ThemeColor.of("comic.ink"));
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(ResearchUiPalette.DARK_BASE);
        panel.setSize(10, 10);
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(0x0C1117, paintedPixel(panel));
        assertEquals(0x252525, ink.getRGB() & 0xFFFFFF);
        AskAiTheme.setActive(AskAiTheme.resolve("switch-test-theme"));
        assertEquals(0x0B1220, paintedPixel(panel));
        assertEquals(0x24304A, ink.getRGB() & 0xFFFFFF);
        assertEquals(0x24304A, ComicPalette.defaultPalette().getInk().getRGB() & 0xFFFFFF);
        ThemeColor dim = ThemeColor.withAlpha(ink, 100);
        assertEquals(new Color(0x24, 0x30, 0x4A, 100), new Color(dim.getRGB(), true));
        assertFalse(new Color(0x252525).equals(ink));
    }

    private static int paintedPixel(JPanel panel) {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            panel.paint(g);
        } finally {
            g.dispose();
        }
        return image.getRGB(5, 5) & 0xFFFFFF;
    }
}
