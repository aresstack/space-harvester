package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.MultiwordToken;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;

/** Präposition einer Nominalgruppe im Wortlaut des Texts: bei Verschmelzungen die Originalform ({@code am}). */
final class Markers {

    private Markers() {
    }

    static String of(Sentence sentence, Token head) {
        Token preposition = sentence.firstDependent(head.id(), "case");
        if (preposition == null) {
            return null;
        }
        MultiwordToken multiword = sentence.multiwordToken(preposition.id());
        return multiword != null ? multiword.form() : preposition.form();
    }
}
