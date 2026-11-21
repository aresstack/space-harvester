package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JTextField;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ein Ordnerfeld: der Pfad als Text, „Durchsuchen …“ im Feld (Angelos Vorgabe: Knöpfe zu einem Feld sitzen im Feld).
 * Enter übernimmt einen getippten Pfad, der Dialog einen gewählten; beides meldet {@link #onChange}. Wer das Feld
 * verlässt, ohne zu übernehmen, sieht wieder den geltenden Ordner. Weitere Knöpfe kommen mit {@link #addButton}.
 */
public final class FolderField extends InputFrame {

    public static final String BROWSE_LABEL = "Durchsuchen …";

    private final JTextField text;
    private final JButton browse = flatButton(BROWSE_LABEL, "Einen Ordner im Dialog wählen");
    private Path current;
    private Consumer<Path> changed = path -> { };
    private Consumer<String> invalid = input -> { };
    private Function<Path, Optional<Path>> chooser = this::chooseWithDialog;
    private String dialogTitle = "Ordner wählen";

    /**
     * @param text das Textfeld, schon im Stil der übrigen Felder
     */
    public FolderField(JTextField text) {
        super(text);
        this.text = Objects.requireNonNull(text, "text");
        addButton(browse);
        text.addActionListener(event -> commitTyped());
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                if (!event.isTemporary()) {
                    show(current);
                }
            }
        });
        browse.addActionListener(event -> chooser.apply(current).ifPresent(this::commit));
    }

    /** Zeigt den geltenden Ordner; meldet nichts. */
    public void path(Path path) {
        // Wer gerade tippt, behält seine Eingabe; sonst folgt das Feld dem geltenden Ordner.
        boolean typing = text.isFocusOwner() && !text.getText().equals(current == null ? "" : current.toString());
        current = path;
        if (!typing) {
            show(path);
        }
    }

    /** Sperrt Feld und „Durchsuchen …“, etwa ohne geladene Einstellungen. */
    public void editable(boolean editable) {
        text.setEnabled(editable);
        browse.setEnabled(editable);
    }

    /** Der geltende Ordner, {@code null} ohne. */
    public Path path() {
        return current;
    }

    /** Bekommt den neuen Ordner, wenn der Benutzer einen anderen getippt oder gewählt hat. */
    public void onChange(Consumer<Path> listener) {
        changed = Objects.requireNonNull(listener, "listener");
    }

    /** Bekommt eine Eingabe, die kein Pfad oder eine vorhandene Datei ist; das Feld zeigt danach wieder den geltenden Ordner. */
    public void onInvalid(Consumer<String> listener) {
        invalid = Objects.requireNonNull(listener, "listener");
    }

    /** Titel des Ordnerdialogs. */
    public void dialogTitle(String title) {
        dialogTitle = Objects.requireNonNull(title, "title");
    }

    /** Ersetzt den Ordnerdialog, für Tests: bekommt den geltenden Ordner, liefert den gewählten. */
    public void chooser(Function<Path, Optional<Path>> replacement) {
        chooser = Objects.requireNonNull(replacement, "replacement");
    }

    public JButton browseButton() {
        return browse;
    }

    public JTextField textField() {
        return text;
    }

    private void commitTyped() {
        String typed = text.getText().strip();
        if (typed.isEmpty()) {
            show(current);
            return;
        }
        Path path;
        try {
            path = Path.of(typed).toAbsolutePath().normalize();
        } catch (InvalidPathException | java.io.IOError e) {
            show(current);
            invalid.accept(typed);
            return;
        }
        // Eine vorhandene Datei ist kein Ordner; ein Ordner, den es noch nicht gibt, darf es sein (er wird angelegt).
        if (Files.exists(path) && !Files.isDirectory(path)) {
            show(current);
            invalid.accept(typed);
            return;
        }
        commit(path);
    }

    private void commit(Path path) {
        if (path.equals(current)) {
            show(current);
            return;
        }
        current = path;
        show(path);
        changed.accept(path);
    }

    private void show(Path path) {
        String value = path == null ? "" : path.toString();
        if (!value.equals(text.getText())) {
            text.setText(value);
            text.setCaretPosition(0);
        }
        text.setToolTipText(value.isEmpty() ? null : value);
    }

    private Optional<Path> chooseWithDialog(Path start) {
        JFileChooser dialog = new JFileChooser(start != null && Files.isDirectory(start) ? start.toFile() : null);
        dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        dialog.setDialogTitle(dialogTitle);
        if (dialog.showDialog(this, "Übernehmen") != JFileChooser.APPROVE_OPTION || dialog.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(dialog.getSelectedFile().toPath().toAbsolutePath().normalize());
    }
}
