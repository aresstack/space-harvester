package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.AnimatedThoughtBubblePanel;
import com.aresstack.spaceharvester.app.ui.Texts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Die gelbe Gedankenblase einer Antwort, wie die Recherchekarte {@code ChatResearchCard} im Enterprise AI Client
 * (Angelo 24.11.2026): Sie erscheint sofort mit dem Senden, zeigt das Wissen der Frage (Bereich oder interne Suche),
 * die Schritte, die Quellen und einen Balken über die Arbeitsschritte, und platzt, sobald die Antwort schreibt. Vorher
 * steht keine vorläufige Antwortblase und keine Zeile über dem Eingabefeld. Bereichssuche und interne Suche zeigen sie
 * gleich; ohne Wissen heißt sie „Antwort wird vorbereitet“. Nur auf dem EDT.
 */
final class KnowledgeCard {

    /** Wie im EAC, wenn die Frage Wissen aus dem Bestand bekommt (Bereich oder interne Suche). */
    static final String KNOWLEDGE_TITLE = "Internes Wissen durchsuchen";
    /** Ohne Wissen aus dem Bestand. */
    static final String TITLE = "Antwort wird vorbereitet";
    static final String STARTING = "Suche startet …";
    static final String THINKING = "Überlegt …";
    static final String PREPARING = "Antwort wird erstellt …";
    /** So viele erledigte Schritte stehen höchstens in der Blase; ältere fallen heraus, die Bilanz zählt alle. */
    static final int SHOWN_STEPS = 8;

    private final String title;
    private final BiFunction<String, String, AgentActivityBubblePanel> open;
    private AgentActivityBubblePanel bubble;
    private String knowledge = "";
    private final Deque<String> steps = new ArrayDeque<>();
    private int stepsDone;
    private String current;
    private int released;
    private List<String> sources = List.of();
    private int done;
    private int total = 1;
    private boolean finished;

    /**
     * @param withKnowledge ob die Frage Wissen aus dem Bestand bekommt
     * @param open          legt die Blase mit Titel und Text im Verlauf an
     */
    KnowledgeCard(boolean withKnowledge, BiFunction<String, String, AgentActivityBubblePanel> open) {
        this.title = withKnowledge ? KNOWLEDGE_TITLE : TITLE;
        this.current = withKnowledge ? STARTING : THINKING;
        this.open = open;
    }

    String title() {
        return title;
    }

    /** Die Wissenszeile („Wissen: Space … · 2515 Seiten · 1 davon nicht geladen“), oben in der Blase. */
    void knowledge(String line) {
        knowledge = line == null ? "" : line.strip();
        update();
    }

    /** Zeigt die Blase; einmal. */
    void open() {
        if (bubble == null && !finished) {
            bubble = open.apply(title, body());
        }
    }

    /** Was gerade läuft. */
    void step(String what) {
        if (what != null && !what.isBlank()) {
            current = what.strip();
            update();
        }
    }

    /** Ein Schritt ist fertig: er wandert in die Liste. */
    void stepDone(String what) {
        if (what == null || what.isBlank()) {
            return;
        }
        stepsDone++;
        steps.addLast("✓ " + what.strip());
        while (steps.size() > SHOWN_STEPS) {
            steps.removeFirst();
        }
        update();
    }

    void released(int count) {
        released += Math.max(0, count);
        update();
    }

    /** Die Quellen der Antwort, nummeriert wie unter der Antwort. */
    void sources(List<String> titles) {
        sources = titles == null ? List.of() : List.copyOf(titles);
        update();
    }

    /** Fortschritt der Arbeit: {@code done} von {@code total} Schritten; der Balken geht nie zurück. */
    void progress(int done, int total) {
        this.total = Math.max(1, total);
        this.done = Math.max(this.done, Math.min(done, this.total));
        update();
    }

    /** Das Wissen steht, das Modell schreibt gleich. */
    void preparing() {
        current = PREPARING;
        update();
    }

    /** Die Antwort schreibt (oder der Turn endet): Die Blase platzt mit der Bilanz. */
    void finish(boolean cancelled) {
        if (finished) {
            return;
        }
        finished = true;
        done = total;
        if (bubble != null && bubble.getVisualState() == AnimatedThoughtBubblePanel.VisualState.RUNNING) {
            if (cancelled) {
                bubble.cancel(summary(true), null);
            } else {
                bubble.completeSuccessfully(summary(false), null);
            }
        }
    }

    boolean finished() {
        return finished;
    }

    /** „Recherche abgeschlossen · 3 Schritte · 2 Quellen · 2 Seiten für KI freigegeben“. */
    String summary(boolean cancelled) {
        StringBuilder text = new StringBuilder(cancelled ? "Abgebrochen" : "Recherche abgeschlossen");
        if (stepsDone > 0) {
            text.append(" · ").append(Texts.count(stepsDone, "Schritt", "Schritte"));
        }
        if (!sources.isEmpty()) {
            text.append(" · ").append(Texts.count(sources.size(), "Quelle", "Quellen"));
        }
        if (released > 0) {
            text.append(" · ").append(Texts.count(released, "Seite", "Seiten")).append(" für KI freigegeben");
        }
        return text.toString();
    }

    String body() {
        StringBuilder text = new StringBuilder(knowledge);
        for (String step : steps) {
            text.append(text.isEmpty() ? "" : "\n").append(step);
        }
        if (!current.isEmpty()) {
            text.append(text.isEmpty() ? "" : "\n").append(current);
        }
        if (!sources.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                list.append(i == 0 ? "" : "  ").append('[').append(i + 1).append("] ").append(sources.get(i));
            }
            text.append(text.isEmpty() ? "" : "\n").append("Quellen: ").append(list);
        }
        if (released > 0) {
            text.append(text.isEmpty() ? "" : "\n").append(Texts.count(released, "Seite", "Seiten"))
                    .append(" für KI freigegeben");
        }
        text.append(" [[bar:").append(done).append('/').append(total).append("]]");
        return text.toString();
    }

    private void update() {
        if (bubble != null && !finished
                && bubble.getVisualState() == AnimatedThoughtBubblePanel.VisualState.RUNNING) {
            bubble.updateActivity(title, body());
        }
    }
}
