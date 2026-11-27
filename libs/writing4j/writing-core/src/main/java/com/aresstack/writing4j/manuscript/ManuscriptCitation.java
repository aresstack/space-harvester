package com.aresstack.writing4j.manuscript;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One entry of a section revision's citation map (plan PR 26, D8): the local marker in the section text, the
 * source id, the source checksum the writer saw, the cited text with its hash, the passage id as a derived link
 * and the claim-check flags (filled by PR 28). A citation without a checksum or cited text is refused.
 */
public final class ManuscriptCitation {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** A run of whitespace in Unicode's or Java's sense, a no-break space too. */
    private static final Pattern WHITESPACE = Pattern.compile("(?U)[\\s\\p{javaWhitespace}]+");

    private final String marker;
    private final String sourceId;
    private final String sourceChecksum;
    private final String citedText;
    private final String citedTextHash;
    private final String passageId;
    private final List<String> flags;

    public ManuscriptCitation(String marker, String sourceId, String sourceChecksum, String citedText,
                              String passageId, List<String> flags) {
        this.marker = require(marker, "a local marker");
        this.sourceId = require(sourceId, "a source id");
        this.sourceChecksum = require(sourceChecksum, "the source checksum");
        if (citedText == null || citedText.trim().isEmpty()) {
            throw new IllegalArgumentException("Citation [" + this.marker + "] lacks the cited text.");
        }
        this.citedText = citedText;
        this.citedTextHash = sha256(citedText);
        this.passageId = passageId == null ? "" : passageId.trim();
        this.flags = Collections.unmodifiableList(new ArrayList<String>(
                flags == null ? Collections.<String>emptyList() : flags));
    }

    private String require(String value, String what) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Citation " + (marker == null ? "" : "[" + marker + "] ")
                    + "lacks " + what + ".");
        }
        return value.trim();
    }

    public String getMarker() {
        return marker;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public String getCitedText() {
        return citedText;
    }

    /** SHA-256 of the cited text, so the citation can be checked against the text the writer saw. */
    public String getCitedTextHash() {
        return citedTextHash;
    }

    /** The passage id; a derived link that may be re-resolved after a re-segmentation ("" when unknown). */
    public String getPassageId() {
        return passageId;
    }

    public List<String> getFlags() {
        return flags;
    }

    /**
     * True when the cited text occurs in {@code sourceText}, every whitespace run on both sides read as one space.
     * A passage is its sentences joined by one space, and the sentence segmenter reads a no-break space between two
     * sentences as whitespace (live run 4: a no-break space after "Gefahren." in the source, a plain space in the
     * passage, four "[?]" in the book).
     */
    public boolean isFoundIn(String sourceText) {
        return collapse(sourceText).contains(collapse(citedText));
    }

    /** Every whitespace run as one space, trimmed. */
    static String collapse(String text) {
        return text == null ? "" : WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ManuscriptCitation)) {
            return false;
        }
        ManuscriptCitation that = (ManuscriptCitation) other;
        return marker.equals(that.marker) && sourceId.equals(that.sourceId)
                && sourceChecksum.equals(that.sourceChecksum) && citedText.equals(that.citedText)
                && passageId.equals(that.passageId) && flags.equals(that.flags);
    }

    @Override
    public int hashCode() {
        return ((marker.hashCode() * 31 + sourceId.hashCode()) * 31 + citedTextHash.hashCode()) * 31
                + passageId.hashCode();
    }
}
