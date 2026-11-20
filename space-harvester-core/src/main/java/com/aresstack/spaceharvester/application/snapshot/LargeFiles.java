package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Dateien über der Größengrenze des Caches (Angelo, 20.11.2026): Das Original legt der Cache nicht ab. Hat die Datei
 * lesbaren Text und ist sie nicht größer als die Grenze für den Textauszug, steht an ihrer Stelle der Textauszug der
 * Quelle ({@link #TEXT_FORMAT}): Suche und Text-Ansicht arbeiten ohnehin auf dem Textauszug, nie auf dem Original.
 * Sonst (Archive, Figma, Aufzeichnungen, …) wird sie gar nicht erst heruntergeladen; es bleibt ein Snapshot nur mit
 * den Kopfdaten ({@link #FORMAT}). In beiden Fällen zeigt die Lese-Ansicht, dass das Original zu groß ist, und öffnet
 * es in der Quelle. Sonst lüde jeder Abruf, Abgleich und die Beschaffung im Hintergrund die Datei immer wieder ganz,
 * nur damit der Cache sie danach abweist.
 */
public final class LargeFiles {

    /** Medientyp des Snapshots ohne Dateiinhalt. */
    public static final String MEDIA_TYPE = "application/x-space-harvester-large-file";
    /** Natives Format des Snapshots ohne Dateiinhalt. */
    public static final String FORMAT = "large-file-metadata";
    /** Natives Format des Snapshots mit dem Textauszug statt des Originals: Lesetext der Quelle als UTF-8. */
    public static final String TEXT_FORMAT = "large-file-text";
    /** Medientyp des Textauszugs. */
    public static final String TEXT_MEDIA_TYPE = "text/markdown";
    /** Größe der Datei in Bytes, wie sie die Quelle meldet (etwa BSCW aus {@code getcontentlength}). */
    public static final String FILE_SIZE = "file.size";
    /** Wie {@link #FILE_SIZE}, unter dem Namen aus WebDAV. */
    private static final String DAV_CONTENT_LENGTH = "dav.contentLength";

    private LargeFiles() {
    }

    /** Die gemeldete Größe der Datei; {@code -1}, wenn die Quelle keine meldet. */
    public static long size(PageMetadata metadata) {
        String value = metadata.attributes().getOrDefault(FILE_SIZE, metadata.attributes().get(DAV_CONTENT_LENGTH));
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException unknown) {
            return -1;
        }
    }

    /** Ob die Datei größer ist als {@code maxBytes}; ohne gemeldete Größe oder ohne Grenze ({@code <= 0}) nie. */
    public static boolean tooLarge(PageMetadata metadata, long maxBytes) {
        return maxBytes > 0 && PageMetadata.FILE_KIND.equals(metadata.attributes().get(PageMetadata.RESOURCE_KIND))
                && size(metadata) > maxBytes;
    }

    /** Der Snapshot ohne Dateiinhalt: nur die Kopfdaten der Quelle. */
    public static PageSnapshot placeholder(String sourceId, PageMetadata metadata, Instant retrievedAt) {
        Objects.requireNonNull(metadata, "metadata");
        return PageSnapshot.of(sourceId, metadata, new NativePayload(new byte[0], MEDIA_TYPE, FORMAT), retrievedAt);
    }

    /** Der Snapshot mit dem Textauszug statt des Originals. */
    public static PageSnapshot textOnly(String sourceId, PageMetadata metadata, byte[] text, Instant retrievedAt) {
        Objects.requireNonNull(metadata, "metadata");
        return PageSnapshot.of(sourceId, metadata, new NativePayload(text, TEXT_MEDIA_TYPE, TEXT_FORMAT), retrievedAt);
    }

    /** Ob der Snapshot für eine zu große Datei steht, nur mit Kopfdaten oder mit dem Textauszug. */
    public static boolean placeholder(NativePayload payload) {
        return payload != null && (FORMAT.equals(payload.nativeFormat()) || TEXT_FORMAT.equals(payload.nativeFormat()));
    }

    /** Ob der Snapshot den Textauszug einer zu großen Datei trägt. */
    public static boolean textOnly(NativePayload payload) {
        return payload != null && TEXT_FORMAT.equals(payload.nativeFormat());
    }
}
