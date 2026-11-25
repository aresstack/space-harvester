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
    private final int maxPayloadBytes;
    private final BiFunction<PageSnapshot, LocalOriginalReference, Optional<NativePayload>> originals;

    public LocalExportSnapshots(DocumentExtractions documents,
                                BiFunction<PageSnapshot, LocalOriginalReference, Optional<NativePayload>> originals,
                                long maxPayloadBytes) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = (int) Math.min(maxPayloadBytes,
                com.aresstack.spaceharvester.application.assets.Assets.MAX_IN_MEMORY_BYTES);
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
        var retained = extraction.map(stored -> documents.originalText(stored, maxPayloadBytes));
        String text = retained.map(DocumentExtractions.OriginalPreview::text).orElse(reference.preview());
        boolean truncated = retained.map(DocumentExtractions.OriginalPreview::truncated)
                .orElse(reference.previewTruncated() || reference.extractionKey() != null);
        NativePayload payload = portable(snapshot, reference, extraction.isPresent(), text, truncated);
        if (payload.length() > maxPayloadBytes) {
            truncated = true;
            payload = fit(snapshot, reference, extraction.isPresent(), text);
        }
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
        return new Result(withPayload(snapshot, payload), warning, incomplete);
    }

    private NativePayload fit(PageSnapshot snapshot, LocalOriginalReference reference, boolean available, String text) {
        NativePayload empty = portable(snapshot, reference, available, "", true);
        int budget = maxPayloadBytes - empty.length();
        if (budget < 0) {
            throw new com.aresstack.spaceharvester.application.jobs.JobFailedException("EXPORT_PAYLOAD_TOO_LARGE",
                    "Die Exportgrenze reicht nicht für die Metadaten einer lokalen Datei");
        }
        // Bound the UTF-8 JSON string, including escaping, without cutting a surrogate pair.
        int end = 0;
        while (end < text.length()) {
            int code = text.codePointAt(end);
            int cost = jsonBytes(code);
            if (cost > budget) {
                break;
            }
            budget -= cost;
            end += Character.charCount(code);
        }
        NativePayload payload = portable(snapshot, reference, available, text.substring(0, end), true);
        if (payload.length() > maxPayloadBytes) {
            // Retain a safe prefix even if the JSON encoder changes its escaping rules.
            int safe = Math.min(end, (maxPayloadBytes - empty.length()) / 12);
            if (safe > 0 && Character.isHighSurrogate(text.charAt(safe - 1))) {
                safe--;
            }
            payload = portable(snapshot, reference, available, text.substring(0, safe), true);
        }
        return payload;
    }

    private static int jsonBytes(int code) {
        return switch (code) {
            case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
            default -> code < 32 ? 6 : code < 128 ? 1 : code < 2048 ? 2 : code < 65536 ? 3 : 4;
        };
    }

    private static NativePayload portable(PageSnapshot snapshot, LocalOriginalReference reference,
                                          boolean available, String text, boolean truncated) {
        return new PortableLocalDocument(snapshot.payload().rawSha256(), reference.originalSha256(),
                reference.originalMediaType(), reference.status(), reference.reason(), available,
                text, truncated).payload();
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
