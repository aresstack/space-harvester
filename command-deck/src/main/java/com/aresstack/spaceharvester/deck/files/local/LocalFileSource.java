package com.aresstack.spaceharvester.deck.files.local;

import com.aresstack.spaceharvester.deck.files.FileAccessException;
import com.aresstack.spaceharvester.deck.files.FileEntry;
import com.aresstack.spaceharvester.deck.files.FileSource;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter „Lokale Dateien“ über {@code java.nio.file} (wie {@code VfsLocalFileService} in MainframeMate, ohne Commons
 * VFS). Orte: der Benutzerordner, die Laufwerke und die unter Einstellungen › Dateizugriff freigegebenen Ordner.
 */
public final class LocalFileSource implements FileSource {

    private final FileSystem fileSystem;
    private final Path home;
    private final Supplier<List<Path>> shared;

    /**
     * Lokales Dateisystem ab dem Benutzerordner; {@code shared} liefert die freigegebenen Ordner als Orte, jedes Mal
     * neu, damit Änderungen der Einstellungen ohne Neustart gelten.
     */
    public LocalFileSource(Supplier<List<Path>> shared) {
        this(FileSystems.getDefault(), Path.of(System.getProperty("user.home")), shared);
    }

    /** Für Tests: beliebiges Dateisystem und Startordner. */
    public LocalFileSource(FileSystem fileSystem, Path home, List<Path> shared) {
        this(fileSystem, home, () -> shared);
    }

    public LocalFileSource(FileSystem fileSystem, Path home, Supplier<List<Path>> shared) {
        this.fileSystem = fileSystem;
        this.home = home.toAbsolutePath().normalize();
        this.shared = shared;
    }

    @Override
    public String name() {
        return "Lokale Dateien";
    }

    @Override
    public String home() {
        return home.toString();
    }

    @Override
    public List<Place> places() {
        Map<String, Place> places = new LinkedHashMap<>();
        places.put(home.toString(), new Place("Benutzerordner", home.toString()));
        List<Path> folders = shared.get();
        for (Path folder : folders == null ? List.<Path>of() : folders) {
            String location = folder.toAbsolutePath().normalize().toString();
            places.putIfAbsent(location, new Place(location, location));
        }
        for (Path root : fileSystem.getRootDirectories()) {
            places.putIfAbsent(root.toString(), new Place(root.toString(), root.toString()));
        }
        return List.copyOf(places.values());
    }

    @Override
    public String normalize(String location) throws FileAccessException {
        String text = location == null ? "" : location.strip();
        if (text.isEmpty()) {
            return home.toString();
        }
        try {
            Path path = fileSystem.getPath(text);
            Path absolute = path.isAbsolute() ? path : home.resolve(path);
            return absolute.normalize().toString();
        } catch (InvalidPathException e) {
            throw new FileAccessException("„" + text + "“ ist kein gültiger Pfad.", e);
        }
    }

    @Override
    public Optional<String> parent(String location) {
        Path parent = fileSystem.getPath(location).getParent();
        return parent == null ? Optional.empty() : Optional.of(parent.toString());
    }

    @Override
    public String child(String folder, String name) {
        return fileSystem.getPath(folder).resolve(name).normalize().toString();
    }

    @Override
    public List<FileEntry> list(String folder) throws FileAccessException {
        Path directory = fileSystem.getPath(folder);
        if (!Files.isDirectory(directory)) {
            throw new FileAccessException(Files.exists(directory) ? "Das ist kein Ordner." : "Ordner nicht gefunden.");
        }
        List<FileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                entries.add(entry(child));
            }
        } catch (IOException e) {
            throw new FileAccessException(LocalFiles.reason(e), e);
        }
        return entries;
    }

    private static FileEntry entry(Path child) {
        String name = child.getFileName() == null ? child.toString() : child.getFileName().toString();
        try {
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class);
            boolean directory = attributes.isDirectory();
            return new FileEntry(name, child.toString(), directory, directory ? 0L : attributes.size(),
                    attributes.lastModifiedTime().toInstant());
        } catch (IOException e) {
            // Nicht lesbare Einträge (etwa Systemdateien) trotzdem zeigen, nur ohne Angaben.
            return new FileEntry(name, child.toString(), Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS), 0L,
                    null);
        }
    }

    @Override
    public void createFolder(String location) throws FileAccessException {
        try {
            Files.createDirectory(fileSystem.getPath(location));
        } catch (IOException e) {
            throw new FileAccessException(LocalFiles.reason(e), e);
        }
    }

    @Override
    public void createFile(String location) throws FileAccessException {
        try {
            Files.createFile(fileSystem.getPath(location));
        } catch (IOException e) {
            throw new FileAccessException(LocalFiles.reason(e), e);
        }
    }

    @Override
    public void delete(String location) throws FileAccessException {
        try {
            Files.delete(fileSystem.getPath(location));
        } catch (IOException e) {
            throw new FileAccessException(LocalFiles.reason(e), e);
        }
    }
}
