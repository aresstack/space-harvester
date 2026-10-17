package com.aresstack.spaceharvester.nlp.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ein Satz: Wörter in Textreihenfolge mit IDs {@code 1..n}, Mehrworttokens und seine Spanne im Dokumenttext.
 *
 * @param index           Position im Dokument ab 0
 * @param start           Zeichenposition im Dokumenttext (inklusive)
 * @param end             Zeichenposition im Dokumenttext (exklusive)
 * @param text            Satztext im Wortlaut
 * @param tokens          Wörter, {@code tokens.get(i).id() == i + 1}
 * @param multiwordTokens Mehrworttokens
 */
public record Sentence(int index, int start, int end, String text, List<Token> tokens,
                       List<MultiwordToken> multiwordTokens) {

    public Sentence {
        Objects.requireNonNull(text, "text");
        tokens = List.copyOf(tokens);
        multiwordTokens = multiwordTokens == null ? List.of() : List.copyOf(multiwordTokens);
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).id() != i + 1) {
                throw new IllegalArgumentException("Token-IDs müssen 1..n lückenlos sein, gefunden "
                        + tokens.get(i).id() + " an Stelle " + (i + 1));
            }
        }
    }

    /** Token mit {@code id} (ab 1). */
    public Token token(int id) {
        return tokens.get(id - 1);
    }

    /** Kopf von {@code token} oder {@code null} bei der Wurzel und vor dem Parsen. */
    public Token head(Token token) {
        return token.head() > 0 ? token(token.head()) : null;
    }

    /** Direkt abhängige Tokens von {@code headId} in Textreihenfolge. */
    public List<Token> dependents(int headId) {
        List<Token> result = new ArrayList<>();
        for (Token token : tokens) {
            if (token.head() == headId) {
                result.add(token);
            }
        }
        return result;
    }

    /** Direkt abhängige Tokens mit genau der Relation {@code deprel} oder, ohne Doppelpunkt, mit dieser Hauptrelation. */
    public List<Token> dependents(int headId, String deprel) {
        List<Token> result = new ArrayList<>();
        for (Token token : dependents(headId)) {
            if (deprel.equals(token.deprel()) || deprel.indexOf(':') < 0 && deprel.equals(token.baseRelation())) {
                result.add(token);
            }
        }
        return result;
    }

    /** Das erste abhängige Token mit genau der Relation {@code deprel} oder {@code null}. */
    public Token firstDependent(int headId, String deprel) {
        for (Token token : dependents(headId)) {
            if (deprel.equals(token.deprel())) {
                return token;
            }
        }
        return null;
    }

    /** Mehrworttoken, zu dem {@code tokenId} gehört, oder {@code null}. */
    public MultiwordToken multiwordToken(int tokenId) {
        for (MultiwordToken multiword : multiwordTokens) {
            if (multiword.covers(tokenId)) {
                return multiword;
            }
        }
        return null;
    }

    public boolean parsed() {
        return !tokens.isEmpty() && tokens.stream().allMatch(Token::parsed);
    }

    /** Derselbe Satz mit anderen Tokens (etwa nach dem Parsen). */
    public Sentence withTokens(List<Token> newTokens) {
        return new Sentence(index, start, end, text, newTokens, multiwordTokens);
    }
}
