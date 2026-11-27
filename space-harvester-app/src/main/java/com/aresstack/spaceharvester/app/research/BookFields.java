package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.ai.AiChat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Die Felder der Eingabe zu „Korb füllen“ und „Buch schreiben“, die die KI aus Idee und Stichpunkten des Lesers füllt:
 * je Feld eine eigene Anfrage, die nur dieses Feld schreibt. Die Anfrage kennt die Stichpunkte, die aufgelösten
 * Abkürzungen (aus dem Glossar, nie vom Modell), die nicht aufgelösten (unverändert lassen) und die Felder, die schon
 * feststehen. Die Antwort ist reiner Text; {@link #clean} nimmt Aufzählungszeichen, Feldnamen und Codezäune weg.
 */
public final class BookFields {

    private BookFields() {
    }

    /** Ein Feld, das die KI füllt; {@code list}: eine Angabe je Zeile. */
    public enum Field {
        MISSION("Auftrag", false, "Schreibe den Auftrag für das Buch: in zwei bis vier Sätzen, worum es geht, für wen "
                + "es ist und was der Leser danach wissen oder können soll. Bleibe bei dem, was die Stichpunkte "
                + "sagen oder klar meinen."),
        QUESTIONS("Fragen an das Buch", true, "Schreibe die Fragen, die das Buch beantworten soll: eine Frage je "
                + "Zeile, jede für sich verständlich, konkret und aus den Stichpunkten abgeleitet. Nimm jede "
                + "Fragestellung der Stichpunkte auf; ergänze Fragen, die zum Verständnis nötig sind."),
        EDGE("Rand des Themas", false, "Beschreibe in ein bis drei Sätzen den Rand des Themas: wie weit das Buch "
                + "reicht und wo es aufhört."),
        INCLUDED("Dazu gehört ausdrücklich", true, "Nenne die Themen, Begriffe und Gegenstände, die ausdrücklich zum "
                + "Buch gehören: eine Angabe je Zeile, kurz, ohne Erklärung."),
        EXCLUDED("Ausgeschlossen", true, "Nenne die Nachbarthemen, die ausdrücklich NICHT ins Buch gehören, weil sie "
                + "nahe liegen, aber vom Auftrag wegführen: eine Angabe je Zeile, kurz, ohne Erklärung. Nenne nur, "
                + "was die Stichpunkte nahelegen; erfinde keine Ausschlüsse ohne Anlass.");

        private final String label;
        private final boolean list;
        private final String task;

        Field(String label, boolean list, String task) {
            this.label = label;
            this.list = list;
            this.task = task;
        }

        public String label() {
            return label;
        }

        public boolean list() {
            return list;
        }
    }

    static final String SYSTEM = "Du bereitest die Eingabe für ein Buch vor, das aus einem Wissensbestand geschrieben "
            + "wird. Der Leser hat eine grobe Idee, Fragestellungen und Stichpunkte notiert. Du füllst daraus genau "
            + "ein Feld der Eingabe. Antworte nur mit dem Inhalt dieses Felds, ohne Überschrift, ohne Einleitung, "
            + "ohne Markdown. Schreibe Deutsch.";

    /**
     * Die Anfrage für {@code field}.
     *
     * @param ideas    Idee und Stichpunkte des Lesers
     * @param fixed    Felder, die schon feststehen und nicht neu geschrieben werden (leere fehlen)
     */
    public static List<AiChat.Message> messages(Field field, String ideas, Abbreviations.Result abbreviations,
                                                Map<Field, String> fixed) {
        Objects.requireNonNull(field, "field");
        StringBuilder user = new StringBuilder();
        user.append("Idee und Stichpunkte des Lesers:\n").append(ideas == null ? "" : ideas.strip()).append("\n\n");
        Abbreviations.Result known = abbreviations == null ? Abbreviations.Result.NONE : abbreviations;
        if (!known.resolved().isEmpty()) {
            user.append("Abkürzungen laut Fachglossar (verwende diese Bedeutungen):\n");
            known.resolved().forEach(r -> user.append("- ").append(r.line()).append('\n'));
            user.append('\n');
        }
        if (!known.unresolved().isEmpty()) {
            user.append("Diese Abkürzungen sind unbekannt. Lass sie unverändert stehen und erfinde keine Bedeutung: ")
                    .append(String.join(", ", known.unresolved())).append("\n\n");
        }
        if (fixed != null) {
            for (Field other : Field.values()) {
                String value = fixed.get(other);
                if (other != field && value != null && !value.isBlank()) {
                    user.append("Schon festgelegt – ").append(other.label()).append(":\n").append(value.strip())
                            .append("\n\n");
                }
            }
        }
        user.append("Feld „").append(field.label()).append("“: ").append(field.task);
        return List.of(AiChat.Message.system(SYSTEM), AiChat.Message.user(user.toString()));
    }

    /** Die Antwort als Feldinhalt: ohne Codezaun und Feldnamen; bei Listen eine Angabe je Zeile ohne Zeichen davor. */
    public static String clean(Field field, String answer) {
        if (answer == null) {
            return "";
        }
        String text = answer.replace("\r", "").strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\n?", "").replaceFirst("\\n?```\\s*$", "").strip();
        }
        String label = field.label().toLowerCase(Locale.ROOT);
        String head = text.toLowerCase(Locale.ROOT);
        if (head.startsWith(label + ":") || head.startsWith("**" + label + ":**") || head.startsWith(label + "\n")) {
            text = text.substring(text.indexOf(head.startsWith(label + "\n") ? '\n' : ':') + 1).replace("**", "")
                    .strip();
        }
        if (!field.list()) {
            return text;
        }
        List<String> lines = new ArrayList<>();
        for (String line : Arrays.asList(text.split("\n"))) {
            String item = line.strip().replaceFirst("^([-*•–]|\\d+[.)])\\s+", "").strip();
            if (!item.isEmpty()) {
                lines.add(item);
            }
        }
        return String.join("\n", lines);
    }
}
