package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationType;
import java.util.Objects;

/** Woher eine Kante stammt; je Grundlage ein eigener Typ, damit nichts verwechselt werden kann. */
public sealed interface Provenance {

    /** Die Grundlage dieser Herkunft. */
    Basis basis();

    /** Aus den Kopfdaten der Quelle: Feld Elternseite der Ausgangs- bzw. der Unterseite. */
    record Hierarchy() implements Provenance {

        @Override
        public Basis basis() {
            return Basis.EXPLICIT;
        }
    }

    /** Aus dem Verzeichnis der Verweise, also aus dem Inhalt einer der beiden Seiten. */
    record Link() implements Provenance {

        @Override
        public Basis basis() {
            return Basis.EXPLICIT;
        }
    }

    /**
     * Eine bestätigte Begriffsbeziehung.
     *
     * @param relationId     Kennung der Beziehung ({@code TermRelation#id()})
     * @param type           Art, auch {@link RelationType#RELATED}
     * @param fromTerm       Wortlaut des einen Endes, bei Abkürzungen die Abkürzung
     * @param toTerm         Wortlaut des anderen Endes
     * @param scopeSourceId  Geltungsbereich: Quelle oder {@code null} für global
     * @param scopeSpaceKey  Geltungsbereich: Space oder {@code null}
     * @param origin         wie die Beziehung entstand (von Hand, Regel, KI-Vorschlag); bestätigt ist sie in jedem Fall
     * @param seedTerm       Suchwert des Endes, das auf der Ausgangsseite vorkommt
     * @param targetTerm     Suchwert des Endes, das auf der Zielseite vorkommt
     */
    record TermLink(String relationId, RelationType type, String fromTerm, String toTerm, String scopeSourceId,
                    String scopeSpaceKey, RelationOrigin origin, String seedTerm, String targetTerm)
            implements Provenance {

        public TermLink {
            Objects.requireNonNull(relationId, "relationId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
        }

        @Override
        public Basis basis() {
            return Basis.CONFIRMED;
        }
    }

    /**
     * Ähnlichkeit zweier Abschnitte aus gespeicherten Vektoren; der ähnlichste Abschnittspaar je Zielseite.
     *
     * @param similarity    Kosinusähnlichkeit zwischen -1 und 1
     * @param seedChunk     Nummer des Stücks der Ausgangsseite, ab 0
     * @param seedSection   seine Überschriftenkette oder {@code null}
     * @param targetChunk   Nummer des Stücks der Zielseite, ab 0
     * @param targetSection seine Überschriftenkette oder {@code null}
     * @param model         Version des Embedding-Prozessors, aus der beide Vektoren stammen
     */
    record Similarity(double similarity, int seedChunk, String seedSection, int targetChunk, String targetSection,
                      String model) implements Provenance {

        public Similarity {
            Objects.requireNonNull(model, "model");
        }

        @Override
        public Basis basis() {
            return Basis.COMPUTED;
        }
    }
}
