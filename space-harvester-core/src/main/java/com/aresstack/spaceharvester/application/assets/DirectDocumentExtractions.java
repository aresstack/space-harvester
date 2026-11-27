package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Extract a local original into the existing section store without duplicating the original. */
public final class DirectDocumentExtractions {

    private final DocumentExtractions documents;

    public DirectDocumentExtractions(DocumentExtractions documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /**
     * Commit only after the source has revalidated its path, access grant and revision.
     * Keep the original digest separate from the digest of any derived snapshot.
     */
    public Optional<Original> extract(HarvestSource source, PageMetadata expected, Predicate<String> wanted) {
        return extract(source, expected, wanted, 0);
    }

    /**
     * Wie {@link #extract(HarvestSource, PageMetadata, Predicate)}, dazu die Grenze großer Dateien des Aufrufers
     * ({@code cache.max-text-file-size}): Eine größere Datei wird weder gehasht noch gelesen, sie bleibt mit
     * {@link StatusReason#TOO_LARGE} nur mit ihren Kopfdaten stehen, wie große Dateien anderer Quellen
     * ({@code LargeFiles}). Ohne diese Grenze las der Abgleich eines lokalen Ordners jede Datei jeder Größe zweimal
     * ganz (Prüfsumme) und extrahierte sie vollständig, nacheinander (Angelo 27.11.2026: 40-GB-Ordner im
     * Schneckentempo).
     *
     * @param maxBytes größte Datei, die gelesen wird; {@code <= 0} ohne weitere Grenze
     */
    public Optional<Original> extract(HarvestSource source, PageMetadata expected, Predicate<String> wanted,
                                      long maxBytes) {
        try (Pending pending = new Pending()) {
            Optional<Read> read = source.readOriginalInPlace(expected.pageId(), wanted, 0,
                    (original, actual, mediaType) -> {
                        requireSameRevision(source, expected, actual);
                        long limit = smallest(documents.limits().maxFileBytes(), maxBytes);
                        if (limit > 0 && Files.size(original) > limit) {
                            return new Read(actual, mediaType, null,
                                    Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.TOO_LARGE));
                        }
                        String sha256 = sha256(original);
                        pending.stage = documents.prepareOriginal(original, actual.title(), mediaType, sha256);
                        // Detect content changes during parsing, even if the file's attributes were restored.
                        if (!pending.stage.alreadyStored() && !sha256.equals(sha256(original))) {
                            throw changed(source);
                        }
                        return new Read(actual, mediaType, sha256, null);
                    });
            if (read.isEmpty()) {
                return Optional.empty();
            }
            Read verified = read.get();
            if (verified.excluded() != null) {
                return Optional.of(new Original(verified.metadata(), verified.mediaType(), null,
                        verified.excluded(), Optional.empty(), new DocumentExtractions.OriginalPreview("", false)));
            }
            StoredExtraction stored = pending.stage.commit();
            return Optional.of(new Original(verified.metadata(), verified.mediaType(), verified.sha256(),
                    stored.document().outcome(), Optional.of(stored.key()), documents.originalPreview(stored)));
        }
    }

    /** Die kleinere zweier Grenzen; {@code <= 0} heißt jeweils keine Grenze. */
    private static long smallest(long first, long second) {
        if (first <= 0) {
            return Math.max(0, second);
        }
        return second <= 0 ? first : Math.min(first, second);
    }

    public record Original(PageMetadata metadata, String mediaType, String originalSha256, Outcome outcome,
                           Optional<ExtractionKey> extraction, DocumentExtractions.OriginalPreview preview) {
    }

    private record Read(PageMetadata metadata, String mediaType, String sha256, Outcome excluded) {
    }

    private static final class Pending implements AutoCloseable {
        private DocumentExtractions.PreparedOriginal stage;

        @Override
        public void close() {
            if (stage != null) {
                stage.close();
            }
        }
    }

    public static void requireSameRevision(HarvestSource source, PageMetadata expected, PageMetadata actual) {
        if (!expected.pageId().equals(actual.pageId())
                || !Objects.equals(expected.version(), actual.version())
                || !Objects.equals(expected.modifiedAt(), actual.modifiedAt())
                || !Objects.equals(expected.attributes().get("file.size"), actual.attributes().get("file.size"))) {
            throw changed(source);
        }
    }

    private static SourceAccessException changed(HarvestSource source) {
        return new SourceAccessException(source.id(), SourceAccessException.Reason.UNAVAILABLE,
                "Datei hat sich beim Lesen geändert", null);
    }

    private static String sha256(Path original) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(original)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
