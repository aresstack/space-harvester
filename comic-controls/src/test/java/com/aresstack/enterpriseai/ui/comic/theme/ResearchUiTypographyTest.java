package com.aresstack.enterpriseai.ui.comic.theme;

import org.junit.Test;

import java.awt.Font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** One font source: Regular and Semi Bold at the requested size, with a sane fallback on every JVM. */
public class ResearchUiTypographyTest {

    @Test
    public void regularAndSemiBoldResolveToUsableFonts() {
        Font regular = ResearchUiTypography.regular(12.5f);
        Font semiBold = ResearchUiTypography.semiBold(13f);
        assertNotNull(regular);
        assertNotNull(semiBold);
        assertEquals(12.5f, regular.getSize2D(), 0.001f);
        assertEquals(13f, semiBold.getSize2D(), 0.001f);
        assertTrue("semi bold is a real semi-bold family or the bold fallback",
                semiBold.isBold() || semiBold.getFamily().toLowerCase().contains("semi"));
        assertNotNull(ResearchUiTypography.familiesForTest());
    }
}
