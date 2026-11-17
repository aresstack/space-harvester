package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.ui.controls.WordTable;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings.Kind;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Platte „Regeln für das Fachglossar“ unter Einstellungen › Modelle (ADR 0032, FL5b, Glossar 3): die Höchstzahl der
 * Vorschläge je Lauf ({@code space-harvester.glossary.rules.max-terms}) und die vier Wortlisten der Einordnung nach
 * Regeln ({@code glossary.rules.<art>.add} und {@code .exclude}). Je Liste eine {@link WordTable}: die Wörter der festen
 * Liste des Core stehen immer da und lassen sich nur abhaken (das wird {@code exclude}), eigene Wörter kommen dazu
 * ({@code add}). Ein Wort, das der Core nicht nimmt, bleibt draußen; der Grund steht in Worten unter dem Feld.
 * „Speichern“ schreibt nur den Block {@code glossary.rules}; ein offener Reiter „Fachglossar“ liest danach neu.
 */
final class GlossaryRulesEditor {

    static final String HEADING = "Regeln für das Fachglossar";
    static final String MAX_TERMS_LABEL = "Höchstens Vorschläge je Lauf";
    static final String HELP = "Die Regeln ordnen jeden Begriff ein, bevor eine KI ihn sieht: „Technik“ (im Glossar "
            + "nur mit dem Chip „Technik“ zu sehen), „Rolle“ (Rollen und Stellen) oder „fachlich“. Lieber unklar als "
            + "falsch: Technische Wörter gehen vor.\n\n"
            + "„Wortende“: Das Wort zählt auch am Ende eines zusammengeschriebenen Worts („Antragsdatenbank“ endet auf "
            + "„datenbank“); dafür braucht es mindestens " + GlossaryRuleSettings.MIN_COMPOUND_HEAD + " Buchstaben. "
            + "„Ganzes Wort“: Das Wort zählt nur allein oder nach Bindestrich und Leerzeichen („REST-API“, nicht "
            + "„Leerstelle“).\n\n"
            + "Die mitgelieferten Wörter lassen sich abhaken, aber nicht löschen; eigene Wörter kommen über das Feld "
            + "unter der Liste dazu (Enter). Erlaubt sind Buchstaben, Ziffern, Leerzeichen und Bindestrich.\n\n"
            + "„Höchstens Vorschläge je Lauf“ begrenzt, wie viele Begriffe die Regel je Lauf und Geltungsbereich "
            + "vorschlägt, die wichtigsten zuerst. Gilt nach dem Speichern; ein offener Reiter „Fachglossar“ liest "
            + "dann neu.";

    private final AppContext context;
    final Map<Kind, WordTable> tables = new EnumMap<>(Kind.class);
    final JSpinner maxTerms = new JSpinner(new SpinnerNumberModel(GlossaryRuleSettings.DEFAULT_MAX_TERMS, 1, 100_000,
            50));
    final ComposerButton save = Comic.primary("Speichern", "Regeln für das Fachglossar in die Konfiguration schreiben");
    final Feedback feedback = new Feedback();
    final HelpButton help = new HelpButton(Dialogs.CLOSE_SIZE, HEADING, HELP);
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));
    private boolean loaded;

    GlossaryRulesEditor(AppContext context) {
        this.context = context;
        panel.setOpaque(false);
        JPanel lists = new JPanel(new GridLayout(2, 2, 16, 12));
        lists.setOpaque(false);
        for (Kind kind : Kind.values()) {
            WordTable table = new WordTable(title(kind), defaults(kind))
                    .checkWith(GlossaryRuleSettings::normalize, word -> problem(kind, word));
            tables.put(kind, table);
            lists.add(table.component());
        }
        if (maxTerms.getEditor() instanceof JSpinner.DefaultEditor editor) {
            Comic.style(editor.getTextField());
            editor.getTextField().setColumns(6);
            editor.getTextField().setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 4));
        }
        maxTerms.setFont(Comic.regular(13f));
        maxTerms.setBorder(Comic.fieldBorder());
        maxTerms.getAccessibleContext().setAccessibleName(MAX_TERMS_LABEL);
        JLabel maxLabel = new JLabel(MAX_TERMS_LABEL);
        maxLabel.setForeground(Comic.INK);
        maxLabel.setFont(Comic.regular(13f));
        maxLabel.setLabelFor(maxTerms);
        JPanel maxRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        maxRow.setOpaque(false);
        maxRow.add(maxLabel);
        maxRow.add(maxTerms);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(maxRow, BorderLayout.NORTH);
        center.add(lists, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        // Überschrift selbst gesetzt, damit der ?-Knopf in ihrer Zeile steht (wie bei den Wörtern für den Stand).
        JLabel title = new JLabel(HEADING);
        title.setForeground(Comic.INK);
        title.setFont(Comic.semiBold(14f));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.WEST);
        top.add(help, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        save.addActionListener(e -> save());
        render();
    }

    JComponent component() {
        return panel;
    }

    /** Überschrift einer Liste in Worten. */
    static String title(Kind kind) {
        return switch (kind) {
            case TECHNICAL_HEADS -> "Technik: Wortende";
            case TECHNICAL_WORDS -> "Technik: ganzes Wort";
            case ROLE_HEADS -> "Rolle: Wortende";
            case ROLE_WORDS -> "Rolle: ganzes Wort";
        };
    }

    /** Die feste Liste des Core, alphabetisch. */
    static List<String> defaults(Kind kind) {
        return GlossaryDerivation.Rules.DEFAULT.words(kind).stream().sorted().toList();
    }

    /**
     * Warum der Core ein neues Wort nicht nimmt, in Worten; {@code null}, wenn er es nimmt. Ein zu kurzes Wortende
     * verweist auf die Liste „ganzes Wort“ derselben Gruppe.
     */
    static String problem(Kind kind, String word) {
        List<String> problems = new ArrayList<>();
        GlossaryRuleSettings.accepted(kind, true, List.of(word), problems::add);
        if (problems.isEmpty()) {
            return null;
        }
        if (kind.compound() && word.codePointCount(0, word.length()) < GlossaryRuleSettings.MIN_COMPOUND_HEAD) {
            Kind whole = kind == Kind.TECHNICAL_HEADS ? Kind.TECHNICAL_WORDS : Kind.ROLE_WORDS;
            return "Unter " + GlossaryRuleSettings.MIN_COMPOUND_HEAD + " Buchstaben zählt ein Wort nur als ganzes "
                    + "Wort: bitte unter „" + title(whole) + "“ eintragen.";
        }
        return problems.get(0);
    }

    /** Zeigt den gespeicherten Stand; noch nicht gespeicherte Änderungen bleiben stehen. */
    void render() {
        if (changed()) {
            return;
        }
        GlossaryRuleSettings saved = saved();
        for (Kind kind : Kind.values()) {
            List<String> active = new ArrayList<>(defaults(kind));
            active.removeAll(saved.excluded(kind));
            saved.added(kind).stream().filter(word -> !active.contains(word)).forEach(active::add);
            tables.get(kind).show(active);
        }
        maxTerms.setValue(saved.maxTerms());
        loaded = true;
    }

    boolean changed() {
        return loaded && !same(settings(), saved());
    }

    /** Gleiche Höchstzahl und je Art dieselben Wörter, Reihenfolge egal. */
    static boolean same(GlossaryRuleSettings one, GlossaryRuleSettings other) {
        if (one.maxTerms() != other.maxTerms()) {
            return false;
        }
        for (Kind kind : Kind.values()) {
            if (!ConfigFileEditor.sameWords(one.added(kind), other.added(kind))
                    || !ConfigFileEditor.sameWords(one.excluded(kind), other.excluded(kind))) {
                return false;
            }
        }
        return true;
    }

    /** Die Einstellung, wie sie gespeichert würde. */
    GlossaryRuleSettings settings() {
        GlossaryRuleSettings result = GlossaryRuleSettings.DEFAULT.withMaxTerms(((Number) maxTerms.getValue())
                .intValue());
        for (Kind kind : Kind.values()) {
            WordTable table = tables.get(kind);
            result = result.with(kind, table.added(), table.excluded());
        }
        return result;
    }

    void save() {
        if (!changed()) {
            feedback.info("Nichts geändert", null);
            return;
        }
        GlossaryRuleSettings chosen = settings();
        Path file = context.configFile();
        save.setEnabled(false);
        feedback.working("Regeln werden gespeichert …");
        Background.execute(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withGlossaryRules(file, new ConfigFileEditor.Draft(text, null),
                    chosen);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
        }, () -> context.reload(() -> {
            save.setEnabled(true);
            loaded = false;
            render();
            feedback.success("Regeln für das Fachglossar übernommen");
        }), error -> {
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    private GlossaryRuleSettings saved() {
        AppSettings settings = context.settings();
        return settings == null ? GlossaryRuleSettings.DEFAULT : settings.terms().glossaryRules();
    }

    /** Kurzform für die Abschlussseite des Assistenten. */
    static String summary(GlossaryRuleSettings rules) {
        List<String> parts = new ArrayList<>();
        parts.add("höchstens " + rules.maxTerms() + " Vorschläge je Lauf");
        int added = 0;
        int excluded = 0;
        for (Kind kind : Kind.values()) {
            added += rules.added(kind).size();
            excluded += rules.excluded(kind).size();
        }
        if (added == 0 && excluded == 0) {
            parts.add("Wortlisten wie mitgeliefert");
        } else {
            if (added > 0) {
                parts.add(Texts.count(added, "eigenes Wort", "eigene Wörter"));
            }
            if (excluded > 0) {
                parts.add(Texts.count(excluded, "Wort", "Wörter") + " abgehakt");
            }
        }
        return String.join(" · ", parts);
    }
}
