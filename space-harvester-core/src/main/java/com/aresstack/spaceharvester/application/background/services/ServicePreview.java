package com.aresstack.spaceharvester.application.background.services;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis von {@link ServicePlanner#preview}: was {@link ServicePlanner#start} jetzt anlegen würde, ohne etwas zu
 * starten (W5). Beide rechnen mit demselben Code; Stufen, Teile und Hinweise stimmen also überein, solange sich der
 * Stand zwischen beiden Aufrufen nicht ändert.
 *
 * @param stages       je Dienst der Kette eine Stufe, Vorstufen in topologischer Reihenfolge, das Ziel zuletzt
 * @param notes        gelbe Hinweise, wörtlich dieselben wie in {@link ServicePlan#notes()}; nie mit Seitentiteln
 * @param inaccessible Seiten der Menge ohne Zugriff des Aufrufers, nur als Zahl
 */
public record ServicePreview(List<Stage> stages, List<String> notes, int inaccessible) {

    public ServicePreview {
        stages = List.copyOf(stages);
        notes = List.copyOf(notes);
    }

    /**
     * Ob nichts fehlt: Keine Vorstufe hätte Seiten zu rechnen oder wartet auf einen laufenden Teil. Das Ziel selbst
     * zählt nicht, denn es legt seine Teile immer an. Seiten, die erst geladen werden müssten, zählen nicht; sie
     * stehen in den Hinweisen.
     */
    public boolean nothingMissing() {
        for (int i = 0; i < stages.size() - 1; i++) {
            Stage stage = stages.get(i);
            if (stage.pages() > 0 || stage.reused() > 0) {
                return false;
            }
        }
        return true;
    }

    /** Die Stufe des Ziels (die letzte); leer nur ohne Stufen. */
    public java.util.Optional<Stage> goal() {
        return stages.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(stages.get(stages.size() - 1));
    }

    /** Stand einer Stufe in der Vorschau. */
    public enum State {
        /** Legt Teile an ({@link Stage#pages()} &gt; 0). */
        PLANNED,
        /** Alle fehlenden Seiten deckt schon ein laufender Teil ab („läuft schon“). */
        RUNNING,
        /** Nichts zu tun. */
        NOTHING_MISSING,
        /** {@link ServiceStart#DISABLED}: bewusst ausgelassen, gelber Hinweis. */
        DISABLED,
        /** Der Dienst hat noch keine Arbeit ({@link AnalysisService#kind()} {@code null}); ohne Hinweis. */
        NOT_WIRED
    }

    /**
     * Eine Stufe der Vorschau.
     *
     * @param id     Kennung des Dienstes
     * @param title  Titel des Dienstes, etwa „Aussagenanalyse“
     * @param state  Stand
     * @param pages  Seiten, für die neue Teile entstehen (beim Ziel alle sichtbaren Seiten der Menge)
     * @param toLoad Seiten, die erst geladen werden müssten und ausgelassen bleiben
     * @param reused Seiten, die ein schon laufender Teil derselben Art abdeckt
     * @param parts  so viele neue Teile (je Space einer)
     */
    public record Stage(String id, String title, State state, int pages, int toLoad, int reused, int parts) {

        public Stage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(state, "state");
        }
    }
}
