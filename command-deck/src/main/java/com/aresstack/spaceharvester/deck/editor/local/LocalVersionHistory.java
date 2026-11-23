package com.aresstack.spaceharvester.deck.editor.local;

import com.aresstack.spaceharvester.deck.editor.DocumentHash;
import com.aresstack.spaceharvester.deck.editor.VersionHistory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Lokaler Verlauf als Dateien: je Ablage und Adresse ein Ordner (benannt nach dem SHA-256 der beiden, nie nach dem
 * Pfad), darin je Version {@code <Millisekunden>-<Kennzeichen, URL-kodiert>.txt} in UTF-8 (Dateinamen nur ASCII). Grenzen wie die Vorgaben von
 * MainframeMate: höchstens {@code maxVersions} je Datei, nichts älter als {@code maxAge}.
 */
public final class LocalVersionHistory implements VersionHistory {

    private static final String SUFFIX = ".txt";

    private final Path root;
    private final int maxVersions;
    private final Duration maxAge;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    /** Vorgaben aus MainframeMate: 100 Versionen je Datei, 90 Tage. */
    public LocalVersionHistory(Path root) {
        this(root, 100, Duration.ofDays(90), Clock.systemUTC());
    }

    public LocalVersionHistory(Path root, int maxVersions, Duration maxAge, Clock clock) {
        this.root = root;
        this.maxVersions = maxVersions;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    public void record(String store, String location, String text, String label) {
        lock.lock();
        try {
            Path folder = folder(store, location);
            List<Path> existing = files(folder);
            if (!existing.isEmpty() && Files.readString(existing.get(0), StandardCharsets.UTF_8).equals(text)) {
                return;
            }
            Files.createDirectories(folder);
            long now = clock.millis();
            if (!existing.isEmpty()) {
                now = Math.max(now, millis(existing.get(0)) + 1);
            }
            Files.writeString(folder.resolve(now + "-" + clean(label) + SUFFIX), text, StandardCharsets.UTF_8);
            prune(folder);
        } catch (IOException e) {
            // Verlauf ist eine Hilfe; Fehler beim Merken stören die Arbeit nicht.
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Version> versions(String store, String location) {
        lock.lock();
        try {
            List<Version> versions = new ArrayList<>();
            for (Path file : files(folder(store, location))) {
                String id = file.getFileName().toString();
                String stem = id.substring(0, id.length() - SUFFIX.length());
                int dash = stem.indexOf('-');
                int length;
                try {
                    length = Files.readString(file, StandardCharsets.UTF_8).length();
                } catch (IOException e) {
                    continue;
                }
                versions.add(new Version(id, Instant.ofEpochMilli(millis(file)), length,
                        dash < 0 ? "" : java.net.URLDecoder.decode(stem.substring(dash + 1), StandardCharsets.UTF_8)));
            }
            return List.copyOf(versions);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> content(String store, String location, String id) {
        if (id == null || id.contains("/") || id.contains("\\") || !id.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        lock.lock();
        try {
            Path file = folder(store, location).resolve(id);
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    private void prune(Path folder) throws IOException {
        List<Path> files = files(folder);
        long oldest = clock.millis() - maxAge.toMillis();
        for (int i = 0; i < files.size(); i++) {
            // Die neueste Version bleibt immer.
            if (i > 0 && (i >= maxVersions || millis(files.get(i)) < oldest)) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    private Path folder(String store, String location) {
        return root.resolve(DocumentHash.of((store + "\n" + location).getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Path> files(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(SUFFIX) && millis(p) >= 0)
                    .sorted(Comparator.comparingLong(LocalVersionHistory::millis).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long millis(Path file) {
        String name = file.getFileName().toString();
        int dash = name.indexOf('-');
        try {
            return Long.parseLong(dash < 0 ? name.substring(0, name.length() - SUFFIX.length())
                    : name.substring(0, dash));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static String clean(String label) {
        return label == null ? "" : java.net.URLEncoder.encode(label, StandardCharsets.UTF_8).replace("*", "%2A");
    }
}
