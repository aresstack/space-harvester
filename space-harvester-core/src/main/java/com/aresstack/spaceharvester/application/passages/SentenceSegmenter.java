package com.aresstack.spaceharvester.application.passages;

import com.aresstack.enterpriseai.astu.knowledge.SentenceSplitter;
import java.util.List;

/**
 * Satzgrenzen für die Passagenbildung, wie {@code SentenceSegmenter} im Enterprise AI Client (askai arch
 * {@code SentenceSegmentationPort}): produktiv das OpenNLP-Satzmodell ({@link OpenNlpSentenceSegmenter}), sonst der
 * regelbasierte {@link SentenceSplitter}. {@link #id()} geht in die Prozessorversion ein: ein anderes Satzmodell ergibt
 * andere Passagen, die bisherigen veralten.
 */
public interface SentenceSegmenter {

    /** Die Sätze eines Absatzes in Lesereihenfolge, jeweils wörtlich aus ihm (nie {@code null}). */
    List<String> sentences(String paragraph);

    /** Kennung des Verfahrens, z. B. {@code opennlp-de} oder {@code splitter}. */
    String id();

    /** Der regelbasierte Splitter (ohne Modell). */
    static SentenceSegmenter splitter() {
        SentenceSplitter splitter = new SentenceSplitter();
        return new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                return splitter.split(paragraph);
            }

            @Override
            public String id() {
                return "splitter";
            }
        };
    }
}
