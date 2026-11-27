package com.aresstack.writing4j.planwriter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where the writer (component 10) finds the text of a passage the section plan names: the plan holds passage ids, the
 * text stays in the stock. With the text comes what the source rating (component 16) knows of the passage's source,
 * so the writer attributes the claims of a maker, a seller or an association and marks a rule of another legal space.
 */
public interface PassageLookup {

    /** A passage with its text and what is known of its source; every field {@code ""} when unknown. */
    final class Passage {
        private final String passageId;
        private final String sourceId;
        private final String sourceUrl;
        private final String text;
        private final String sourceKind;
        private final String legalSpace;

        /**
         * @param sourceKind the kind of the source (component 16, e.g. {@code HERSTELLER_HANDEL}, {@code VERBAND});
         *                   {@code ""} when unknown
         * @param legalSpace the legal space of the source (component 16: DE, AT, CH, EU, ANDERE, OHNE); {@code ""}
         *                   when unknown
         */
        public Passage(String passageId, String sourceId, String sourceUrl, String text, String sourceKind,
                       String legalSpace) {
            this.passageId = clean(passageId);
            this.sourceId = clean(sourceId);
            this.sourceUrl = clean(sourceUrl);
            this.text = text == null ? "" : text;
            this.sourceKind = clean(sourceKind).toUpperCase(Locale.ROOT);
            this.legalSpace = clean(legalSpace).toUpperCase(Locale.ROOT);
            if (this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a passage needs an id");
            }
        }

        /** A passage without anything known of its source. */
        public Passage(String passageId, String sourceId, String text) {
            this(passageId, sourceId, "", text, "", "");
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getText() {
            return text;
        }

        public String getSourceKind() {
            return sourceKind;
        }

        public String getLegalSpace() {
            return legalSpace;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** The passage with this id, {@code null} when the stock has none. */
    Passage find(String passageId);

    /** A lookup over passages held in memory (tests and the stock test); a later passage with the same id wins. */
    static PassageLookup of(Collection<Passage> passages) {
        Map<String, Passage> byId = new LinkedHashMap<String, Passage>();
        for (Passage passage : passages == null ? Collections.<Passage>emptyList() : passages) {
            byId.put(passage.getPassageId(), passage);
        }
        return byId::get;
    }
}
