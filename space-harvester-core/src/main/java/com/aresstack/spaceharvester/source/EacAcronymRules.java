package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymExtractor;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymFinding;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymForms;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymSettings;
import com.aresstack.enterpriseai.astu.acropolis.textmining.TermKind;
import com.aresstack.enterpriseai.astu.acropolis.textmining.TextSpan;
import com.aresstack.spaceharvester.application.enrichment.AcronymRules;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AcronymRules} mit dem {@code AcronymExtractor} des Enterprise AI Client (unverändert übernommen,
 * {@code enterprise-ai-sources}) und dessen Standardschwellen ({@code AcronymSettings.defaults()}).
 */
public final class EacAcronymRules implements AcronymRules {

    private final AcronymExtractor extractor;

    private EacAcronymRules(com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter sentences) {
        this.extractor = new AcronymExtractor(sentences);
    }

    /** Mit dem regelbasierten Satz-Splitter des EAC ({@code SentenceSegmenter.splitter()}), ohne Satzmodell. */
    public static AcronymRules splitter() {
        return new EacAcronymRules(com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter.splitter());
    }

    /**
     * @param sentences Satzgrenzen für die Ausschreibungen, produktiv wie {@code passages} das OpenNLP-Satzmodell mit
     *                  dem Splitter als Rückfall, wie im EAC
     */
    public static AcronymRules with(SentenceSegmenter sentences) {
        Objects.requireNonNull(sentences, "sentences");
        return new EacAcronymRules(new com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                return sentences.sentences(paragraph);
            }

            @Override
            public String id() {
                return sentences.id();
            }
        });
    }

    @Override
    public List<Finding> analyze(String text) {
        List<Finding> findings = new ArrayList<>();
        for (AcronymFinding finding : extractor.analyze(text, AcronymSettings.defaults()).findings()) {
            boolean acronym = finding.kind() == TermKind.ACRONYM;
            List<Occurrence> occurrences = new ArrayList<>();
            for (TextSpan span : finding.occurrences()) {
                String surface = span.of(text);
                occurrences.add(new Occurrence(span.start(), span.end(),
                        acronym ? AcronymForms.withoutPlural(surface) : surface));
            }
            findings.add(new Finding(finding.shortForm(), finding.normalized(), acronym,
                    acronym ? finding.longForms() : List.of(), occurrences));
        }
        return findings;
    }
}
