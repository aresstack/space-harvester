package com.aresstack.writing4j.manuscript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One immutable revision of a manuscript section (plan PR 26): text, the citation map from each local marker to
 * its source, gaps, who wrote it, model label, thinking level, prompt hash, evidence ids, the fingerprints of the
 * outline entry and of its chapter entry it was written from, and the time. The store assigns number and time;
 * callers build the content with {@link #builder()}.
 */
public final class SectionRevision {

    public static final String AUTHOR_USER = "user";
    public static final String AUTHOR_WRITER = "writer";

    private final int number;
    private final String text;
    private final List<ManuscriptCitation> citations;
    private final List<String> gaps;
    private final List<String> claimFlags;
    private final String author;
    private final String modelLabel;
    private final String thinkingLevel;
    private final String promptHash;
    private final List<String> evidenceIds;
    private final String sectionFingerprint;
    private final String chapterFingerprint;
    private final int restoredFrom;
    private final long createdAt;
    private final long evidenceAt;
    private final int wordTarget;

    private SectionRevision(Builder b, int number, long createdAt) {
        this.number = number;
        this.text = b.text == null ? "" : b.text;
        this.citations = Collections.unmodifiableList(new ArrayList<ManuscriptCitation>(b.citations));
        this.gaps = Collections.unmodifiableList(new ArrayList<String>(b.gaps));
        this.claimFlags = Collections.unmodifiableList(new ArrayList<String>(b.claimFlags));
        this.author = b.author == null || b.author.isEmpty() ? AUTHOR_USER : b.author;
        this.modelLabel = b.modelLabel == null ? "" : b.modelLabel;
        this.thinkingLevel = b.thinkingLevel == null ? "" : b.thinkingLevel;
        this.promptHash = b.promptHash == null ? "" : b.promptHash;
        this.evidenceIds = Collections.unmodifiableList(new ArrayList<String>(b.evidenceIds));
        this.sectionFingerprint = b.sectionFingerprint == null ? "" : b.sectionFingerprint;
        this.chapterFingerprint = b.chapterFingerprint == null ? "" : b.chapterFingerprint;
        this.restoredFrom = b.restoredFrom;
        this.createdAt = createdAt;
        this.evidenceAt = b.evidenceAt > 0 ? b.evidenceAt : createdAt;
        this.wordTarget = Math.max(0, b.wordTarget);
        Set<String> markers = new HashSet<String>();
        for (ManuscriptCitation citation : citations) {
            if (citation == null) {
                throw new IllegalArgumentException("A citation map entry is missing.");
            }
            if (!markers.add(citation.getMarker())) {
                throw new IllegalArgumentException("Citation marker [" + citation.getMarker() + "] occurs twice.");
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this revision's content (number and time are assigned again). */
    public Builder toBuilder() {
        Builder b = new Builder().text(text).citations(citations).gaps(gaps).claimFlags(claimFlags).author(author)
                .modelLabel(modelLabel).thinkingLevel(thinkingLevel).promptHash(promptHash)
                .evidenceIds(evidenceIds).sectionFingerprint(sectionFingerprint)
                .chapterFingerprint(chapterFingerprint);
        b.restoredFrom = restoredFrom;
        b.evidenceAt = evidenceAt;
        b.wordTarget = wordTarget;
        return b;
    }

    public int getNumber() {
        return number;
    }

    public String getText() {
        return text;
    }

    public List<ManuscriptCitation> getCitations() {
        return citations;
    }

    public List<String> getGaps() {
        return gaps;
    }

    /**
     * Section-level claim-check flags of PR 28 ("label: detail", e.g. "unsupported number: 15 %"), including
     * uncited sentences and paragraphs that have no citation entry.
     */
    public List<String> getClaimFlags() {
        return claimFlags;
    }

    public String getAuthor() {
        return author;
    }

    public String getModelLabel() {
        return modelLabel;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public List<String> getEvidenceIds() {
        return evidenceIds;
    }

    public String getSectionFingerprint() {
        return sectionFingerprint;
    }

    public String getChapterFingerprint() {
        return chapterFingerprint;
    }

    /** The revision number this one restored, 0 when it is not a restore. */
    public int getRestoredFrom() {
        return restoredFrom;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * When the evidence this text was written from was taken: the writer builds its evidence pack before the model
     * call, and sources accepted during the call are not in it. The creation time for a text without that record.
     */
    public long getEvidenceAt() {
        return evidenceAt;
    }

    /**
     * The word target the writer wrote this text to (live run 5: the target follows the actual evidence pack);
     * 0 when the text was not written to a target of its own (user text, older revisions).
     */
    public int getWordTarget() {
        return wordTarget;
    }

    /** The target the book report measures this text by: the writer's, else the outline's. */
    public int wordTarget(int outlineTarget) {
        return wordTarget > 0 ? wordTarget : outlineTarget;
    }

    /** SHA-256 of the text, for the manuscript pin. */
    public String textHash() {
        return ManuscriptCitation.sha256(text);
    }

    /** The content of a new revision. */
    public static final class Builder {
        private String text = "";
        private final List<ManuscriptCitation> citations = new ArrayList<ManuscriptCitation>();
        private final List<String> gaps = new ArrayList<String>();
        private final List<String> claimFlags = new ArrayList<String>();
        private String author = AUTHOR_USER;
        private String modelLabel = "";
        private String thinkingLevel = "";
        private String promptHash = "";
        private final List<String> evidenceIds = new ArrayList<String>();
        private String sectionFingerprint = "";
        private String chapterFingerprint = "";
        private int restoredFrom;
        private long evidenceAt;
        private int wordTarget;

        private Builder() {
        }

        public Builder text(String value) {
            text = value;
            return this;
        }

        public Builder citations(List<ManuscriptCitation> values) {
            citations.clear();
            if (values != null) {
                citations.addAll(values);
            }
            return this;
        }

        public Builder gaps(List<String> values) {
            gaps.clear();
            if (values != null) {
                gaps.addAll(values);
            }
            return this;
        }

        public Builder claimFlags(List<String> values) {
            claimFlags.clear();
            if (values != null) {
                claimFlags.addAll(values);
            }
            return this;
        }

        public Builder author(String value) {
            author = value;
            return this;
        }

        public Builder modelLabel(String value) {
            modelLabel = value;
            return this;
        }

        public Builder thinkingLevel(String value) {
            thinkingLevel = value;
            return this;
        }

        public Builder promptHash(String value) {
            promptHash = value;
            return this;
        }

        public Builder evidenceIds(List<String> values) {
            evidenceIds.clear();
            if (values != null) {
                evidenceIds.addAll(values);
            }
            return this;
        }

        public Builder sectionFingerprint(String value) {
            sectionFingerprint = value;
            return this;
        }

        public Builder chapterFingerprint(String value) {
            chapterFingerprint = value;
            return this;
        }

        /** The revision number this content restores (0 for none). */
        public Builder restoredFromNumber(int value) {
            restoredFrom = value;
            return this;
        }

        /** Store use: the revision with its number and time. */
        /** When the evidence of the text was taken (before the writer's model call); 0 = the creation time. */
        public Builder evidenceAt(long value) {
            evidenceAt = value;
            return this;
        }

        /** The word target the writer wrote to (0 = none of its own). */
        public Builder wordTarget(int value) {
            wordTarget = value;
            return this;
        }

        public SectionRevision build(int number, long createdAt) {
            return new SectionRevision(this, number, createdAt);
        }
    }
}
