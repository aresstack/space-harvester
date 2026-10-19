package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Ein Dateianhang der gelesenen Seite, wie Anhangsliste und Overlay ihn brauchen: Dateiname, Medientyp und Größe;
 * {@code key} ist die Kennung, unter der der Controller die Datei holt (für die Oberfläche undurchsichtig).
 *
 * <p>Kopie von {@code ReaderAttachment} aus dem Enterprise AI Client (exedra ui.reader); unverändert bis auf das
 * Paket. In der App ist {@code key} die Anhang-ID der Quelle.</p>
 */
public final class ReaderAttachment {

    private final String key;
    private final String fileName;
    private final String mediaType;
    private final long size;

    /** @param size Größe in Bytes, negativ: unbekannt */
    public ReaderAttachment(String key, String fileName, String mediaType, long size) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        this.key = key;
        this.fileName = fileName == null || fileName.trim().isEmpty() ? key : fileName;
        this.mediaType = mediaType == null ? "" : mediaType;
        this.size = size < 0 ? -1 : size;
    }

    public String key() {
        return key;
    }

    public String fileName() {
        return fileName;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Größe in Bytes, {@code -1}: unbekannt. */
    public long size() {
        return size;
    }

    public ReaderAttachmentKind kind() {
        return ReaderAttachmentKind.of(mediaType, fileName);
    }

    /** „PDF“, „Excel“, „PNG“ … */
    public String typeLabel() {
        return ReaderAttachmentKind.label(mediaType, fileName);
    }

    /** „459 KB“; leer, wenn die Größe unbekannt ist. */
    public String sizeLabel() {
        return size(size);
    }

    /** „PDF · 459 KB“ (ohne Größe nur der Typ). */
    public String summary() {
        String sized = sizeLabel();
        return sized.isEmpty() ? typeLabel() : typeLabel() + " · " + sized;
    }

    /** Größe für Menschen: Bytes, KB (gerundet) oder MB mit einer Nachkommastelle (Komma). */
    static String size(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        DecimalFormat format = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.GERMANY));
        return format.format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    @Override
    public String toString() {
        return "ReaderAttachment[" + fileName + ", " + mediaType + ", " + size + "]";
    }
}
