package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.project.Project;
import com.aresstack.spaceharvester.app.project.ProjectOutput;
import com.aresstack.spaceharvester.app.project.ProjectTarget;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.File;
import java.util.function.Function;

/**
 * Die Projekteinstellungen (Angelo, 27.11.2026): das Ziel eines Projekts (ADR 0043, Nachtrag F6) – „Lokaler Ordner oder
 * Datei“ (vorgewählt, mit Dateiauswahl oder Handeingabe) oder „Website“, die Art wird mit dem Wert gespeichert – und
 * beim Ordner der Haken „Alles hier speichern“ mit Unterordner (Vorgabe „Downloads“, leer = direkt im Projektordner):
 * Dann landen Exporte und Downloads dort statt im Ausgabeordner der Einstellungen ({@link ProjectOutput}).
 */
final class ProjectTargetDialog {

    static final String TITLE = "Projekteinstellungen";
    static final String PATH = "Lokaler Ordner oder Datei";
    static final String WEBSITE = "Website";
    static final String BROWSE = "Durchsuchen …";
    static final String INVALID_WEBSITE = "Nur Adressen mit http:// oder https://";
    static final String SAVE_HERE = "Alles hier speichern";
    static final String SUBFOLDER = "Unterordner";
    static final String INVALID_SUBFOLDER = "Nur ein Unterordner im Projektordner, ohne Laufwerk und ohne „..“";
    static final String SAVE_HINT = "Exporte und Downloads landen im Projektordner, im Unterordner; ist er leer, "
            + "direkt im Projektordner. Fehlende Ordner werden angelegt. Ohne Haken gilt der Ausgabeordner unter "
            + "Einstellungen › Allgemein.";

    private ProjectTargetDialog() {
    }

    /**
     * Fragt die Einstellungen des Projekts ab.
     *
     * @return das Projekt mit neuem Ziel und Speicherort oder {@code null}, wenn abgebrochen wurde
     */
    static Project ask(Component parent, Project project) {
        return ask(parent, project, ProjectTargetDialog::choose);
    }

    static Project ask(Component parent, Project project, Function<Component, File> chooser) {
        Project[] result = {null};
        JDialog dialog = Dialogs.dialog(parent, TITLE);
        Body body = new Body(project.target(), project.output(), chooser);
        ComposerButton ok = Comic.primary("Übernehmen", null);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        Runnable check = () -> ok.setEnabled(body.complete());
        body.onChange(check);
        check.run();
        ok.addActionListener(e -> {
            if (body.complete()) {
                result[0] = project.withTarget(body.target()).withOutput(body.output());
                dialog.dispose();
            }
        });
        body.field.addActionListener(e -> ok.doClick());
        body.subfolder.addActionListener(e -> ok.doClick());
        cancel.addActionListener(e -> dialog.dispose());
        JPanel stack = new JPanel(new BorderLayout(0, 8));
        stack.setOpaque(false);
        stack.add(Comic.note("Wohin führt ein Klick auf „" + project.name() + "“ unten in der Ecke?"),
                BorderLayout.NORTH);
        stack.add(body, BorderLayout.CENTER);
        SwingUtilities.invokeLater(body.field::requestFocusInWindow);
        Dialogs.show(dialog, TITLE, stack, cancel, ok);
        return result[0];
    }

    /**
     * Der Inhalt: Art, Eingabe mit „Durchsuchen …“ (nur bei Ordner/Datei), ein Hinweis bei ungültiger Website und
     * (nur bei Ordner/Datei) „Alles hier speichern“ mit dem Unterordner.
     */
    static final class Body extends JPanel {

        final SegmentedToggle kind = new SegmentedToggle().segment(PATH).segment(WEBSITE);
        final JTextField field = new JTextField(32);
        final JButton browse = InputFrame.flatButton(BROWSE, "Ordner oder Datei wählen");
        final JLabel hint = Comic.muted(" ");
        final JCheckBox saveHere = Comic.checkBox(SAVE_HERE);
        final JTextField subfolder = new JTextField(20);
        final JLabel subfolderHint = Comic.muted(" ");
        private final JPanel savePanel = new JPanel(new GridLayout(0, 1, 0, 6));
        private Runnable changed = () -> { };

        Body(ProjectTarget current, Function<Component, File> chooser) {
            this(current, ProjectOutput.standard(), chooser);
        }

        Body(ProjectTarget current, ProjectOutput output, Function<Component, File> chooser) {
            super(new BorderLayout(0, 6));
            setOpaque(false);
            ProjectOutput shown = output == null ? ProjectOutput.standard() : output;
            saveHere.setSelected(shown.saveHere());
            saveHere.setToolTipText(SAVE_HINT);
            Comic.style(subfolder);
            subfolder.setText(shown.subfolder());
            subfolder.setToolTipText(SAVE_HINT);
            Comic.style(field);
            InputFrame frame = new InputFrame(field);
            frame.addButton(browse);
            if (current != null) {
                kind.select(current.isWebsite() ? WEBSITE : PATH);
                field.setText(current.value());
            }
            browse.addActionListener(e -> {
                File file = chooser.apply(this);
                if (file != null) {
                    field.setText(file.getAbsolutePath());
                }
            });
            kind.onSelect(name -> update());
            DocumentListener typing = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    update();
                }
            };
            field.getDocument().addDocumentListener(typing);
            subfolder.getDocument().addDocumentListener(typing);
            saveHere.addActionListener(e -> update());
            JPanel targetPanel = new JPanel(new GridLayout(0, 1, 0, 6));
            targetPanel.setOpaque(false);
            targetPanel.add(kind);
            targetPanel.add(frame);
            targetPanel.add(hint);
            JPanel subfolderRow = new JPanel(new BorderLayout(8, 0));
            subfolderRow.setOpaque(false);
            subfolderRow.add(new JLabel(SUBFOLDER), BorderLayout.WEST);
            subfolderRow.add(new InputFrame(subfolder), BorderLayout.CENTER);
            savePanel.setOpaque(false);
            savePanel.add(saveHere);
            savePanel.add(subfolderRow);
            savePanel.add(subfolderHint);
            add(targetPanel, BorderLayout.NORTH);
            add(savePanel, BorderLayout.CENTER);
            update();
        }

        /** Der Speicherort, wie eingegeben. */
        ProjectOutput output() {
            return new ProjectOutput(saveHere.isSelected(), subfolder.getText());
        }

        /** Ob „Übernehmen“ geht: gültiges Ziel und, mit Haken bei Ordner/Datei, ein gültiger Unterordner. */
        boolean complete() {
            boolean subfolderNeeded = selectedKind() == ProjectTarget.Kind.PATH && saveHere.isSelected();
            return target() != null && (!subfolderNeeded || ProjectOutput.valid(subfolder.getText()));
        }

        void onChange(Runnable listener) {
            changed = listener;
        }

        ProjectTarget.Kind selectedKind() {
            return WEBSITE.equals(kind.selected()) ? ProjectTarget.Kind.WEBSITE : ProjectTarget.Kind.PATH;
        }

        ProjectTarget target() {
            return ProjectTarget.of(selectedKind(), field.getText());
        }

        private void update() {
            boolean website = selectedKind() == ProjectTarget.Kind.WEBSITE;
            browse.setVisible(!website);
            hint.setText(website && !field.getText().isBlank() && target() == null ? INVALID_WEBSITE : " ");
            savePanel.setVisible(!website);
            subfolder.setEnabled(saveHere.isSelected());
            subfolderHint.setText(saveHere.isSelected() && !ProjectOutput.valid(subfolder.getText())
                    ? INVALID_SUBFOLDER : " ");
            changed.run();
        }
    }

    private static File choose(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Projektordner wählen");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }
}
