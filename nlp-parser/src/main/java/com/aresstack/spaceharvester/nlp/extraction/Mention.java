package com.aresstack.spaceharvester.nlp.extraction;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Eine konkrete Erwähnung: eine Nominalgruppe oder ein Pronomen an einer Textstelle. Zwei Erwähnungen mit gleichem
 * Wortlaut bleiben zwei Erwähnungen; die ID enthält Quelle, Version, Satz und Kopf-Token.
 *
 * @param id             {@code quelle/dokument@version#s<Satz>.t<Kopf>}
 * @param sentence       Satzindex ab 0
 * @param head           Kopf-Token
 * @param tokens         Tokens der Gruppe in Textreihenfolge (Artikel, Adjektive, Komposita, Namen)
 * @param start          Zeichenposition im Dokumenttext (inklusive)
 * @param end            Zeichenposition im Dokumenttext (exklusive)
 * @param text           Wortlaut der Gruppe im Originaltext
 * @param headForm       Form des Kopfs
 * @param lemma          Grundform des Kopfs, soweit geliefert, sonst {@code null}
 * @param kind           Art der Erwähnung
 * @param resolution     Bezug
 * @param cases          mögliche Kasus ({@code Nom}, {@code Acc}, {@code Dat}, {@code Gen}); leer = unbekannt
 * @param caseBasis      {@link Basis#PARSER} bei Kasus aus den Merkmalen, {@link Basis#CASE_HEURISTIC} bei
 *                       Schluss aus Artikel oder Pronomenform, {@code null} wenn unbekannt
 */
public record Mention(String id, int sentence, int head, List<Integer> tokens, int start, int end, String text,
                      String headForm, String lemma, Kind kind, Resolution resolution, Set<String> cases,
                      Basis caseBasis) {

    public Mention {
        Objects.requireNonNull(id, "id");
        tokens = List.copyOf(tokens);
        cases = Set.copyOf(cases);
    }

    /** Eindeutiger Kasus oder {@code null}. */
    public String uniqueCase() {
        return cases.size() == 1 ? cases.iterator().next() : null;
    }

    /** Schlüssel für Statistiken: Grundform, sonst Wortform des Kopfs. Nie eine Identität. */
    public String key() {
        return lemma != null ? lemma : headForm;
    }

    /** Art einer Erwähnung nach der Wortart des Kopfs. */
    public enum Kind {
        NOUN,
        PROPER_NOUN,
        PRONOUN,
        /** Andere nominal gebrauchte Wörter (Zahlen, substantivierte Adjektive). */
        OTHER
    }
}
