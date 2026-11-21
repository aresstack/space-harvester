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
        // Suchmethode „Nachbarseiten“ (R2): Der Treffer kam nur als Nachbar herein, das ist sein Grund. Kurz, der
        // Titel der Ausgangsseite steht im Tooltip und im Overlay (UX-Manager 21.11.2026).
        if (neighbor(reasons) != null) {
            return new MarkedTextRenderer.Mark(NEIGHBOR, NEIGHBOR);
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

    /** Die Marke eines Nachbar-Treffers (R2). */
    static final String NEIGHBOR = "Nachbar";

    /** Die erste Nachbar-Ursache oder {@code null}. */
    private static HitReason neighbor(List<HitReason> reasons) {
        return reasons == null ? null : reasons.stream().filter(reason -> reason.kind() == HitReason.Kind.NEIGHBOR)
                .findFirst().orElse(null);
    }

    /** Der Tooltip der Marke: bei einem Nachbarn die Ausgangsseite und die Kante, sonst {@code null}. */
    static String tip(List<HitReason> reasons) {
        HitReason first = neighbor(reasons);
        if (first != null) {
            return neighborLine(first.via());
        }
        HitReason.Relation synonym = synonymOnly(reasons);
        return synonym == null ? null : synonymLine(synonym);
    }

    /** Art „vorgeschlagenes Synonym eines Fachbegriffs“ (K5a, {@code GlossarySynonyms.PROPOSED}). */
    static final String PROPOSED_SYNONYM = "synonym-proposed";

    /**
     * Die Synonym-Beziehung eines Treffers, der nur über ein Synonym gefunden wurde (K5a: Marke mit Tooltip), sonst
     * {@code null}.
     */
    private static HitReason.Relation synonymOnly(List<HitReason> reasons) {
        if (reasons == null) {
            return null;
        }
        List<HitReason> text = reasons.stream().filter(reason -> reason.kind() == HitReason.Kind.TEXT).toList();
        if (text.isEmpty() || text.stream().anyMatch(HitReason::original)) {
            return null;
        }
        HitReason.Relation relation = text.getFirst().relation();
        return relation != null && ("synonym".equals(relation.type()) || PROPOSED_SYNONYM.equals(relation.type()))
                ? relation : null;
    }

    /** „Fachbegriff „Kunde“, Synonym „Klient““ für den Tooltip der Marke (K5a). */
    static String synonymLine(HitReason.Relation relation) {
        return "Fachbegriff „" + relation.term() + "“, " + (PROPOSED_SYNONYM.equals(relation.type())
                ? "vorgeschlagenes Synonym" : "Synonym") + " „" + relation.counterpart() + "“";
    }

    /** „Unterseite von „Betrieb““ usw., aus Sicht des Treffers. */
    static String neighborLine(HitReason.Neighbor via) {
        String seed = "„" + via.title() + "“";
        return switch (via.edge()) {
            case PARENT -> "Elternseite von " + seed;
            case CHILD -> "Unterseite von " + seed;
            case LINKS_TO -> seed + " verweist hierher";
            case LINKED_FROM -> "Verweist auf " + seed;
        };
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
            case NEIGHBOR -> text.append("Nachbarseite: ").append(neighborLine(reason.via()))
                    .append(", einem besseren Treffer. Als Kandidat dazugenommen und wie jeder Treffer gegen „")
                    .append(reason.query()).append("“ geprüft.");
        }
        if (reason.relation() != null) {
            HitReason.Relation relation = reason.relation();
            text.append("\n„").append(relation.counterpart()).append("“ statt „").append(relation.term())
                    .append(PROPOSED_SYNONYM.equals(relation.type())
                            ? "“, als Synonym des Fachbegriffs vorgeschlagen, noch nicht bestätigt"
                            : "“, bestätigt als " + relationName(relation.type())).append('.');
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
            case PROPOSED_SYNONYM -> "Synonym (Vorschlag)";
            case "variant" -> "Schreibvariante";
            default -> "Begriffsbeziehung";
        };
    }
}
