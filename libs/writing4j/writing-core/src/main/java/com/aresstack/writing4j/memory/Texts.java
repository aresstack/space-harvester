package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small shared helpers of the research memory. */
final class Texts {

    private Texts() {
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Trimmed, non-empty, each once, in order; unmodifiable. */
    static List<String> ids(List<String> values) {
        Set<String> kept = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                String id = clean(value);
                if (!id.isEmpty()) {
                    kept.add(id);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(kept));
    }

    /** Lower case, everything but letters and digits folded to one blank. */
    static String wording(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
