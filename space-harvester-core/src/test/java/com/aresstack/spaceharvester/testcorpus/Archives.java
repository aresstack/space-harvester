package com.aresstack.spaceharvester.testcorpus;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZIP-Archive für die Grenzen beim Auspacken (ADR 0026, Punkt 8 und Abnahme 4, 5, 7): Tiefe, Anzahl, Verhältnis
 * und verschlüsselte Einträge. Alle Umfänge sind Parameter, damit ein Test je Grenze einen Fall knapp darunter, genau
 * darauf und darüber bauen kann ({@link Limits#around}). Feste Zeitstempel, also bei gleichem Aufbau gleiche Bytes.
 */
public final class Archives {

    public static final String ZIP = "application/zip";
    /** Fester Zeitstempel aller Einträge (1.1.2026), damit die Bytes reproduzierbar bleiben. */
    private static final long TIME = 1_767_225_600_000L;

    private Archives() {
    }

    /** Ein ZIP aus Name und Inhalt, in dieser Reihenfolge; ein Name darf Pfadzeichen enthalten und doppelt sein. */
    public static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Ein ZIP mit genau einem Eintrag. */
    public static byte[] zip(String name, byte[] content) {
        return zip(Map.of(name, content));
    }

    /**
     * ZIP in ZIP: {@code depth} Archive ineinander (1 = ein Archiv ohne inneres). Jede Ebene {@code n} enthält
     * {@code ebene<n>.txt} mit der Marke {@link #levelMark}, jede außer der innersten {@code ebene<n+1>.zip}.
     */
    public static byte[] nested(int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("Tiefe muss positiv sein");
        }
        byte[] inner = zip("ebene" + depth + ".txt", text(levelMark(depth)));
        for (int level = depth - 1; level >= 1; level--) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("ebene" + level + ".txt", text(levelMark(level)));
            entries.put("ebene" + (level + 1) + ".zip", inner);
            inner = zip(entries);
        }
        return inner;
    }

    /**
     * ZIP mit zwei Dateien {@code notiz.txt} in verschiedenen Ordnern ({@code a/} und {@code b/}) und verschiedenem
     * Inhalt: Tikas Namenspfad nennt beide {@code /notiz.txt} (Messung der ADR). Marken {@link #SAME_NAME_MARK}-a
     * und -b.
     */
    public static byte[] sameNameInFolders() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/notiz.txt", text(SAME_NAME_MARK + "-a"));
        entries.put("b/notiz.txt", text(SAME_NAME_MARK + "-b"));
        return zip(entries);
    }

    /** Marke der beiden Dateien in {@link #sameNameInFolders}. */
    public static final String SAME_NAME_MARK = "MARKE-GLEICHER-NAME";

    /** Marke der Ebene {@code level} in {@link #nested}. */
    public static String levelMark(int level) {
        return "MARKE-EBENE-" + level;
    }

    /** Ein ZIP mit {@code count} kleinen Textdateien {@code datei-0000.txt} …, jede mit der Marke {@link #fileMark}. */
    public static byte[] many(int count) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put(String.format("datei-%04d.txt", i), text(fileMark(i)));
        }
        return zip(entries);
    }

    /** Marke der Datei {@code index} in {@link #many}. */
    public static String fileMark(int index) {
        return "MARKE-DATEI-" + index;
    }

    /**
     * Stark gepacktes Archiv wie in der Messung der ADR (327 KB, darin 150 MiB): ein Eintrag {@code gross.txt} mit
     * {@code unpackedBytes} Bytes aus Zeilen „AAAA…“ (Verhältnis rund 480 : 1), danach {@code danach.txt} mit der Marke
     * {@link #AFTER_PACKED}. Fortlaufend in die Datei geschrieben, ohne den Inhalt im Speicher zu halten.
     */
    public static Path packed(Path target, long unpackedBytes) {
        byte[] line = new byte[1024];
        Arrays.fill(line, (byte) 'A');
        line[line.length - 1] = '\n';
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            ZipEntry big = new ZipEntry("gross.txt");
            big.setTime(TIME);
            zip.putNextEntry(big);
            long left = unpackedBytes;
            while (left > 0) {
                int n = (int) Math.min(line.length, left);
                zip.write(line, 0, n);
                left -= n;
            }
            zip.closeEntry();
            put(zip, "danach.txt", text(AFTER_PACKED));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wie {@link #packed(Path, long)}, im Speicher; nur für kleine Umfänge. */
    public static byte[] packed(long unpackedBytes) {
        try {
            Path file = Files.createTempFile("korpus-gepackt", ".zip");
            try {
                return Files.readAllBytes(packed(file, unpackedBytes));
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Marke der Datei hinter dem gepackten Eintrag in {@link #packed}. */
    public static final String AFTER_PACKED = "MARKE-NACH-GEPACKT";

    /**
     * ZIP mit drei Einträgen, deren mittlerer ({@code geheim.txt}) als verschlüsselt markiert ist (Bit 0 der
     * Allzweck-Flags in lokalem und zentralem Kopf, Inhalt unlesbar). Tika übergibt ihn nicht als Teil (Messung der
     * ADR); {@code offen-1.txt} und {@code offen-2.txt} mit {@link #OPEN_MARK} bleiben lesbar.
     */
    public static byte[] partlyEncrypted() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("offen-1.txt", text(OPEN_MARK + "-1"));
        entries.put("geheim.txt", text("Dieser Text darf nie gelesen werden."));
        entries.put("offen-2.txt", text(OPEN_MARK + "-2"));
        byte[] zip = storedZip(entries);
        markEncrypted(zip, "geheim.txt");
        return zip;
    }

    /** Marke der lesbaren Einträge in {@link #partlyEncrypted}. */
    public static final String OPEN_MARK = "MARKE-OFFEN";

    private static byte[] storedZip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry stored = new ZipEntry(entry.getKey());
                stored.setTime(TIME);
                stored.setMethod(ZipEntry.STORED);
                stored.setSize(entry.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                stored.setCrc(crc.getValue());
                zip.putNextEntry(stored);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Setzt das Verschlüsselungs-Bit an lokalem (PK\3\4) und zentralem Kopf (PK\1\2) des Eintrags {@code name}. */
    private static void markEncrypted(byte[] zip, String name) {
        byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int marked = 0;
        for (int i = 0; i + 46 < zip.length; i++) {
            int signature = buffer.getInt(i);
            int flags;
            int nameAt;
            int nameLength;
            if (signature == 0x04034b50) {
                flags = i + 6;
                nameLength = buffer.getShort(i + 26) & 0xFFFF;
                nameAt = i + 30;
            } else if (signature == 0x02014b50) {
                flags = i + 8;
                nameLength = buffer.getShort(i + 28) & 0xFFFF;
                nameAt = i + 46;
            } else {
                continue;
            }
            if (nameLength == wanted.length && nameAt + nameLength <= zip.length
                    && Arrays.equals(zip, nameAt, nameAt + nameLength, wanted, 0, wanted.length)) {
                zip[flags] |= 1;
                marked++;
            }
        }
        if (marked != 2) {
            throw new IllegalStateException("Eintrag nicht gefunden");
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    static byte[] text(String text) {
        return (text + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
