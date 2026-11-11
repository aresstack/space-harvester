package com.aresstack.spaceharvester.application.configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Dateianhänge im Export (ADR 0023) unter {@code space-harvester.export.attachments}: Der Auftrag „Bilder beschaffen“
 * legt zusätzlich die Dokument-Anhänge der exportierten Seiten ab, wenn {@link #attachments()} an ist, ihr Medientyp
 * zu {@link #mediaTypes()} passt und sie nicht über {@link #maxSize()} liegen ({@link #tooLarge}). Andere stehen nur
 * mit Status und Grund im Manifest. Gilt ohne Neustart für den nächsten Auftrag.
 *
 * @param attachments ob Dokument-Anhänge beschafft werden (Standard an)
 * @param mediaTypes  zulässige Medientypen, klein geschrieben; ein Eintrag {@code typ/*} passt auf alle Untertypen
 * @param maxSize     größte Datei in Bytes; {@code 0} ohne Grenze (Standard, Angelo 11.11.2026). Text gibt es
 *                    unabhängig davon nur bis zur Grenze der Textextraktion (20 MiB).
 */
public record ExportSettings(boolean attachments, List<String> mediaTypes, long maxSize) {

    /** {@code export.attachments.enabled}: {@code true} oder {@code false}. */
    public static final String ATTACHMENTS_ENABLED = "export.attachments.enabled";
    /** {@code export.attachments.media-types}: Liste, durch Kommas getrennt oder als {@code key[0]}, …. */
    public static final String ATTACHMENTS_MEDIA_TYPES = "export.attachments.media-types";
    /** {@code export.attachments.max-size}: Bytes oder mit Einheit wie {@code 20MB}; {@code 0} ohne Grenze. */
    public static final String ATTACHMENTS_MAX_SIZE = "export.attachments.max-size";

    /** Ohne Grenze: Dateien beliebiger Größe gehen als Strom in den Export. */
    public static final long DEFAULT_MAX_SIZE = 0;

    /** PDF, Office (OOXML und alt), OpenDocument, RTF, Mails und Klartext. */
    public static final List<String> DEFAULT_MEDIA_TYPES = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.*",
            "application/vnd.ms-word.*",
            "application/vnd.ms-excel.*",
            "application/vnd.ms-powerpoint.*",
            "application/vnd.oasis.opendocument.*",
            "application/rtf",
            "text/rtf",
            "message/rfc822",
            "application/vnd.ms-outlook",
            "text/*");

    public static final ExportSettings DEFAULT = new ExportSettings(true, DEFAULT_MEDIA_TYPES, DEFAULT_MAX_SIZE);

    public ExportSettings {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String type : Objects.requireNonNull(mediaTypes, "mediaTypes")) {
            String normalized = normalize(type);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        mediaTypes = List.copyOf(unique);
    }

    /** Ob eine Datei dieser Größe über {@link #maxSize()} liegt; ohne Grenze ({@code 0}) nie. */
    public boolean tooLarge(long size) {
        return maxSize > 0 && size > maxSize;
    }

    /**
     * Ob ein Medientyp zugelassen ist. Ein Eintrag {@code typ/*} passt auf jeden Untertyp, ein Eintrag mit {@code .*}
     * am Ende auf alle Untertypen mit diesem Anfang (etwa {@code application/vnd.oasis.opendocument.*}).
     */
    public boolean permits(String mediaType) {
        String type = normalize(mediaType);
        if (type.isEmpty()) {
            return false;
        }
        for (String allowed : mediaTypes) {
            if (allowed.equals(type)) {
                return true;
            }
            if (allowed.endsWith("*") && type.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String mediaType) {
        return mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
    }
}
