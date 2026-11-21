package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.query.HitReason;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Treffergrund (R1) in Worten der Oberfläche: eine knappe Marke am Treffer und die Einzelheiten fürs Overlay.
 * Gesagt wird nur, was die Suche als Ursache meldet; Funde ohne Wortangabe (nur von der Quelle gemeldet, semantisch
 * ähnlich) bekommen keine Fundstelle dazuerfunden.
 */
final class HitReasons {

    /** Überschrift des Overlays. */
    static final String HEADING = "Warum gefunden";

    private HitReasons() {
    }

    /**
     * Die Marke am Treffer, leer, wenn die eingegebene Anfrage selbst ihn lieferte oder nichts gemeldet ist; sonst die
     * erste Ursache über eine Begriffsbeziehung oder Variante, etwa „Abkürzung „Rückmeldeschlüssel““.
     */
    static String mark(List<HitReason> reasons) {
        if (reasons == null || reasons.isEmpty() || reasons.stream().anyMatch(HitReason::original)) {
            return "";
        }
        HitReason first = reasons.getFirst();
        if (first.relation() != null) {
            return relationName(first.relation().type()) + " „" + first.relation().counterpart() + "“";
        }
        return "Variante „" + first.query() + "“";
    }

    /** Die Einzelheiten aller Ursachen, je Ursache ein Absatz. */
    static String details(List<HitReason> reasons) {
        List<String> parts = new ArrayList<>();
        for (HitReason reason : reasons == null ? List.<HitReason>of() : reasons) {
            parts.add(detail(reason));
        }
        return String.join("\n\n", parts);
    }

    private static String detail(HitReason reason) {
        StringBuilder text = new StringBuilder();
        switch (reason.kind()) {
            case TEXT -> {
                text.append("Gesucht mit „").append(reason.query()).append("“ im Volltext.");
                if (!reason.inTitle().isEmpty()) {
                    text.append("\nIm Titel: ").append(String.join(", ", reason.inTitle()));
                }
                if (!reason.inText().isEmpty()) {
                    text.append("\nIm Text: ").append(String.join(", ", reason.inText()));
                }
            }
            case SOURCE -> text.append("Von der Quelle gemeldet auf „").append(reason.query())
                    .append("“. Noch nicht im Cache geprüft, daher keine Fundstelle.");
            case SEMANTIC -> text.append("Ähnlich im Inhalt zu „").append(reason.query())
                    .append("“ (semantische Suche), keine Fundstelle einzelner Wörter.");
        }
        if (reason.relation() != null) {
            HitReason.Relation relation = reason.relation();
            text.append("\n„").append(relation.counterpart()).append("“ statt „").append(relation.term())
                    .append("“, bestätigt als ").append(relationName(relation.type())).append('.');
        }
        if (reason.variant() != null) {
            HitReason.Variant variant = reason.variant();
            text.append("\nVariante ").append(variant.priority()).append(" der Suche")
                    .append(variant.origin() == null ? ""
                            : variant.origin() == HitReason.Variant.Origin.MODEL ? ", vom KI-Modell"
                            : ", nach Regeln");
        }
        return text.toString();
    }

    /** Die Art der Beziehung in den Worten, die die App schon nutzt. */
    private static String relationName(String type) {
        return switch (type == null ? "" : type) {
            case "abbreviation" -> "Abkürzung";
            case "synonym" -> "Synonym";
            case "variant" -> "Schreibvariante";
            default -> "Begriffsbeziehung";
        };
    }
}
