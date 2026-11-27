package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.proasteion.application.browser.WebExplorationProgress;
import com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.AnimatedThoughtBubblePanel;

import java.util.function.Function;

/**
 * Die Karte „Browserrecherche“ einer Antwort mit Websuche, wie {@code ChatBrowserCard} im Enterprise AI Client: eine
 * Aktivitätsblase, die mit dem ersten Browser-Werkzeug erscheint, laufend Zähler und Schritte zeigt und am Ende mit
 * der Bilanz platzt. Eine Antwort ohne Browser zeigt keine Karte. Nur auf dem EDT.
 */
final class WebSearchCard {

    static final String TITLE = "Browserrecherche";
    static final String WORKING = "Browser arbeitet …";
    /** Die Wolke, solange der erste Browser-Aufruf den Browser startet (wie im EAC). */
    static final String STARTING = "Browser wird gestartet";

    /** Legt die Blase mit Titel und Text im Verlauf an. */
    private final Function<String, AgentActivityBubblePanel> open;
    private AgentActivityBubblePanel bubble;
    private WebExplorationProgress progress = WebExplorationProgress.none();
    private boolean finished;

    WebSearchCard(Function<String, AgentActivityBubblePanel> open) {
        this.open = open;
    }

    /** Zeigt den neuen Stand; die Karte erscheint mit dem ersten Browser-Aufruf. */
    void update(WebExplorationProgress changed) {
        if (finished || changed == null) {
            return;
        }
        progress = changed;
        if (!progress.isActive()) {
            return;
        }
        if (bubble == null) {
            bubble = open.apply(body(progress));
        } else if (bubble.getVisualState() == AnimatedThoughtBubblePanel.VisualState.RUNNING) {
            bubble.updateActivity(TITLE, body(progress));
        }
    }

    /** Platzt mit der Bilanz von {@code last}; ohne Browser-Aufruf gibt es keine Karte. */
    void finish(WebExplorationProgress last, boolean cancelled) {
        if (finished) {
            return;
        }
        finished = true;
        if (last != null) {
            progress = last;
        }
        if (bubble != null && bubble.getVisualState() == AnimatedThoughtBubblePanel.VisualState.RUNNING) {
            bubble.completeSuccessfully(summary(progress, cancelled), null);
        }
    }

    /** „Browserrecherche abgeschlossen · 14 Seiten besucht · 37 Links geprüft · 6 relevante Seiten verwendet“. */
    static String summary(WebExplorationProgress progress, boolean cancelled) {
        StringBuilder text = new StringBuilder(cancelled ? "Browserrecherche abgebrochen"
                : "Browserrecherche abgeschlossen").append(" · ").append(pages(progress.visited())).append(" besucht");
        if (progress.examined() > 0) {
            text.append(" · ").append(progress.examined()).append(progress.examined() == 1 ? " Link" : " Links")
                    .append(" geprüft");
        }
        text.append(" · ").append(progress.relevant())
                .append(progress.relevant() == 1 ? " relevante Seite verwendet" : " relevante Seiten verwendet");
        if (!progress.challenge().isEmpty()) {
            text.append(" · Browser bleibt offen (")
                    .append("login".equals(progress.challenge()) ? "Anmeldung" : "Prüfseite").append(')');
        }
        return text.toString();
    }

    static String body(WebExplorationProgress progress) {
        StringBuilder text = new StringBuilder(countLine(progress));
        for (WebExplorationProgress.Step step : progress.steps()) {
            text.append('\n').append(line(step));
        }
        if (progress.isRunning()) {
            text.append('\n').append(WORKING);
        }
        return text.toString();
    }

    /** „3 Seiten besucht · 37 Links geprüft · 2 relevant“, dazu Übersprungene und Fehlgeschlagene. */
    static String countLine(WebExplorationProgress progress) {
        StringBuilder text = new StringBuilder(pages(progress.visited())).append(" besucht · ")
                .append(progress.examined()).append(progress.examined() == 1 ? " Link" : " Links")
                .append(" geprüft · ").append(progress.relevant()).append(" relevant");
        if (progress.skipped() > 0) {
            text.append(" · ").append(progress.skipped()).append(" übersprungen");
        }
        if (progress.failed() > 0) {
            text.append(" · ").append(progress.failed()).append(" fehlgeschlagen");
        }
        return text.toString();
    }

    static String line(WebExplorationProgress.Step step) {
        String label = oneLine(step.label());
        return switch (step.kind()) {
            case OPENED -> "„" + label + "“ geöffnet";
            case BACK -> "Zurück zu „" + label + "“";
            case ACTED -> label;
            case CHALLENGE -> "login".equals(label) ? "Anmeldung nötig: bitte im Browserfenster anmelden"
                    : "Prüfseite: bitte im Browserfenster lösen";
            case LINKS -> step.count() + (step.count() == 1 ? " Link" : " Links")
                    + (label.isEmpty() ? " erkannt" : " passend zu „" + label + "“");
            case SKIPPED -> "„" + label + "“ übersprungen (nicht freigegeben)";
            case FAILED -> "Fehlgeschlagen: " + label;
            case MARKED_RELEVANT -> "„" + label + "“ ist relevant";
            case MARKED_IRRELEVANT -> "„" + label + "“ nicht relevant";
            case CLOSED -> "Browser geschlossen";
            default -> label;
        };
    }

    private static String pages(int count) {
        return count + (count == 1 ? " Seite" : " Seiten");
    }

    private static String oneLine(String value) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= 100 ? text : text.substring(0, 100) + " …";
    }
}
