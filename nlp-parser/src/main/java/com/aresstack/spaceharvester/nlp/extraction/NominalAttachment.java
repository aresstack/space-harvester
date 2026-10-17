package com.aresstack.spaceharvester.nlp.extraction;

import java.util.Objects;

/**
 * Ein nominaler Bezug: eine Erwähnung hängt an einer anderen Nominalgruppe ({@code der Beleg des Kunden}). Er sagt
 * nur, dass der Text die Gruppen verbindet, nicht wie: ein Genitiv ist kein Besitz, ein {@code von} keine Herkunft.
 *
 * @param id        {@code <Erwähnung des Kopfs>→t<Kopf der Ergänzung>}
 * @param head      Erwähnung, an der die Ergänzung hängt
 * @param dependent Erwähnung der Ergänzung
 * @param relation  UD-Relation ({@code nmod}, {@code nmod:poss})
 * @param marker    Präposition im Wortlaut oder {@code null}
 * @param kind      Art des Bezugs nach Form
 * @param basis     {@link Basis#PARSER} oder bei Kasus aus Artikelformen {@link Basis#CASE_HEURISTIC}
 */
public record NominalAttachment(String id, String head, String dependent, String relation, String marker, Kind kind,
                                Basis basis) {

    public NominalAttachment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(dependent, "dependent");
    }

    public enum Kind {
        /** Genitivattribut ohne Präposition ({@code des Kunden}). */
        GENITIVE,
        /** Präpositionalattribut ({@code der Antrag von Frau Meier}). */
        PREPOSITIONAL,
        /** Andere nominale Ergänzung, Kasus unbekannt. */
        OTHER
    }
}
