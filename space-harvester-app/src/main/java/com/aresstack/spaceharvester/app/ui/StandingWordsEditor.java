package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.ui.controls.WordTable;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Platte „Wörter für den Stand“ unter Einstellungen › Modelle, bei der Aussagenanalyse (ADR 0032, FL6, S5): die Wörter,
 * an denen Labels und Titel eine Seite als überholt oder als Entwurf kennzeichnen
 * ({@code space-harvester.statements.standing.outdated-words} und {@code .draft-words}). Je Liste eine Tabelle mit
 * Häkchen „Aktiv“, dem Wort und ✕ für eigene Wörter; die Vorgabewörter stehen immer da und lassen sich nur abhaken.
 * In der Kopfzeile jeder Liste „Alle aus“ (schreibt „-“, keine Wörter) und „Vorgabe“ (zurücksetzen). Neue Wörter über
 * das Feld darunter (Enter). „Speichern“ schreibt nur den Block {@code statements.standing}.
 */
final class StandingWordsEditor {

    static final String HEADING = "Wörter für den Stand";
    static final String OUTDATED_TITLE = "Seite überholt";
    static final String DRAFT_TITLE = "Seite ist Entwurf";
    static final String HELP = "Eine Seite gilt als überholt bzw. als Entwurf, wenn eines dieser Wörter als ganzes Wort "
            + "in ihrem Titel, in einem ihrer Labels oder im Titel einer Elternseite steht; Groß- und Kleinschreibung "
            + "zählt nicht, Wortteile zählen nicht („Archivierung“ trifft „archiv“ nicht). Das zieht nur Aussagen "
            + "mit, die selbst nichts über die Zeit sagen; eine Entscheidung von Hand geht immer vor.\n\n"
            + "Die Vorgabewörter lassen sich abhaken, aber nicht löschen. „Alle aus“ heißt: keine Wörter (in der Datei "
            + "als „-“). „Vorgabe“ stellt die Liste zurück, eigene Wörter fallen dabei weg. Beides fragt vorher nach. "
            + "Gilt nach dem Speichern; ein offener Reiter „Stand“ liest dann neu.";

    private final AppContext context;
    final WordTable outdated = new WordTable(OUTDATED_TITLE, StandingSettings.DEFAULT_OUTDATED_WORDS);
    final WordTable draft = new WordTable(DRAFT_TITLE, StandingSettings.DEFAULT_DRAFT_WORDS);
    final ComposerButton save = Comic.primary("Speichern", "Wörter für den Stand in die Konfiguration schreiben");
    final Feedback feedback = new Feedback();
    final HelpButton help = new HelpButton(Dialogs.CLOSE_SIZE, HEADING, HELP);
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    StandingWordsEditor(AppContext context) {
        this.context = context;
        panel.setOpaque(false);
        JPanel lists = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        lists.setOpaque(false);
        lists.add(outdated.component());
        lists.add(draft.component());
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        // Überschrift selbst gesetzt, damit der ?-Knopf in ihrer Zeile steht (die Platte hat keine Kopfaktionen).
        JLabel title = new JLabel(HEADING);
        title.setForeground(Comic.INK);
        title.setFont(Comic.semiBold(14f));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.WEST);
        top.add(help, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(lists, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        save.addActionListener(e -> save());
        render();
    }

    JComponent component() {
        return panel;
    }


    /** Zeigt den gespeicherten Stand; noch nicht gespeicherte Änderungen bleiben stehen. */
    void render() {
        if (changed()) {
            return;
        }
        StandingSettings saved = saved();
        outdated.show(saved.outdatedWords());
        draft.show(saved.draftWords());
    }

    boolean changed() {
        StandingSettings saved = saved();
        return outdated.loaded() && (!ConfigFileEditor.sameWords(outdated.active(), saved.outdatedWords())
                || !ConfigFileEditor.sameWords(draft.active(), saved.draftWords()));
    }

    /** Die Einstellung, wie sie gespeichert würde. */
    StandingSettings settings() {
        return new StandingSettings(outdated.active(), draft.active());
    }

    void save() {
        if (!changed()) {
            feedback.info("Nichts geändert", null);
            return;
        }
        StandingSettings chosen = settings();
        Path file = context.configFile();
        save.setEnabled(false);
        feedback.working("Wörter werden gespeichert …");
        Background.execute(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withStanding(file, new ConfigFileEditor.Draft(text, null), chosen);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
        }, () -> context.reload(() -> {
            save.setEnabled(true);
            outdated.forget();
            render();
            feedback.success("Wörter für den Stand übernommen");
        }), error -> {
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    private StandingSettings saved() {
        AppSettings settings = context.settings();
        return settings == null ? StandingSettings.DEFAULT : settings.standing();
    }

    /** Kurzform für die Abschlussseite des Assistenten. */
    static String summary(StandingSettings standing) {
        return "überholt: " + count(standing.outdatedWords(), StandingSettings.DEFAULT_OUTDATED_WORDS)
                + " · Entwurf: " + count(standing.draftWords(), StandingSettings.DEFAULT_DRAFT_WORDS);
    }

    private static String count(List<String> words, List<String> fallback) {
        if (words.isEmpty()) {
            return "keine Wörter";
        }
        return ConfigFileEditor.sameWords(words, fallback) ? "Vorgabe (" + words.size() + " Wörter)"
                : Texts.count(words.size(), "Wort", "Wörter") + ": " + String.join(", ", words);
    }
}
