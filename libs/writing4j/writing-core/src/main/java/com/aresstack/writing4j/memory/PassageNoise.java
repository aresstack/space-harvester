package com.aresstack.writing4j.memory;

/**
 * Which passages are page leftovers, as the page-noise check before the topic finder marks them: advertising, tables
 * of contents and indexes, literature lists, captions, navigation, bare headings, descriptions of the site itself,
 * imprint and contact data, site notices. A mark is kept by text key ({@link PassageText#key}), so the same text
 * counts the same on every page. The topic finder (13), the evidence judge (6), the sentence check (11) and the
 * research memory (7) skip such passages; nothing is deleted.
 */
public interface PassageNoise {

    /** No passage is a leftover: before the check ran, and where it does not apply. */
    PassageNoise NONE = textKey -> false;

    /** Whether the passage with this text key is a page leftover. */
    boolean isNoise(String textKey);
}
