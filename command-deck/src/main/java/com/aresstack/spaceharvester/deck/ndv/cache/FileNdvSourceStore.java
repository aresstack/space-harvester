package com.aresstack.spaceharvester.deck.ndv.cache;

import com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Dauerhafte Ablage des NDV-Zwischenspeichers in Dateien (an Stelle der H2-Tabelle in MainframeMate): je Server und
 * Bibliothek ein Ordner, je Objekt der Quelltext ({@code NAME.src}) und Art, Größe und Datum ({@code NAME.meta}).
 * Dateinamen umkehrbar verschlüsselt ({@link #safe(String)}); nichts außer den Quelltexten selbst wird abgelegt, keine Zugangsdaten.
 */
public final class FileNdvSourceStore implements NdvSourceCache.Store {

    private final Path base;

    public FileNdvSourceStore(Path base) {
        this.base = base;
    }

    @Override
    public Map<String, NdvSourceCache.Entry> load(String server, String library) {
        Map<String, NdvSourceCache.Entry> entries = new LinkedHashMap<>();
        Path folder = folder(server, library);
        if (!Files.isDirectory(folder)) {
            return entries;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path meta : files.filter(p -> p.getFileName().toString().endsWith(".meta")).sorted().toList()) {
                String file = meta.getFileName().toString();
                Path src = meta.resolveSibling(file.substring(0, file.length() - 5) + ".src");
                if (!Files.isRegularFile(src)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
                if (lines.size() < 6) {
                    continue;
                }
                try {
                    NdvSourceCache.Entry entry = new NdvSourceCache.Entry(library, lines.get(0), lines.get(1),
                            Files.readString(src, StandardCharsets.UTF_8), Integer.parseInt(lines.get(2)),
                            lines.get(3), Integer.parseInt(lines.get(4)), Integer.parseInt(lines.get(5)));
                    if (file.equals(safe(entry.objectKey()) + ".meta")) {
                        entries.put(entry.objectKey(), entry);
                    }
                } catch (NumberFormatException e) {
                    // Ignore incomplete or invalid metadata rather than trusting the text.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("NDV-Zwischenspeicher nicht lesbar: " + folder, e);
        }
        return entries;
    }

    @Override
    public void put(String server, NdvSourceCache.Entry entry) {
        Path folder = folder(server, entry.library());
        try {
            Files.createDirectories(folder);
            String name = safe(entry.objectKey());
            Files.writeString(folder.resolve(name + ".src"), entry.source(), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(name + ".meta"), String.join("\n", entry.name(), entry.extension(),
                    String.valueOf(entry.size()), entry.date(), String.valueOf(entry.databaseId()),
                    String.valueOf(entry.fileNumber())) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("NDV-Zwischenspeicher nicht schreibbar: " + folder, e);
        }
    }

    @Override
    public void removeLibrary(String server, String library) {
        Path folder = folder(server, library);
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("NDV-Zwischenspeicher nicht löschbar: " + folder, e);
        }
    }

    private Path folder(String server, String library) {
        return base.resolve("v2").resolve(safe(server)).resolve(safe(library));
    }

    /**
     * Umkehrbar verschlüsselter Dateiname: Buchstaben, Ziffern, Punkt und Bindestrich bleiben, alles andere (auch
     * {@code _} selbst) wird {@code _HH} je UTF-8-Byte. So bekommen {@code A#B} und {@code A$B} verschiedene Dateien;
     * ein Name nur aus Punkten wird ganz verschlüsselt.
     */
    public static String safe(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "_";
        }
        boolean onlyDots = text.chars().allMatch(c -> c == '.');
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean keep = c < 0x80 && (Character.isLetterOrDigit(c) || c == '-' || c == '.' && !onlyDots);
            if (keep) {
                out.append((char) c);
            } else {
                out.append('_').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
