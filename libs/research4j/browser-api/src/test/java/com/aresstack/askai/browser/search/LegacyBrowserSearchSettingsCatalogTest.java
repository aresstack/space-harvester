package com.aresstack.askai.browser.search;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The UI catalog and the codec must describe EXACTLY the same fields — no drift in either direction. */
public class LegacyBrowserSearchSettingsCatalogTest {

    @Test
    public void catalogCoversExactlyTheCodecKeys() {
        Map<String, String> codecKeys =
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create());
        Set<String> catalogKeys = new HashSet<String>();
        for (LegacyBrowserSearchSettingsCatalog.Field field
                : LegacyBrowserSearchSettingsCatalog.fields()) {
            assertTrue("duplicate catalog key: " + field.key, catalogKeys.add(field.key));
            assertTrue("catalog key unknown to the codec: " + field.key,
                    codecKeys.containsKey(field.key));
            assertTrue("field without description: " + field.key,
                    field.description != null && !field.description.trim().isEmpty());
            assertTrue("field without section: " + field.key,
                    LegacyBrowserSearchSettingsCatalog.sections().contains(field.section));
        }
        assertEquals("codec keys missing in the catalog: " + missing(codecKeys.keySet(), catalogKeys),
                codecKeys.size(), catalogKeys.size());
    }

    private static String missing(Set<String> all, Set<String> present) {
        StringBuilder sb = new StringBuilder();
        for (String key : all) {
            if (!present.contains(key)) {
                sb.append(key).append(' ');
            }
        }
        return sb.toString();
    }
}
