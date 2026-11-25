package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored manifest is not the original file. Its rawSha256 authenticates the JSON bytes only.
 * The optional originalSha256 and extraction key identify the separately stored structured extraction.
 */
public record LocalOriginalReference(String originalSha256, String originalMediaType, ExtractionKey extractionKey,
                                     ExtractionStatus status, StatusReason reason, String preview,
                                     boolean previewTruncated) {
    public static final String FORMAT = "local-original-reference/v1";
    public static final String MEDIA_TYPE = "application/vnd.space-harvester.local-original+json";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public LocalOriginalReference {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(preview, "preview");
        if (extractionKey != null && !extractionKey.sha256().equals(originalSha256)) {
            throw new IllegalArgumentException("Extraction key does not identify this original");
        }
        if ((status == ExtractionStatus.OK) != (reason == null)) {
            throw new IllegalArgumentException("Invalid extraction status");
        }
    }

    public static LocalOriginalReference of(DirectDocumentExtractions.Original original) {
        return new LocalOriginalReference(original.originalSha256(), original.mediaType(),
                original.extraction().orElse(null), original.outcome().status(), original.outcome().reason().orElse(null),
                original.preview().text(), original.preview().truncated());
    }

    public static LocalOriginalReference unsupported() {
        return new LocalOriginalReference(null, null, null, ExtractionStatus.UNSUPPORTED,
                StatusReason.UNSUPPORTED_FORMAT, "", false);
    }

    public NativePayload payload() {
        return new NativePayload(JSON.writeValueAsBytes(this), MEDIA_TYPE, FORMAT);
    }

    public static boolean recognizes(NativePayload payload) {
        return payload != null && FORMAT.equals(payload.nativeFormat());
    }

    public static Optional<LocalOriginalReference> read(NativePayload payload) {
        return recognizes(payload) ? Optional.of(JSON.readValue(payload.bytes(), LocalOriginalReference.class))
                : Optional.empty();
    }
}
