package com.aresstack.writing4j.acronym;

import com.aresstack.knowledge4j.text.SentenceSegmentationPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic default segmentation: terminal punctuation followed by whitespace and an upper-case or
 * digit start. Good enough for tests and as fallback; production quality comes from the OpenNLP adapter
 * behind the same port.
 */
public final class RegexSentenceSegmenter {

    /** Fingerprint of this segmenter in stored findings. */
    public static final String FINGERPRINT = "regex";

    public String fingerprint() {
        return FINGERPRINT;
    }

    public List<String> segment(String text) {
        List<String> sentences = new ArrayList<String>();
        if (text == null) {
            return sentences;
        }
        String[] parts = text.split("(?<=[.!?])\\s+(?=[A-Z0-9ÄÖÜ])");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }
}
