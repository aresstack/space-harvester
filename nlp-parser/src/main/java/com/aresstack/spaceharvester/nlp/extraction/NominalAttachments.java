package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * Nominale Ergänzungen ({@code nmod}, {@code nmod:poss}) an der Nominalgruppe, an der der Parser sie anhängt:
 * ohne Präposition und mit Genitiv → {@link NominalAttachment.Kind#GENITIVE}, mit Präposition →
 * {@link NominalAttachment.Kind#PREPOSITIONAL}. Es entsteht nur ein Bezug, keine Besitz- oder Herkunftsaussage.
 */
final class NominalAttachments {

    private NominalAttachments() {
    }

    static List<NominalAttachment> extract(ParsedDocument document, Mentions mentions) {
        List<NominalAttachment> attachments = new ArrayList<>();
        for (Sentence sentence : document.sentences()) {
            for (Token token : sentence.tokens()) {
                if (!"nmod".equals(token.baseRelation()) || sentence.head(token) == null) {
                    continue;
                }
                Mention head = mentions.at(document, sentence, sentence.head(token));
                Mention dependent = mentions.at(document, sentence, token);
                String marker = Markers.of(sentence, token);
                NominalAttachment.Kind kind;
                Basis basis = Basis.PARSER;
                if (marker != null) {
                    kind = NominalAttachment.Kind.PREPOSITIONAL;
                } else if (CaseHints.GEN.equals(dependent.uniqueCase())) {
                    kind = NominalAttachment.Kind.GENITIVE;
                    basis = dependent.caseBasis();
                } else {
                    kind = NominalAttachment.Kind.OTHER;
                }
                attachments.add(new NominalAttachment(head.id() + "→t" + token.id(), head.id(), dependent.id(),
                        token.deprel(), marker, kind, basis));
            }
        }
        return attachments;
    }
}
