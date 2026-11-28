package com.aresstack.enterpriseai.ui.comic.theme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** E8: living colors follow {@link AskAiTheme#setActive} without any consumer code. */
public class ThemeColorTest {

    private AskAiTheme before;

    /** A test theme: pure color table, the tokens these tests look at. */
    private static AskAiTheme darkTheme() {
        java.util.Map<String, Color> table = new java.util.LinkedHashMap<String, Color>();
        table.put("comic.ink", new Color(0x24304A));
        table.put("research.DARK_BASE", new Color(0x0B1220));
        table.put("comic.navigationBlue", new Color(0x24406E));
        table.put("comic.fieldBg", new Color(0xF6EEDD));
        AskAiTheme.registerTheme("theme-color-test-dark", table);
        return AskAiTheme.resolve("theme-color-test-dark");
    }

    @Before
    public void remember() {
        before = AskAiTheme.active();
    }

    @After
    public void restore() {
        AskAiTheme.setActive(before);
    }

    @Test
    public void tokenFollowsTheActiveTheme() {
        ThemeColor ink = ThemeColor.of("comic.ink");
        assertSame(ink, ThemeColor.of("comic.ink"));
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(0x252525, ink.getRGB() & 0xFFFFFF);
        assertEquals(0x25, ink.getRed());
        AskAiTheme.setActive(darkTheme());
        assertEquals(0x24304A, ink.getRGB() & 0xFFFFFF);
        assertEquals(0x4A, ink.getBlue());
        assertArrayEquals(new Color(0x24304A).getRGBComponents(null), ink.getRGBComponents(null), 0.0001f);
        assertArrayEquals(new Color(0x24304A).getColorComponents(null), ink.getColorComponents(null), 0.0001f);
        assertTrue("equals stays value based", ink.equals(new Color(0x24304A)));
    }

    @Test
    public void derivedColorsAreDerivedLive() {
        ThemeColor dim = ThemeColor.withAlpha(ThemeColor.of("comic.ink"), 100);
        ThemeColor wash = ThemeColor.mix(ThemeColor.of("comic.navigationBlue"), ThemeColor.of("comic.fieldBg"), 0.5f);
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(new Color(0x25, 0x25, 0x25, 100), new Color(dim.getRGB(), true));
        int classicWash = wash.getRGB();
        AskAiTheme.setActive(darkTheme());
        assertEquals(new Color(0x24, 0x30, 0x4A, 100), new Color(dim.getRGB(), true));
        assertTrue(classicWash != wash.getRGB());
    }

    @Test
    public void averageTruncatesLikeTheHistoricBlendsAndDeriveIsLive() {
        AskAiTheme.setActive(AskAiTheme.classic());
        ThemeColor average = ThemeColor.average(ThemeColor.of("comic.accentYellow"), ThemeColor.of("comic.buttonPlate"));
        // (0xF2 + 0xFF) / 2 = 0xF8, (0xC9 + 0xFF) / 2 = 0xE4, (0x4C + 0xFF) / 2 = 0xA5 — truncated
        assertEquals(0xF8E4A5, average.getRGB() & 0xFFFFFF);
        ThemeColor brighter = ThemeColor.derive("ink.brighter", new java.util.function.Supplier<Color>() {
            @Override
            public Color get() {
                return ThemeColor.of("comic.ink").brighter();
            }
        });
        assertEquals(new Color(0x252525).brighter(), new Color(brighter.getRGB()));
        AskAiTheme.setActive(darkTheme());
        assertEquals(new Color(0x24304A).brighter(), new Color(brighter.getRGB()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownKeyIsRejectedImmediately() {
        ThemeColor.of("comic.doesNotExist");
    }

    @Test
    public void researchPaletteConstantsAreLiving() {
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(0x0C1117, ResearchUiPalette.DARK_BASE.getRGB() & 0xFFFFFF);
        AskAiTheme.setActive(darkTheme());
        assertEquals(0x0B1220, ResearchUiPalette.DARK_BASE.getRGB() & 0xFFFFFF);
        assertEquals(0x24304A, ComicPalette.defaultPalette().getInk().getRGB() & 0xFFFFFF);
    }

    @Test
    public void aPanelRepaintsInTheNewThemeAfterTheSwitch() {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(ResearchUiPalette.DARK_BASE);
        panel.setSize(10, 10);

        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(0x0C1117, paintedPixel(panel));
        AskAiTheme.setActive(darkTheme());
        assertEquals(0x0B1220, paintedPixel(panel));
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
