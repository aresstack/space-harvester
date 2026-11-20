package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Dateien über der Größengrenze des Caches (Angelo, 20.11.2026): Sie werden gar nicht erst heruntergeladen. An ihrer
 * Stelle steht ein Snapshot ohne Dateiinhalt mit den Kopfdaten der Quelle, wie bei entfernten Videos; die Lese-Ansicht
 * zeigt dann, dass die Datei zu groß ist, und öffnet sie in der Quelle. Sonst lüde jeder Abruf, Abgleich und die
 * Beschaffung im Hintergrund die Datei immer wieder ganz, nur damit der Cache sie danach abweist.
 */
public final class LargeFiles {

    /** Medientyp des Snapshots ohne Dateiinhalt. */
    public static final String MEDIA_TYPE = "application/x-space-harvester-large-file";
    /** Natives Format des Snapshots ohne Dateiinhalt. */
    public static final String FORMAT = "large-file-metadata";
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

    /** Ob der Snapshot nur für eine zu große Datei steht. */
    public static boolean placeholder(NativePayload payload) {
        return payload != null && FORMAT.equals(payload.nativeFormat());
    }
}
