package com.aresstack.spaceharvester.mainframe.dependency;

import java.util.Objects;

/**
 * Ein Eintrag der Ansicht „Ressourcen“ (Daten, Data Areas, Datasets …), wie {@code LeftDrawer.RelationEntry} in
 * MainframeMate.
 *
 * @param label  angezeigter Text
 * @param target was ein Doppelklick öffnet (siehe {@link CallNode#target()}), sonst {@code null}
 * @param type   Herkunft, z. B. {@code DEPENDENCY_USING} oder {@code JCL_DEP_DSN}
 * @param line   Zeile im offenen Dokument (1-basiert), {@code 0} wenn unbekannt
 */
public record Relation(String label, String target, String type, int line) {

    public Relation {
        Objects.requireNonNull(label, "label");
    }
}
