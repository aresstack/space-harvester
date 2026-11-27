package com.aresstack.knowledge4j.text;

import java.util.List;

/** Splits text into sentences. Implementations (rules, OpenNLP) live outside Knowledge4j. */
public interface SentenceSegmentationPort {

    /**
     * @param language IETF language tag of the text, empty when unknown
     * @return the sentences in text order; empty for blank text
     */
    List<SentenceSpan> segment(String text, String language);
}
