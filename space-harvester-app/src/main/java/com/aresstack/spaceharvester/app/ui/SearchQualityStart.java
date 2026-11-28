package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.controls.FileField;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.quality.QualitySuite;
import com.aresstack.spaceharvester.application.quality.SearchQualityRuns;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Startoverlay „Suchqualität messen“ (Q1b, Angelo 21.11.2026: Eintrag im ▼-Menü neben dem Besen). Testbestand mit
 * „Durchsuchen …“ im Feld, darunter ein Band mit der Zahl gültiger Fragen und ausgelassener Zeilen (gelb, nicht rot),
 * „Vergleichen mit (optional)“ mit ✕, einzige Aktion die Pille „Messen“, aktiv erst mit mindestens einer gültigen Frage.
 * Abbrechen nur über den Esc-Kreis. Wortlaut der Fragen erscheint nirgends.
 */
final class SearchQualityStart {

    static final String TITLE = "Suchqualität messen";
    static final String MENU = "Suchqualität messen …";
    static final String MENU_TIP = "Einen Testbestand an Fragen über den Suchweg des Chats stellen und zählen, wo die "
            + "erwarteten Seiten stehen; Ergebnis im Ausgabeordner unter „Suchqualität“";
    static final String START = "Messen";
    static final String SUITE = "Testbestand";
    static final String PREVIOUS = "Vergleichen mit (optional)";
    /** Höchstens so viele Zeilennummern im Band; der Rest steht im Tooltip. */
    static final int SHOWN_LINES = 6;

    /** Hintergrundarbeit (in Tests sofort). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Liest einen Testbestand zur Vorschau. */
    interface Reader {
        QualitySuite read(Path suite) throws Exception;
    }

    /** Startet den Auftrag. */
    interface Starter {
        JobStatus start(Path suite, Path previous) throws Exception;
    }

    enum Tone { NONE, NEUTRAL, NOTICE, PROBLEM }

    private final Reader reader;
    private final Starter starter;
    private final Runner runner;
    private final Consumer<JobStatus> started;
    private Runnable close = () -> { };
    final FileField suite = new FileField(Comic.field(36));
    final FileField previous = new FileField(Comic.field(36));
    final JLabel status = new JLabel(" ");
    /**
     * Mit der Suchmethode „Nachbarseiten“ (R2) messen; vorbelegt wie in den Suchoptionen. Ein Lauf mit und einer ohne
     * liegen nebeneinander im Ordner, der Lauf mit trägt „mit Nachbarseiten“ im Namen.
     */
    final JCheckBox neighbors = Comic.checkBox(NEIGHBORS);
    static final String NEIGHBORS = "Mit Nachbarseiten messen";
    /** Mit der Suchmethode „Synonyme der Fachbegriffe“ (K5a) messen; vorbelegt wie in den Suchoptionen. */
    final JCheckBox synonyms = Comic.checkBox(SYNONYMS);
    static final String SYNONYMS = "Mit Synonymen der Fachbegriffe messen";
    /** Mit der Suchmethode „Seiten zu Fachbegriffen“ (W8) messen; vorbelegt wie in den Suchoptionen. */
    final JCheckBox technicalTerms = Comic.checkBox(TECHNICAL_TERMS);
    static final String TECHNICAL_TERMS = "Mit Seiten zu Fachbegriffen messen";
    final ComposerButton start = Comic.primary(START, "Fragen stellen; Ergebnis und Bericht im Ausgabeordner unter "
            + "„" + SearchQualityRuns.FOLDER + "“");
    /** Mindestens {@link #WIDTH} breit, so hoch wie der Inhalt (das Band darf wachsen, „Messen“ bleibt sichtbar). */
    private final JPanel body = new JPanel(new GridBagLayout()) {
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(Math.max(WIDTH, size.width), size.height);
        }
    };
    static final int WIDTH = 500;
    private Tone tone = Tone.NONE;
    private int valid;
    private boolean starting;
    /** Zählt Lesevorgänge, damit ein verspätetes Ergebnis einer früher gewählten Datei nichts überschreibt. */
    private int reading;

    SearchQualityStart(Path outputDirectory, Reader reader, Starter starter, Runner runner,
                       Consumer<JobStatus> started) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.started = Objects.requireNonNull(started, "started");
        Path folder = outputDirectory == null ? null : outputDirectory.resolve(SearchQualityRuns.FOLDER);
        Path startFolder = folder != null && Files.isDirectory(folder) ? folder : outputDirectory;
        suite.startFolder(startFolder);
        suite.dialogTitle("Testbestand wählen");
        suite.extensions("Testbestand (*.tsv, *.txt)", "tsv", "txt");
        suite.onChange(this::read);
        suite.onInvalid(typed -> show(Tone.PROBLEM, "Keine lesbare Datei unter diesem Pfad", null));
        previous.startFolder(startFolder);
        previous.dialogTitle("Früheres Ergebnis wählen");
        previous.extensions("Ergebnis (*.tsv)", "tsv");
        previous.clearable("Kein Vergleich");
        neighbors.setOpaque(false);
        neighbors.setSelected(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().neighbors());
        neighbors.setToolTipText("Wie die Suchmethode „Nachbarseiten“ der Suche: Eltern-, Unter- und verlinkte "
                + "Seiten der besten Treffer kommen dazu, wenn sie die Frage auch treffen");
        synonyms.setOpaque(false);
        synonyms.setSelected(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().glossarySynonyms());
        synonyms.setToolTipText("Wie die Suchmethode „Synonyme der Fachbegriffe“ der Suche: zu bestätigten "
                + "Fachbegriffen auch ihre vorgeschlagenen Synonyme; der Lauf trägt „mit Synonymen“ im Namen");
        technicalTerms.setOpaque(false);
        technicalTerms.setSelected(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().termPages());
        technicalTerms.setToolTipText("Wie die Suchmethode „Seiten zu Fachbegriffen“ der Suche: Seiten mit "
                + "Aussagen zu Fachbegriffen der Frage kommen dazu; der Lauf trägt „mit Fachbegriffen“ im Namen");
        start.setEnabled(false);
        start.addActionListener(e -> start());
        status.setOpaque(true);
        status.setFont(Comic.regular(12.5f));
        // Von Anfang an so hoch wie das Band, damit das Overlay beim ersten Lesen nicht springt.
        show(Tone.NONE, " ", null);
        build();
    }

    /** Öffnet das Overlay modal über {@code parent}. */
    static void open(Component parent, AppContext context, Consumer<JobStatus> started, Consumer<Throwable> failed) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        Path output = context.exportDirectory();
        String version = com.aresstack.spaceharvester.app.SpaceHarvesterApp.version();
        Runner background = new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        };
        SearchQualityStart[] holder = new SearchQualityStart[1];
        SearchQualityStart start = new SearchQualityStart(output, path -> runtime.workspace().readQualitySuite(path),
                (suite, previous) -> runtime.workspace().startSearchQuality(suite, previous, output, version,
                        holder[0].neighbors.isSelected(), holder[0].synonyms.isSelected(),
                        holder[0].technicalTerms.isSelected()),
                background, started);
        holder[0] = start;
        JDialog dialog = Dialogs.dialog(parent, TITLE);
        start.close = dialog::dispose;
        Dialogs.escFrame(dialog, TITLE, start.component(), dialog::dispose);
        Dialogs.present(dialog, new Dimension(460, 0));
    }

    JComponent component() {
        return body;
    }

    /** Legt fest, was „schließen“ heißt (Overlay oder Test). */
    void onClose(Runnable action) {
        close = Objects.requireNonNull(action, "action");
    }

    Tone tone() {
        return tone;
    }

    String statusText() {
        return tone == Tone.NONE ? "" : status.getText().strip();
    }

    private void build() {
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, 0);
        body.add(Comic.caption(SUITE), c);
        c.insets = new Insets(0, 0, 0, 0);
        body.add(suite, c);
        c.insets = new Insets(6, 0, 0, 0);
        body.add(status, c);
        c.insets = new Insets(14, 0, 4, 0);
        body.add(Comic.caption(PREVIOUS), c);
        c.insets = new Insets(0, 0, 0, 0);
        body.add(previous, c);
        c.insets = new Insets(10, 0, 0, 0);
        body.add(neighbors, c);
        c.insets = new Insets(4, 0, 0, 0);
        body.add(synonyms, c);
        body.add(technicalTerms, c);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(start);
        c.insets = new Insets(16, 0, 0, 0);
        body.add(actions, c);
    }

    private void read(Path path) {
        int ticket = ++reading;
        valid = 0;
        updateStart();
        if (path == null) {
            show(Tone.NONE, " ", null);
            return;
        }
        show(Tone.NEUTRAL, "Testbestand wird gelesen …", null);
        runner.run(() -> reader.read(path), read -> {
            if (ticket == reading) {
                shown(read);
            }
        }, failure -> {
            if (ticket == reading) {
                show(Tone.PROBLEM, "Testbestand nicht lesbar (" + failure.getClass().getSimpleName() + ")", null);
            }
        });
    }

    private void shown(QualitySuite read) {
        valid = read.cases().size();
        List<QualitySuite.LineError> errors = read.errors();
        String questions = Texts.count(valid, "Frage", "Fragen") + " gültig";
        if (valid == 0) {
            questions = "Keine gültige Frage";
        }
        if (errors.isEmpty()) {
            show(valid == 0 ? Tone.PROBLEM : Tone.NEUTRAL, questions, null);
        } else {
            String lines = errors.stream().limit(SHOWN_LINES).map(e -> Integer.toString(e.line()))
                    .collect(Collectors.joining(", "));
            String more = errors.size() > SHOWN_LINES ? ", …" : "";
            String text = questions + " · " + Texts.count(errors.size(), "Zeile", "Zeilen") + " ausgelassen ("
                    + "Zeile " + lines + more + ")";
            String tip = "<html>" + errors.stream().map(e -> "Zeile " + e.line() + ": " + escape(e.message()))
                    .collect(Collectors.joining("<br>")) + "</html>";
            show(valid == 0 ? Tone.PROBLEM : Tone.NOTICE, text, tip);
        }
        updateStart();
    }

    private void start() {
        Path chosen = suite.path();
        if (chosen == null || valid == 0 || starting) {
            return;
        }
        starting = true;
        updateStart();
        runner.run(() -> starter.start(chosen, previous.path()), status -> {
            starting = false;
            close.run();
            started.accept(status);
        }, failure -> {
            starting = false;
            updateStart();
            show(Tone.PROBLEM, "Nicht gestartet (" + failure.getClass().getSimpleName() + ")", null);
        });
    }

    private void updateStart() {
        start.setEnabled(valid > 0 && !starting && suite.path() != null);
    }

    private void show(Tone tone, String text, String tooltip) {
        this.tone = tone;
        status.setText("  " + text);
        status.setToolTipText(tooltip);
        Color background = switch (tone) {
            case NOTICE -> Comic.NOTICE;
            case PROBLEM -> Comic.CHANGE_REMOVED;
            default -> null;
        };
        status.setOpaque(background != null);
        if (background != null) {
            status.setBackground(background);
            status.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(
                    tone == Tone.NOTICE ? Comic.YELLOW : Comic.RED, 1, true),
                    BorderFactory.createEmptyBorder(4, 4, 4, 6)));
        } else {
            status.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 7));
        }
        status.setForeground(tone == Tone.NEUTRAL ? Comic.MUTED : Comic.TEXT);
        body.revalidate();
        body.repaint();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
