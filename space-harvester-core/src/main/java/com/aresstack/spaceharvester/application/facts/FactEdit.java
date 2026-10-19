package com.aresstack.spaceharvester.application.facts;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein von einer Person angelegter oder geprüfter Fakt (#182), wie er in der Prüfablage liegt: ein manueller Fakt oder
 * die Kopie eines automatischen Fakts mit seinem Prüfstatus. Die Kopie überlebt die Neuberechnung ihres Ergebnisses mit
 * der alten Herkunft, wie geprüfte Fakten im Enterprise AI Client ({@code replaceAutomaticFacts}).
 *
 * @param fact       der Fakt mit Herkunft und Prüfstatus
 * @param revision   Änderungszähler ab 1 für optimistisches Sperren
 * @param createdBy  wer ihn angelegt oder zuerst geprüft hat ({@code AccessContext#jobOwner()})
 * @param reviewedBy wer zuletzt geprüft hat oder {@code null}
 */
public record FactEdit(MetadataFact fact, long revision, String createdBy, Instant createdAt, String reviewedBy,
                       Instant reviewedAt) {

    public FactEdit {
        Objects.requireNonNull(fact, "fact");
        if (revision < 1) {
            throw new IllegalArgumentException("revision beginnt bei 1");
        }
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (fact.origin() == FactOrigin.AUTOMATIC && fact.review() == FactReview.UNREVIEWED) {
            throw new IllegalArgumentException("Ein ungeprüfter automatischer Fakt gehört nicht in die Prüfablage");
        }
    }

    public String id() {
        return fact.id();
    }
}
