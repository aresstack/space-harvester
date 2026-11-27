package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.project.ProjectTarget;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import javax.swing.JButton;
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
 * Das Ziel eines Projekts festlegen (ADR 0043, Nachtrag F6; Angelo, 27.11.2026): „Lokaler Ordner oder Datei“
 * (vorgewählt, mit Dateiauswahl oder Handeingabe) oder „Website“. Die Art wird mit dem Wert gespeichert.
 */
final class ProjectTargetDialog {

    static final String TITLE = "Ziel des Projekts";
    static final String PATH = "Lokaler Ordner oder Datei";
    static final String WEBSITE = "Website";
    static final String BROWSE = "Durchsuchen …";
    static final String INVALID_WEBSITE = "Nur Adressen mit http:// oder https://";

    private ProjectTargetDialog() {
    }

    /**
     * Fragt das Ziel ab.
     *
     * @param current das bisherige Ziel oder {@code null}
     * @return das neue Ziel oder {@code null}, wenn abgebrochen wurde
     */
    static ProjectTarget ask(Component parent, String projectName, ProjectTarget current) {
        return ask(parent, projectName, current, ProjectTargetDialog::choose);
    }

    static ProjectTarget ask(Component parent, String projectName, ProjectTarget current,
                             Function<Component, File> chooser) {
        ProjectTarget[] result = {null};
        JDialog dialog = Dialogs.dialog(parent, TITLE);
        Body body = new Body(current, chooser);
        ComposerButton ok = Comic.primary("Übernehmen", null);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        Runnable check = () -> ok.setEnabled(body.target() != null);
        body.onChange(check);
        check.run();
        ok.addActionListener(e -> {
            result[0] = body.target();
            if (result[0] != null) {
                dialog.dispose();
            }
        });
        body.field.addActionListener(e -> ok.doClick());
        cancel.addActionListener(e -> dialog.dispose());
        JPanel stack = new JPanel(new BorderLayout(0, 8));
        stack.setOpaque(false);
        stack.add(Comic.note("Wohin führt ein Klick auf „" + projectName + "“ unten in der Ecke?"),
                BorderLayout.NORTH);
        stack.add(body, BorderLayout.CENTER);
        SwingUtilities.invokeLater(body.field::requestFocusInWindow);
        Dialogs.show(dialog, TITLE, stack, cancel, ok);
        return result[0];
    }

    /** Der Inhalt: Art, Eingabe mit „Durchsuchen …“ (nur bei Ordner/Datei) und ein Hinweis bei ungültiger Website. */
    static final class Body extends JPanel {

        final SegmentedToggle kind = new SegmentedToggle().segment(PATH).segment(WEBSITE);
        final JTextField field = new JTextField(32);
        final JButton browse = InputFrame.flatButton(BROWSE, "Ordner oder Datei wählen");
        final JLabel hint = Comic.muted(" ");
        private Runnable changed = () -> { };

        Body(ProjectTarget current, Function<Component, File> chooser) {
            super(new GridLayout(0, 1, 0, 6));
            setOpaque(false);
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
            field.getDocument().addDocumentListener(new DocumentListener() {
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
            });
            add(kind);
            add(frame);
            add(hint);
            update();
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
            changed.run();
        }
    }

    private static File choose(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(TITLE);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }
}
