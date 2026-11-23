package com.aresstack.spaceharvester.deck.files.local;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentHash;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import com.aresstack.spaceharvester.deck.editor.TextDecoding;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Optional;

/**
 * Ablage des gemeinsamen Editors für lokale Dateien. Speichern prüft zuerst den Fingerabdruck (wie
 * {@code writeIfUnchanged} in MainframeMate) und schreibt dann über eine Nachbardatei, die an die Stelle der alten
 * tritt – ein Abbruch mitten im Schreiben hinterlässt so nie eine halbe Datei.
 */
public final class LocalDocumentStore implements DocumentStore {

    private final FileSystem fileSystem;

    public LocalDocumentStore() {
        this(FileSystems.getDefault());
    }

    public LocalDocumentStore(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public EditorDocument load(String location) throws DocumentException {
        Path path = fileSystem.getPath(location);
        if (Files.isDirectory(path)) {
            throw new DocumentException("Das ist ein Ordner.");
        }
        byte[] bytes = read(path);
        Optional<String> problem = TextDecoding.problem(bytes);
        if (problem.isPresent()) {
            throw new DocumentException(problem.get());
        }
        TextDecoding.Decoded decoded = TextDecoding.decode(bytes);
        return new EditorDocument(path.toString(), name(path), decoded.text(), decoded.charset(),
                decoded.lineEnding(), DocumentHash.of(bytes));
    }

    @Override
    public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
        // Bei einer Verknüpfung wird das Ziel gelesen, geprüft und ersetzt, nie die Verknüpfung selbst.
        Path path = target(fileSystem.getPath(loaded.location()));
        if (!overwrite) {
            if (!Files.exists(path)) {
                return new SaveResult.Conflict("Die Datei wurde inzwischen gelöscht oder umbenannt.");
            }
            if (!DocumentHash.of(read(path)).equals(loaded.hash())) {
                return new SaveResult.Conflict("Die Datei wurde seit dem Öffnen geändert.");
            }
        }
        byte[] bytes = TextDecoding.encode(text, loaded.charset(), loaded.lineEnding());
        write(path, bytes);
        return new SaveResult.Saved(new EditorDocument(loaded.location(), loaded.name(), text, loaded.charset(),
                loaded.lineEnding(), DocumentHash.of(bytes)));
    }

    @Override
    public boolean canSaveAs() {
        return true;
    }

    @Override
    public EditorDocument saveAs(EditorDocument loaded, String text, String target) throws DocumentException {
        Path path = target(fileSystem.getPath(target));
        if (Files.isDirectory(path)) {
            throw new DocumentException("Das ist ein Ordner.");
        }
        byte[] bytes = TextDecoding.encode(text, loaded.charset(), loaded.lineEnding());
        write(path, bytes);
        return new EditorDocument(path.toString(), name(path), text, loaded.charset(), loaded.lineEnding(),
                DocumentHash.of(bytes));
    }

    private static Path target(Path path) throws DocumentException {
        if (!Files.isSymbolicLink(path)) {
            return path;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new DocumentException("Das Ziel der Verknüpfung fehlt: " + LocalFiles.reason(e), e);
        }
    }

    private static byte[] read(Path path) throws DocumentException {
        try {
            if (Files.size(path) > TextDecoding.MAX_BYTES) {
                throw new DocumentException("Die Datei ist größer als "
                        + (TextDecoding.MAX_BYTES / (1024 * 1024)) + " MB.");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DocumentException(LocalFiles.reason(e), e);
        }
    }

    private static void write(Path path, byte[] bytes) throws DocumentException {
        Path folder = path.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(folder, "." + name(path) + ".", ".tmp");
            Files.write(temporary, bytes);
            keepPermissions(path, temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException e) {
            throw new DocumentException(LocalFiles.reason(e), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Die Nachbardatei bleibt liegen; der Fehler oben ist der wichtigere.
                }
            }
        }
    }

    /** Übernimmt die Rechte der alten Datei (POSIX), damit etwa ausführbare Skripte ausführbar bleiben. */
    private static void keepPermissions(Path original, Path replacement) throws IOException {
        if (!Files.exists(original)) {
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(original, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(replacement, posix.readAttributes().permissions());
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(original, DosFileAttributeView.class);
        DosFileAttributeView copy = Files.getFileAttributeView(replacement, DosFileAttributeView.class);
        if (dos != null && copy != null) {
            DosFileAttributes attributes = dos.readAttributes();
            copy.setHidden(attributes.isHidden());
            copy.setArchive(attributes.isArchive());
            copy.setSystem(attributes.isSystem());
        }
    }

    private static String name(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
