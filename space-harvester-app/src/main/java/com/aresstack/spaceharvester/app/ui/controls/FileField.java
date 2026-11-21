package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Ein Dateifeld wie {@link FolderField}: der Pfad als Text, „Durchsuchen …“ im Feld, wahlweise ein ✕ zum Leeren
 * ({@link #clearable}). Enter übernimmt einen getippten Pfad, wenn es die Datei gibt; der Dialog einen gewählten.
 * Beides und das Leeren meldet {@link #onChange} ({@code null} = leer), auch wenn dieselbe Datei erneut gewählt wird. Wer das Feld verlässt, ohne zu übernehmen, sieht
 * wieder die geltende Datei.
 */
public final class FileField extends InputFrame {

    public static final String BROWSE_LABEL = FolderField.BROWSE_LABEL;

    private final JTextField text;
    private final JButton browse = flatButton(BROWSE_LABEL, "Eine Datei im Dialog wählen");
    private JButton clear;
    private Path current;
    private Path startFolder;
    private Consumer<Path> changed = path -> { };
    private Consumer<String> invalid = input -> { };
    private Function<Path, Optional<Path>> chooser = this::chooseWithDialog;
    private String dialogTitle = "Datei wählen";
    private FileNameExtensionFilter filter;

    public FileField(JTextField text) {
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
        browse.addActionListener(event -> chooser.apply(current != null ? current : startFolder)
                .ifPresent(this::commit));
    }

    /** Hängt ein ✕ ins Feld, das die Datei leert; sichtbar nur mit Datei. */
    public FileField clearable(String tooltip) {
        if (clear == null) {
            clear = flatButton("✕", tooltip);
            clear.addActionListener(event -> commit(null));
            clear.setVisible(current != null);
            addButton(clear);
        }
        return this;
    }

    /** Zeigt die geltende Datei; meldet nichts. */
    public void path(Path path) {
        current = path;
        show(path);
    }

    /** Die geltende Datei, {@code null} ohne. */
    public Path path() {
        return current;
    }

    /** Ordner, in dem der Dialog beginnt, solange keine Datei gewählt ist. */
    public void startFolder(Path folder) {
        startFolder = folder;
    }

    /** Nur Dateien mit diesen Endungen im Dialog (getippte Pfade bleiben frei). */
    public void extensions(String description, String... extensions) {
        filter = new FileNameExtensionFilter(description, extensions);
    }

    public void onChange(Consumer<Path> listener) {
        changed = Objects.requireNonNull(listener, "listener");
    }

    /** Bekommt eine Eingabe, die kein Pfad oder keine vorhandene Datei ist. */
    public void onInvalid(Consumer<String> listener) {
        invalid = Objects.requireNonNull(listener, "listener");
    }

    public void dialogTitle(String title) {
        dialogTitle = Objects.requireNonNull(title, "title");
    }

    /** Ersetzt den Dateidialog, für Tests: bekommt die geltende Datei oder den Startordner, liefert die gewählte. */
    public void chooser(Function<Path, Optional<Path>> replacement) {
        chooser = Objects.requireNonNull(replacement, "replacement");
    }

    public JButton browseButton() {
        return browse;
    }

    /** Das ✕, {@code null} ohne {@link #clearable}. */
    public JButton clearButton() {
        return clear;
    }

    public JTextField textField() {
        return text;
    }

    private void commitTyped() {
        String typed = text.getText().strip();
        if (typed.isEmpty()) {
            commit(null);
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
        if (!Files.isRegularFile(path)) {
            show(current);
            invalid.accept(typed);
            return;
        }
        commit(path);
    }

    private void commit(Path path) {
        // Dieselbe Datei erneut gewählt oder bestätigt meldet trotzdem: Sie kann sich inzwischen geändert haben.
        if (path == null && current == null) {
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
        if (clear != null) {
            clear.setVisible(path != null);
            revalidate();
        }
    }

    private Optional<Path> chooseWithDialog(Path start) {
        java.io.File folder = null;
        if (start != null) {
            Path dir = Files.isDirectory(start) ? start : start.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                folder = dir.toFile();
            }
        }
        JFileChooser dialog = new JFileChooser(folder);
        dialog.setFileSelectionMode(JFileChooser.FILES_ONLY);
        dialog.setDialogTitle(dialogTitle);
        if (filter != null) {
            dialog.setFileFilter(filter);
        }
        if (dialog.showDialog(this, "Übernehmen") != JFileChooser.APPROVE_OPTION || dialog.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(dialog.getSelectedFile().toPath().toAbsolutePath().normalize());
    }
}
