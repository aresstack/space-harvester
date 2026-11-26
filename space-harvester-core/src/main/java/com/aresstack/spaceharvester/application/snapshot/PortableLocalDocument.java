package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Self-contained export representation; never claim that the retained text is the original file. */
public record PortableLocalDocument(String referenceSha256, String originalSha256, String originalMediaType,
                                    ExtractionStatus status, StatusReason reason, boolean extractionAvailable,
                                    String text, boolean textTruncated) {

    public static final String FORMAT = "portable-local-document/v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public PortableLocalDocument {
        Objects.requireNonNull(referenceSha256, "referenceSha256");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(text, "text");
    }

    public NativePayload payload() {
        return new NativePayload(JSON.writeValueAsBytes(this), "application/json", FORMAT);
    }

    public static Optional<PortableLocalDocument> read(NativePayload payload) {
        return payload != null && FORMAT.equals(payload.nativeFormat())
                ? Optional.of(JSON.readValue(payload.bytes(), PortableLocalDocument.class)) : Optional.empty();
    }

    @Override
    public String toString() {
        return "PortableLocalDocument[status=" + status + ", chars=" + text.length() + "]";
    }
}
