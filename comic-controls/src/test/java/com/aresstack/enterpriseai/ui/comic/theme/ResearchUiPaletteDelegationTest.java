package com.aresstack.enterpriseai.ui.comic.theme;

import org.junit.Test;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Every {@link ResearchUiPalette} constant is filled from the active theme's research tokens. */
public class ResearchUiPaletteDelegationTest {

    @Test
    public void constantsEqualTheActiveThemeTokens() throws Exception {
        AskAiTheme active = AskAiTheme.active();
        int checked = 0;
        for (Field field : ResearchUiPalette.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (field.getType() == Color.class && Modifier.isPublic(mod) && Modifier.isStatic(mod)) {
                assertEquals(field.getName(), active.research(field.getName()), field.get(null));
                checked++;
            }
        }
        assertEquals(active.researchTokens().size(), checked);
    }

    @Test
    public void comicDefaultPaletteIsTheActiveThemePalette() {
        ComicPalette live = ComicPalette.defaultPalette();
        ComicPalette active = AskAiTheme.active().comicPalette();
        assertEquals(active.getSurface(), live.getSurface());
        assertEquals(active.getInk(), live.getInk());
        assertEquals(active.getNavigationBlue(), live.getNavigationBlue());
        assertEquals(active.getAccentYellow(), live.getAccentYellow());
        assertEquals(active.getAccentRed(), live.getAccentRed());
    }

    @Test
    public void everyTokenHasAConstant() throws Exception {
        for (Map.Entry<String, Color> e : AskAiTheme.active().researchTokens().entrySet()) {
            assertEquals(e.getValue(), ResearchUiPalette.class.getField(e.getKey()).get(null));
        }
    }
}
