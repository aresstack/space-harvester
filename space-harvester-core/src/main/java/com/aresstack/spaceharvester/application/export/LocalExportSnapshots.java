package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.PortableLocalDocument;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/** Materialize local references at the export boundary, without changing the cached snapshot. */
public final class LocalExportSnapshots {

    private final DocumentExtractions documents;
    private final BiFunction<PageSnapshot, LocalOriginalReference, Optional<NativePayload>> originals;

    public LocalExportSnapshots(DocumentExtractions documents,
                                BiFunction<PageSnapshot, LocalOriginalReference, Optional<NativePayload>> originals) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.originals = Objects.requireNonNull(originals, "originals");
    }

    public Result prepare(PageSnapshot snapshot, boolean holdingsOnly) {
        LocalOriginalReference reference = LocalOriginalReference.read(snapshot.payload()).orElseThrow();
        if (!holdingsOnly) {
            Optional<NativePayload> original = original(snapshot, reference);
            if (original.isPresent()) {
                return new Result(withPayload(snapshot, original.get()), null, false);
            }
        }
        var extraction = Optional.ofNullable(reference.extractionKey()).flatMap(documents::stored);
        var retained = extraction.map(documents::originalText);
        String text = retained.map(DocumentExtractions.OriginalPreview::text).orElse(reference.preview());
        boolean truncated = retained.map(DocumentExtractions.OriginalPreview::truncated)
                .orElse(reference.previewTruncated() || reference.extractionKey() != null);
        var portable = new PortableLocalDocument(snapshot.payload().rawSha256(), reference.originalSha256(),
                reference.originalMediaType(), reference.status(), reference.reason(), extraction.isPresent(),
                text, truncated);
        boolean incomplete = !holdingsOnly || truncated || reference.status() != ExtractionStatus.OK
                || extraction.isEmpty();
        String warning = holdingsOnly ? "Ausgabe enthält den gespeicherten Text, nicht das Original"
                : "Original nicht in der gespeicherten Fassung verifizierbar oder über der Exportgrenze; nur Text/Kopfdaten";
        if (truncated) {
            warning += "; Textauszug gekürzt";
        }
        if (extraction.isEmpty()) {
            warning += "; Extraktion nicht verfügbar";
        }
        return new Result(withPayload(snapshot, portable.payload()), warning, incomplete);
    }

    private Optional<NativePayload> original(PageSnapshot snapshot, LocalOriginalReference reference) {
        if (reference.originalSha256() == null) {
            return Optional.empty();
        }
        try {
            return originals.apply(snapshot, reference)
                    .filter(payload -> reference.originalSha256().equals(payload.rawSha256()));
        } catch (SourceAccessException | UncheckedIOException unavailable) {
            return Optional.empty();
        }
    }

    private static PageSnapshot withPayload(PageSnapshot snapshot, NativePayload payload) {
        return new PageSnapshot(snapshot.key(), snapshot.metadata(), snapshot.metadataHash(),
                snapshot.attributesHash(), payload, snapshot.retrievedAt());
    }

    public record Result(PageSnapshot snapshot, String warning, boolean incomplete) {
    }
}
