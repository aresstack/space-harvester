package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Platte „Dateizugriff“ unter Einstellungen › Allgemein, wie im Enterprise AI Client: die Ordner, die Space Harvester
 * lesen darf ({@code space-harvester.local-files.roots}), mit dem Ordnerdialog gewählt oder eingetippt (UNC-Pfade), je
 * Zeile entfernbar, und der Haken „Alle lokalen Dateien und Netzlaufwerke freigeben“
 * ({@code space-harvester.local-files.allow-all}), der erst nach einer zweiten, ausdrücklichen Bestätigung angeht.
 * Abschalten fragt nicht nach. Eine Freigabe liest nichts ein; „Speichern“ schreibt die Datei und übernimmt sie ohne
 * Neustart der Quellen.
 */
final class FileAccessEditor {

    static final String HEADING = "Dateizugriff";
    static final String NOTE = "Nur freigegebene Ordner kann Space Harvester lesen. Gemappte Laufwerke (P:\\Projekt) "
            + "und Netzpfade (\\\\server\\freigabe\\ordner) zählen wie lokale Ordner; Verknüpfungen aus einem Ordner "
            + "hinaus erweitern die Freigabe nicht. Der Ordner einer Ordner-Quelle ist immer freigegeben. Welche Ordner "
            + "eingelesen werden, legst du weiterhin unter Wissensquellen fest. Gilt nach dem Speichern ohne Neustart "
            + "(local-files.roots).";
    static final String EMPTY_LABEL = "Noch kein Ordner freigegeben.";
    static final String PATH_LABEL = "Ordner oder Netzpfad";
    static final String PATH_HELP = "Pfad eintragen und „Freigeben“ drücken, z. B. \\\\server\\team\\wissen";
    static final String ADD_LABEL = "Freigeben";
    static final String CHOOSE_LABEL = "Ordner wählen …";
    static final String REMOVE_LABEL = "Entfernen";
    static final String ALLOW_ALL_LABEL = "Alle lokalen Dateien und Netzlaufwerke freigeben";
    static final String ALLOW_ALL_HELP = "Standardmäßig aus. Startet kein Einlesen (local-files.allow-all).";
    static final String CONFIRM_TITLE = "Alle Dateizugriffe freigeben";
    static final String CONFIRM_QUESTION = "Wirklich alle Dateien freigeben?";
    static final String CONFIRM_TEXT = "Dadurch darf Space Harvester auf alle Dateien zugreifen, die dein "
            + "Windows-Benutzerkonto lesen kann. Das kann persönliche, vertrauliche und dienstliche Dateien sowie "
            + "erreichbare Netzlaufwerke umfassen. Die Freigabe betrifft den Dateizugriff; ein Einlesen aller "
            + "Laufwerke wird dadurch nicht automatisch gestartet.";
    static final String CONFIRM_LABEL = "Alle Dateizugriffe freigeben";

    /** Die zweite Bestätigung, bevor alles freigegeben wird; Tests ersetzen sie. */
    interface Confirmation {
        boolean confirm(Component parent);
    }

    private final AppContext context;
    private final Confirmation confirmation;
    private final List<String> roots = new ArrayList<>();
    private final JPanel list = new JPanel();
    final PlaceholderField path = new PlaceholderField(24, "D:\\Wissen oder \\\\server\\team\\wissen");
    final ComposerButton add = Comic.button(ADD_LABEL, "Den eingetragenen Ordner freigeben");
    final ComposerButton choose = Comic.button(CHOOSE_LABEL, "Einen Ordner im Dialog wählen und freigeben");
    final JCheckBox allowAll = new JCheckBox(ALLOW_ALL_LABEL);
    final ComposerButton save = Comic.primary("Speichern", "Dateizugriff in die Konfiguration schreiben");
    final Feedback feedback = new Feedback();
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    FileAccessEditor(AppContext context) {
        this(context, parent -> Dialogs.confirm(parent, CONFIRM_TITLE, CONFIRM_QUESTION + " " + CONFIRM_TEXT,
                CONFIRM_LABEL));
    }

    FileAccessEditor(AppContext context, Confirmation confirmation) {
        this.context = context;
        this.confirmation = confirmation;
        panel.setOpaque(false);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        allowAll.setOpaque(false);
        allowAll.setFont(Comic.regular(13f));
        allowAll.setForeground(Comic.INK);
        allowAll.setToolTipText(ALLOW_ALL_HELP);
        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.setOpaque(false);
        pathRow.add(path, BorderLayout.CENTER);
        pathRow.add(Comic.row(add, choose), BorderLayout.EAST);
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (JComponent part : List.of(Comic.note(NOTE), list, Comic.caption(PATH_LABEL), pathRow,
                Comic.muted(PATH_HELP), allowAll, Comic.muted(ALLOW_ALL_HELP))) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(part);
            form.add(javax.swing.Box.createVerticalStrut(6));
        }
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        panel.add(form, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        add.addActionListener(event -> addTyped());
        path.addActionListener(event -> addTyped());
        choose.addActionListener(event -> chooseFolder());
        allowAll.addActionListener(event -> allowAllToggled());
        save.addActionListener(event -> save());
        render();
    }

    JComponent component() {
        return panel;
    }

    /** Zeigt den gespeicherten Stand; noch nicht gespeicherte Eingaben bleiben stehen. */
    void render() {
        if (changed()) {
            return;
        }
        roots.clear();
        roots.addAll(savedRoots());
        allowAll.setSelected(savedAllowAll());
        refresh();
    }

    /** Ob seit dem letzten Speichern etwas freigegeben, entfernt oder umgeschaltet wurde. */
    boolean changed() {
        return !roots.equals(savedRoots()) || allowAll.isSelected() != savedAllowAll();
    }

    /** Gibt einen Ordner frei, außer er ist leer oder schon freigegeben. */
    void grant(String folder) {
        String trimmed = folder == null ? "" : folder.trim();
        if (!trimmed.isEmpty() && !roots.contains(trimmed)) {
            roots.add(trimmed);
            refresh();
        }
    }

    void revoke(String folder) {
        if (roots.remove(folder)) {
            refresh();
        }
    }

    /** Die freigegebenen Ordner, wie sie gespeichert würden (für Tests). */
    List<String> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** Die Zeilen der Liste (für Tests). */
    JPanel list() {
        return list;
    }

    private List<String> savedRoots() {
        AppSettings settings = context.settings();
        return settings == null ? List.of() : settings.localFiles().roots().stream().map(Path::toString).toList();
    }

    private boolean savedAllowAll() {
        AppSettings settings = context.settings();
        return settings != null && settings.localFiles().allowAll();
    }

    private void addTyped() {
        grant(path.getText());
        path.setText("");
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Ordner freigeben");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showDialog(panel, ADD_LABEL) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            grant(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void allowAllToggled() {
        if (allowAll.isSelected() && !confirmation.confirm(panel)) {
            allowAll.setSelected(false);
        }
    }

    /** Schreibt beide Werte wie die übrigen Einstellungen in die Datei und übernimmt sie mit „Neu laden“. */
    void save() {
        if (!changed()) {
            feedback.info("Nichts geändert", null);
            return;
        }
        if (!Dialogs.restartAllowed(panel, context)) {
            return;
        }
        boolean all = allowAll.isSelected();
        List<String> granted = List.copyOf(roots);
        Path file = context.configFile();
        save.setEnabled(false);
        feedback.working("Dateizugriff wird gespeichert …");
        Background.execute(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withFileAccess(file, new ConfigFileEditor.Draft(text, null), all,
                    granted);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
        }, () -> context.reload(() -> {
            save.setEnabled(true);
            render();
            if (context.runtime() != null && !context.runtime().lastRejection().isEmpty()) {
                feedback.warning("Nicht übernommen", "Der Core hat die Änderung abgelehnt; die bisherige Freigabe "
                        + "gilt weiter. Details unter Konfiguration.");
            } else {
                feedback.success("Dateizugriff übernommen");
            }
        }), error -> {
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    private void refresh() {
        list.removeAll();
        if (roots.isEmpty()) {
            JLabel empty = Comic.muted(EMPTY_LABEL);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(empty);
        }
        for (String root : roots) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel label = Comic.text(root);
            label.setToolTipText(root);
            ComposerButton remove = Comic.button(REMOVE_LABEL, "Freigabe dieses Ordners aufheben");
            remove.addActionListener(event -> revoke(root));
            row.add(label, BorderLayout.CENTER);
            row.add(remove, BorderLayout.EAST);
            list.add(row);
        }
        list.revalidate();
        list.repaint();
    }
}
