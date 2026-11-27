package com.aresstack.writing4j.missionreport;

import com.aresstack.writing4j.manuscript.ManuscriptCitation;
import com.aresstack.writing4j.manuscript.SectionRevision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One section of the book as the mission report reads it: its id and title, the words it was written for, its text
 * with the markers {@code [n]} and what each marker cites.
 */
public final class BookSection {

    /** What a marker {@code [n]} of the section's text cites. */
    public static final class Citation {
        private final int marker;
        private final String passageId;
        private final String sourceId;
        private final String passageText;

        public Citation(int marker, String passageId, String sourceId, String passageText) {
            if (marker < 1) {
                throw new IllegalArgumentException("a citation needs a marker from 1 up, not " + marker);
            }
            this.marker = marker;
            this.passageId = clean(passageId);
            this.sourceId = clean(sourceId);
            this.passageText = passageText == null ? "" : passageText;
        }

        public int getMarker() {
            return marker;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        /** The passage the writer cited, as it read it. */
        public String getPassageText() {
            return passageText;
        }
    }

    private final String sectionId;
    private final String title;
    private final int targetWords;
    private final String text;
    private final Map<Integer, Citation> citations;

    /**
     * @param targetWords the words the section was written for; 0 when none was set
     * @param citations   what the markers cite; a marker given twice keeps its first citation
     */
    public BookSection(String sectionId, String title, int targetWords, String text, List<Citation> citations) {
        this.sectionId = clean(sectionId);
        if (this.sectionId.isEmpty()) {
            throw new IllegalArgumentException("a section needs an id");
        }
        this.title = clean(title);
        this.targetWords = Math.max(0, targetWords);
        this.text = text == null ? "" : text;
        Map<Integer, Citation> byMarker = new LinkedHashMap<Integer, Citation>();
        for (Citation citation : citations == null ? Collections.<Citation>emptyList() : citations) {
            if (citation != null && !byMarker.containsKey(citation.getMarker())) {
                byMarker.put(citation.getMarker(), citation);
            }
        }
        this.citations = Collections.unmodifiableMap(byMarker);
    }

    /**
     * A section of the manuscript: the text and citations of its revision, the words of the revision or else of the
     * outline. A citation whose marker is no number is left out (the report counts its marker as unknown).
     */
    public static BookSection of(String sectionId, String title, SectionRevision revision, int outlineTarget) {
        List<Citation> list = new ArrayList<Citation>();
        for (ManuscriptCitation citation : revision.getCitations()) {
            int marker;
            try {
                marker = Integer.parseInt(citation.getMarker().trim());
            } catch (NumberFormatException notANumber) {
                continue;
            }
            if (marker >= 1) {
                list.add(new Citation(marker, citation.getPassageId(), citation.getSourceId(),
                        citation.getCitedText()));
            }
        }
        return new BookSection(sectionId, title, revision.wordTarget(outlineTarget), revision.getText(), list);
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public int getTargetWords() {
        return targetWords;
    }

    public String getText() {
        return text;
    }

    /** The citation of a marker, or {@code null} when the section has none for it. */
    public Citation citation(int marker) {
        return citations.get(marker);
    }

    public Map<Integer, Citation> getCitations() {
        return citations;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
