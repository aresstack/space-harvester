package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die Rückfrage von „Wissensgraph erzeugen“ im Reiter „Graph“ (W5, UX 22.11.2026): „Wissensgraph wirklich erzeugen?“,
 * darunter eine Tabelle je Stufe des Dienst-Planers: Stufe, Zahl der Seiten und gedämpft in eigener Spalte, was dazu
 * gehört („4 erst laden“, „läuft schon“), bewusst abgeschaltete Stufen gelb „abgeschaltet (wird ausgelassen)“. Darunter
 * gelb, wie viele Seiten ohne KI-Freigabe ausgelassen werden, rot die ohne Zugriff; mit dem KI-Analysator steht in
 * eigener Zeile direkt über Ja/Nein, wie viele Seiten an den KI-Dienst gehen (nur die Zahl, kein Geldbetrag; Seiten
 * ohne KI-Freigabe zählen nicht mit). Stufen, die noch keine Arbeit haben, erscheinen nicht.
 */
final class GraphGeneration {

    static final String TITLE = "Wissensgraph erzeugen";
    static final String QUESTION = "Wissensgraph wirklich erzeugen?";
    static final String RUNNING = "läuft schon";
    static final String DISABLED = "abgeschaltet (wird ausgelassen)";
    static final String NOTHING = "nichts zu tun";

    private GraphGeneration() {
    }

    /**
     * Was „Wissensgraph erzeugen“ jetzt täte, ohne etwas zu starten.
     *
     * @param plan        die Vorschau des Dienst-Planers
     * @param analyzer    der eingestellte Aussagenanalysator
     * @param notReleased mit dem KI-Analysator: Seiten ohne KI-Freigabe, die ausgelassen werden; sonst 0
     */
    record Preview(ServicePreview plan, AnalyzerChoice analyzer, int notReleased) {

        Preview {
            Objects.requireNonNull(plan, "plan");
            analyzer = Objects.requireNonNullElse(analyzer, AnalyzerChoice.PARSER);
        }

        /** Ob nichts fehlt: dann nur neu laden, ohne Rückfrage. */
        boolean nothingMissing() {
            return plan.nothingMissing();
        }

        /** Seiten, die an den KI-Dienst gehen: die der Aussagenanalyse, nur mit dem KI-Analysator. */
        int toModel() {
            if (analyzer != AnalyzerChoice.MODEL) {
                return 0;
            }
            return plan.stages().stream().filter(stage -> STATEMENTS.equals(stage.id()))
                    .mapToInt(ServicePreview.Stage::pages).sum();
        }
    }

    /** Kennung der Stufe „Aussagenanalyse“ im Dienst-Planer. */
    static final String STATEMENTS = "statements";

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
                case PLANNED -> lines.add(new Line(stage.title(), pages(stage.pages()),
                        extras(stage, stage.reused() > 0 ? stage.reused() + " " + RUNNING : ""), false));
            }
        }
        return lines;
    }

    /** Der Satz zum KI-Dienst direkt über Ja/Nein, oder {@code null} ohne KI-Analysator oder ohne Seiten dafür. */
    static String modelLine(Preview preview) {
        int pages = preview.toModel();
        return pages <= 0 ? null : pages(pages) + " gehen an den KI-Dienst.";
    }

    /** Gelb: Seiten ohne KI-Freigabe, die ausgelassen werden; {@code null} ohne. */
    static String notReleasedLine(Preview preview) {
        return preview.analyzer() != AnalyzerChoice.MODEL || preview.notReleased() <= 0 ? null
                : preview.notReleased() + " ohne KI-Freigabe (" + (preview.notReleased() == 1 ? "wird" : "werden")
                + " ausgelassen)";
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
        for (String extra : new String[] {notReleasedLine(preview), inaccessibleLine(preview), modelLine(preview)}) {
            if (extra != null) {
                text.append('\n').append(extra);
            }
        }
        return text.toString();
    }

    /**
     * Der Inhalt des Overlays: Frage, Tabelle der Stufen (Stufe, Zahl rechtsbündig, Zusatz gedämpft), gelb die Seiten
     * ohne KI-Freigabe, rot die ohne Zugriff, ganz unten in eigener Zeile der Satz zum KI-Dienst, direkt über Ja/Nein.
     */
    static JComponent body(Preview preview) {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 10, 0);
        JLabel question = Comic.text(QUESTION);
        question.setFont(Comic.semiBold(14f));
        body.add(question, c);
        c.gridwidth = 1;
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
        for (String warning : new String[] {notReleasedLine(preview), inaccessible}) {
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
