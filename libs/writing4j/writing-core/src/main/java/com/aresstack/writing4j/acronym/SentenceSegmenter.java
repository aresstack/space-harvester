package com.aresstack.writing4j.acronym;

import com.aresstack.knowledge4j.text.SentenceSpan;
import java.util.ArrayList;

import com.aresstack.writing4j.acronym.RegexSentenceSegmenter;
import com.aresstack.knowledge4j.text.SentenceSegmentationPort;

import java.util.List;

/**
 * The sentences the {@link AcronymExtractor} aligns long forms in. A small adapter onto the host's
 * {@link SentenceSegmentationPort} (OpenNLP when the language model is installed, else the regex segmenter); the
 * extractor locates each returned sentence in the paragraph again, so the findings keep character spans of the
 * source text. {@link #id()} is the port's fingerprint and goes into stored findings.
 */
public interface SentenceSegmenter {

    /** The sentences of one paragraph in reading order (never {@code null}). */
    List<String> sentences(String paragraph);

    /** Fingerprint of the procedure, e.g. {@code regex} or {@code opennlp-<model checksum>}. */
    String id();

    /** Adapter onto the host's segmentation port, for text in {@code language} (IETF tag, empty when unknown). */
    static SentenceSegmenter of(final SentenceSegmentationPort port, final String language) {
        if (port == null) {
            throw new IllegalArgumentException("port must not be null");
        }
        return new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                List<String> out = new ArrayList<String>();
                for (SentenceSpan span : port.segment(paragraph, language == null ? "" : language)) {
                    String sentence = span.of(paragraph).trim();
                    if (!sentence.isEmpty()) {
                        out.add(sentence);
                    }
                }
                return out;
            }

            @Override
            public String id() {
                return port.getClass().getName();
            }
        };
    }

    /** The deterministic regex segmenter of the pipeline (no model). */
    static SentenceSegmenter splitter() {
        final RegexSentenceSegmenter regex = new RegexSentenceSegmenter();
        return new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                return regex.segment(paragraph);
            }

            @Override
            public String id() {
                return regex.fingerprint();
            }
        };
    }
}
