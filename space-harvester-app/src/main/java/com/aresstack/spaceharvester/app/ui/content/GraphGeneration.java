package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.snapshot.PageSet;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Die Rückfrage von „Wissensgraph erzeugen“ im Reiter „Graph“ (W5, UX 22.11.2026): „Wissensgraph wirklich erzeugen?“
 * als Überschrift, darunter eine Tabelle je Stufe des Dienst-Planers: Stufe, Zahl der Seiten und gedämpft in eigener Spalte, was dazu
 * gehört („4 erst laden“, „läuft schon“), bewusst abgeschaltete Stufen gelb „abgeschaltet (wird ausgelassen)“. Darunter
 * gelb, wie viele Seiten ohne KI-Freigabe ausgelassen werden (nur, wenn überhaupt etwas an den KI-Dienst ginge), rot
 * die ohne Zugriff; ohne bereites Chat-Modell steht in der Zeile „Aussagenanalyse“ statt der Zahl gelb „kein
 * Chat-Modell (wird ausgelassen)“ wie bei einer abgeschalteten Stufe (UX 22.11.2026); in eigener Zeile direkt über
 * Ja/Nein steht, wie viele Seiten an den KI-Dienst gehen: die von „Bilder beschreiben“ immer, die der Aussagenanalyse
 * nur mit dem KI-Analysator, jede Seite nur einmal (nur die Zahl, kein Geldbetrag; Seiten ohne KI-Freigabe zählen
 * nicht mit). Stufen, die noch keine Arbeit haben, erscheinen nicht.
 */
final class GraphGeneration {

    /** Die Frage, zugleich die Überschrift des Overlays neben dem Esc-Kreis (kein eigener Titel, UX 22.11.2026). */
    static final String QUESTION = "Wissensgraph wirklich erzeugen?";
    static final String RUNNING = "läuft schon";
    static final String DISABLED = "abgeschaltet (wird ausgelassen)";
    static final String NOTHING = "nichts zu tun";
    /**
     * Gelb in der Zusatzspalte der Zeile „Aussagenanalyse“ (ohne Seitenzahl), wenn sie mit dem KI-Analysator Seiten
     * hätte, aber kein Chat-Modell bereit ist (Auflage W5, Form UX 22.11.2026): Der Lauf lässt sie bewusst aus, sie
     * zählen nicht beim KI-Dienst.
     */
    static final String NO_MODEL = "kein Chat-Modell (wird ausgelassen)";

    private GraphGeneration() {
    }

    /**
     * Was „Wissensgraph erzeugen“ jetzt täte, ohne etwas zu starten.
     *
     * @param plan        die Vorschau des Dienst-Planers
     * @param analyzer    der eingestellte Aussagenanalysator
     * @param notReleased Seiten ohne KI-Freigabe, die ausgelassen werden, jede nur einmal: beim Bilderbeschreiben
     *                    immer, bei der Aussagenanalyse nur mit dem KI-Analysator
     * @param modelReady  ob für den KI-Analysator ein Chat-Modell bereit ist; ohne gehen die Seiten der
     *                    Aussagenanalyse nicht an den KI-Dienst, der Lauf lässt sie bewusst aus ({@link #NO_MODEL})
     */
    record Preview(ServicePreview plan, AnalyzerChoice analyzer, int notReleased, boolean modelReady) {

        Preview {
            Objects.requireNonNull(plan, "plan");
            analyzer = Objects.requireNonNullElse(analyzer, AnalyzerChoice.PARSER);
        }

        /** Mit bereitem Chat-Modell. */
        Preview(ServicePreview plan, AnalyzerChoice analyzer, int notReleased) {
            this(plan, analyzer, notReleased, true);
        }

        /**
         * Ob die Aussagenanalyse Seiten zu rechnen hätte, die der KI-Analysator ohne bereites Chat-Modell auslässt: dann
         * gehen sie nicht an den KI-Dienst, und gelb steht {@link #NO_MODEL}.
         */
        boolean statementsWithoutModel() {
            return analyzer == AnalyzerChoice.MODEL && !modelReady && plan.stages().stream()
                    .anyMatch(stage -> STATEMENTS.equals(stage.id()) && stage.state() == ServicePreview.State.PLANNED
                            && stage.pages() > 0);
        }

        /** Ob nichts fehlt: dann nur neu laden, ohne Rückfrage. */
        boolean nothingMissing() {
            return plan.nothingMissing();
        }

        /**
         * Seiten, die an den KI-Dienst gehen: die neuen von „Bilder beschreiben“ immer (es gibt nur freigegebene ans
         * Modell), die der Aussagenanalyse nur mit dem KI-Analysator und bereitem Chat-Modell; eine Seite in beiden
         * zählt einmal. Ohne die Seiten je Stufe ({@link ServicePreview#planned}) bleibt nur die Summe der Zahlen.
         */
        int toModel() {
            Set<String> union = new HashSet<>();
            int counted = 0;
            for (ServicePreview.Stage stage : plan.stages()) {
                boolean toModel = IMAGES.equals(stage.id())
                        || STATEMENTS.equals(stage.id()) && analyzer == AnalyzerChoice.MODEL && modelReady;
                if (!toModel || stage.state() != ServicePreview.State.PLANNED || stage.pages() <= 0) {
                    continue;
                }
                PageSet pages = plan.planned(stage.id());
                if (pages.size() == 0) {
                    counted += stage.pages();
                    continue;
                }
                pages.asMap().forEach((sourceId, ids) -> ids.forEach(id -> union.add(sourceId + "\n" + id)));
            }
            return union.size() + counted;
        }
    }

    /** Kennung der Stufe „Aussagenanalyse“ im Dienst-Planer. */
    static final String STATEMENTS = "statements";
    /** Kennung der Stufe „Bilder beschreiben“ im Dienst-Planer; sie gibt nur freigegebene Seiten ans Modell. */
    static final String IMAGES = "images";

    /**
     * Eine Zeile der Rückfrage.
     *
     * @param stage   die Stufe, etwa „Aussagenanalyse“
     * @param count   ihre Zahl, etwa „12 Seiten“, oder leer (abgeschaltet, nichts zu tun)
     * @param extra   gedämpfter Zusatz („4 erst laden“) oder leer
     * @param warning ob der Zusatz gelb ist (bewusst ausgelassen)
     */
    record Line(String stage, String count, String extra, boolean warning) {
    }

    /** Je Stufe mit Arbeit eine Zeile, Vorstufen zuerst, das Ziel zuletzt; Stufen ohne Arbeit fehlen. */
    static List<Line> lines(Preview preview) {
        List<Line> lines = new ArrayList<>();
        for (ServicePreview.Stage stage : preview.plan().stages()) {
            switch (stage.state()) {
                case NOT_WIRED -> {
                    // noch keine Arbeit: weder zu rechnen noch auszulassen
                }
                case DISABLED -> lines.add(new Line(stage.title(), "", DISABLED, true));
                case RUNNING -> lines.add(new Line(stage.title(), pages(stage.reused()), extras(stage, RUNNING),
                        false));
                case NOTHING_MISSING -> lines.add(new Line(stage.title(), "", extras(stage, NOTHING), false));
                case PLANNED -> lines.add(STATEMENTS.equals(stage.id()) && preview.statementsWithoutModel()
                        ? new Line(stage.title(), "", NO_MODEL, true)
                        : new Line(stage.title(), pages(stage.pages()),
                                extras(stage, stage.reused() > 0 ? stage.reused() + " " + RUNNING : ""), false));
            }
        }
        return lines;
    }

    /**
     * Der Satz zum KI-Dienst direkt über Ja/Nein, oder {@code null}, wenn keine Seite an den KI-Dienst geht. Ohne bereites
     * Chat-Modell zählt die Aussagenanalyse nicht mit; gehen dann noch Bilder an den KI-Dienst, nennt er nur deren
     * Seiten.
     */
    static String modelLine(Preview preview) {
        int pages = preview.toModel();
        return pages <= 0 ? null : pages(pages) + " gehen an den KI-Dienst.";
    }

    /** Gelb: Seiten ohne KI-Freigabe, die ausgelassen werden; {@code null} ohne. */
    static String notReleasedLine(Preview preview) {
        return preview.notReleased() <= 0 ? null
                : preview.notReleased() + " ohne KI-Freigabe (" + (preview.notReleased() == 1 ? "wird" : "werden")
                + " ausgelassen)";
    }

    /**
     * In der Rückfrage: die gelbe Zeile der Seiten ohne KI-Freigabe nur, wenn überhaupt eine Seite an den KI-Dienst
     * ginge (UX 22.11.2026); sonst {@code null}.
     */
    static String questionNotReleasedLine(Preview preview) {
        return preview.toModel() > 0 ? notReleasedLine(preview) : null;
    }

    /** Rot (fehlender Zugriff): Seiten ohne Zugriff, die ausgelassen werden; {@code null} ohne. */
    static String inaccessibleLine(Preview preview) {
        int hidden = preview.plan().inaccessible();
        return hidden <= 0 ? null : pages(hidden) + " ohne Zugriff (" + (hidden == 1 ? "wird" : "werden")
                + " ausgelassen)";
    }

    /** Die ganze Rückfrage als Text (Bildschirmleser, Tests), Zeile für Zeile. */
    static String text(Preview preview) {
        StringBuilder text = new StringBuilder(QUESTION);
        for (Line line : lines(preview)) {
            text.append('\n').append(line.stage()).append(line.count().isEmpty() ? "" : " · " + line.count())
                    .append(line.extra().isEmpty() ? "" : " · " + line.extra());
        }
        for (String extra : new String[] {questionNotReleasedLine(preview), inaccessibleLine(preview),
                modelLine(preview)}) {
            if (extra != null) {
                text.append('\n').append(extra);
            }
        }
        return text.toString();
    }

    /**
     * Der Inhalt des Overlays unter der Frage (sie ist die Überschrift, {@link #QUESTION}): Tabelle der Stufen (Stufe,
     * Zahl rechtsbündig, Zusatz gedämpft), gelb die Seiten ohne KI-Freigabe, rot die ohne Zugriff, ganz unten in eigener
     * Zeile der Satz zum KI-Dienst, direkt über Ja/Nein.
     */
    static JComponent body(Preview preview) {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = -1;
        for (Line line : lines(preview)) {
            c.gridy++;
            c.gridx = 0;
            c.weightx = 0;
            c.insets = new Insets(2, 0, 2, 14);
            body.add(Comic.text(line.stage()), c);
            c.gridx = 1;
            JLabel count = Comic.text(line.count().isEmpty() ? " " : line.count());
            count.setHorizontalAlignment(JLabel.RIGHT);
            body.add(count, c);
            c.gridx = 2;
            c.weightx = 1;
            c.insets = new Insets(2, 0, 2, 0);
            JLabel extra = Comic.muted(line.extra().isEmpty() ? " " : line.extra());
            if (line.warning()) {
                extra.setForeground(Comic.STANDING_UNCLEAR);
            }
            body.add(extra, c);
        }
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        boolean first = true;
        String inaccessible = inaccessibleLine(preview);
        for (String warning : new String[] {questionNotReleasedLine(preview), inaccessible}) {
            if (warning != null) {
                c.gridy++;
                c.insets = new Insets(first ? 10 : 2, 0, 0, 0);
                JLabel label = Comic.muted(warning);
                // Gelb heißt bewusst ausgelassen, Rot fehlender Zugriff (Projektregel).
                label.setForeground(warning.equals(inaccessible) ? Comic.RED : Comic.STANDING_UNCLEAR);
                body.add(label, c);
                first = false;
            }
        }
        String model = modelLine(preview);
        if (model != null) {
            c.gridy++;
            c.insets = new Insets(10, 0, 0, 0);
            body.add(Comic.text(model), c);
        }
        body.getAccessibleContext().setAccessibleName(text(preview));
        return body;
    }

    /** Die Zusätze einer Stufe, gedämpft: {@code state}, dann „N erst laden“. */
    private static String extras(ServicePreview.Stage stage, String state) {
        String toLoad = stage.toLoad() > 0 ? stage.toLoad() + " erst laden" : "";
        if (state.isEmpty()) {
            return toLoad;
        }
        return toLoad.isEmpty() ? state : state + ", " + toLoad;
    }

    static String pages(long count) {
        return count == 1 ? "1 Seite" : count + " Seiten";
    }

    /** „1 Teil“ oder „3 Teilen“, wie in „2 von 3 Teilen fertig“. */
    static String parts(long count) {
        return count == 1 ? "1 Teil" : count + " Teilen";
    }
}
