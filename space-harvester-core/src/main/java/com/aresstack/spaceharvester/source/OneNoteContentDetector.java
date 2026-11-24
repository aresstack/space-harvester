package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import java.util.Locale;
import java.util.Objects;

/** Add OneNote recognition without modifying the verified upstream source copy. */
final class OneNoteContentDetector implements ContentDetector {
    static final String MIME = "application/onenote";
    static final String SECTION_MIME = MIME + "; format=one";

    private final ContentDetector delegate;

    OneNoteContentDetector(ContentDetector delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public DetectedContentType detect(String filename, String hint, byte[] content) {
        DetectedContentType detected = delegate.detect(filename, hint, content);
        if (isOneNote(detected.mimeType())
                || detected.category() == ContentCategory.UNKNOWN
                && (isOneNote(hint) || filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".one"))) {
            return new DetectedContentType(SECTION_MIME, ContentCategory.OFFICE_DOCUMENT, filename);
        }
        return detected;
    }

    private static boolean isOneNote(String type) {
        return type != null && MIME.equalsIgnoreCase(type.split(";", 2)[0].trim());
    }
}

