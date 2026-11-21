package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.MarkedTextRenderer;
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
     * Die Marke am Treffer oder {@code null}: keine, wenn die eingegebene Anfrage ihn im Volltext lieferte oder nichts
     * gemeldet ist; über eine Begriffsbeziehung oder Variante deren Art („Abkürzung „Rückmeldeschlüssel““, kurz
     * „Abkürzung“); ohne Volltexttreffer „ähnlich im Inhalt“ (semantisch) oder „von der Quelle gemeldet“.
     */
    static MarkedTextRenderer.Mark mark(List<HitReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        List<HitReason> text = reasons.stream().filter(reason -> reason.kind() == HitReason.Kind.TEXT).toList();
        if (text.isEmpty()) {
            boolean similar = reasons.stream().anyMatch(reason -> reason.kind() == HitReason.Kind.SEMANTIC);
            return new MarkedTextRenderer.Mark(similar ? "ähnlich im Inhalt" : "von der Quelle gemeldet",
                    similar ? "ähnlich" : "Quelle");
        }
        if (text.stream().anyMatch(HitReason::original)) {
            return null;
        }
        HitReason first = text.getFirst();
        if (first.relation() != null) {
            String kind = relationName(first.relation().type());
            return new MarkedTextRenderer.Mark(kind + " „" + first.relation().counterpart() + "“", kind);
        }
        return new MarkedTextRenderer.Mark("Variante „" + first.query() + "“", "Variante");
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
                            : ", nach Regeln").append('.');
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
