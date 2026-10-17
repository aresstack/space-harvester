package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.List;

/**
 * Ein Satz, eine Überschrift, ein Listenpunkt oder eine Tabellenzelle des projizierten Texts.
 *
 * @param tokens     Wörter und Satzzeichen in Textreihenfolge
 * @param nounGroups Nominalgruppen in Textreihenfolge, ohne Überlappung
 */
record Sentence(List<Token> tokens, List<NounGroup> nounGroups) {

    Sentence {
        tokens = List.copyOf(tokens);
        nounGroups = List.copyOf(nounGroups);
    }

    int start() {
        return tokens.get(0).start();
    }

    int end() {
        return tokens.get(tokens.size() - 1).end();
    }

    /** Die Nominalgruppe, die bei Token {@code index} beginnt, oder {@code null}. */
    NounGroup groupAt(int index) {
        for (NounGroup group : nounGroups) {
            if (group.from() == index) {
                return group;
            }
        }
        return null;
    }
}
