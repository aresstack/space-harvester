package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearchLanguagesTest {

    @Test
    public void codesAreNormalised() {
        assertEquals("de", SearchLanguages.normalize(" DE "));
        assertEquals("zh-CN", SearchLanguages.normalize("zh_cn"));
        assertEquals("zh-TW", SearchLanguages.normalize("ZH-tw"));
        assertEquals("zh-Hant", SearchLanguages.normalize("zh-HANT"));
        assertEquals("es-419", SearchLanguages.normalize("es-419"));
        assertEquals("fil", SearchLanguages.normalize("fil"));
    }

    @Test
    public void malformedCodesAreRejected() {
        assertNull(SearchLanguages.normalize(""));
        assertNull(SearchLanguages.normalize("german"));
        assertNull(SearchLanguages.normalize("d"));
        assertNull(SearchLanguages.normalize("de-DE-x"));
        assertNull(SearchLanguages.normalize("1a"));
    }

    @Test
    public void freeTextIsSplitDeduplicatedAndReportsWhatItRejects() {
        SearchLanguages.Parsed parsed = SearchLanguages.parse("pl, uk; id  PL | polish");
        assertEquals(Arrays.asList("pl", "uk", "id"), parsed.getCodes());
        assertEquals(Arrays.asList("polish"), parsed.getRejected());
        assertTrue(SearchLanguages.parse("  ").getCodes().isEmpty());
    }

    @Test
    public void thePickerOffersTheFourteenAgreedLanguages() {
        assertEquals(Arrays.asList("de", "en", "fr", "es", "it", "ru", "zh-CN", "zh-TW", "ja", "ko", "cs", "sk",
                "vi", "tr"), SearchLanguages.pickerCodes());
        assertTrue(SearchLanguages.isPickerLanguage("ZH_cn"));
        assertFalse(SearchLanguages.isPickerLanguage("pl"));
        assertEquals("Chinese (simplified)", SearchLanguages.displayName("zh-CN"));
        assertEquals("pl", SearchLanguages.displayName("pl"));
        assertEquals("de, en", SearchLanguages.join(Arrays.asList("de", "en")));
    }
}
