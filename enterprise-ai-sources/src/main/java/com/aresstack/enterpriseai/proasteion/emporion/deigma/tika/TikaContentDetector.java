package com.aresstack.enterpriseai.proasteion.emporion.deigma.tika;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Erkennung per Dateiendung und MIME-Hinweis ({@link SimpleContentDetector}, ohne Tika); nur was dort
 * {@link ContentCategory#UNKNOWN} bleibt, erkennt Apache Tika am Inhalt (Muster: MainframeMate
 * {@code TikaContentTypeDetector}).
 */
public final class TikaContentDetector implements ContentDetector {

    private final ContentDetector simple = new SimpleContentDetector();
    private final Detector detector;

    public TikaContentDetector() {
        this.detector = TikaConfig.getDefaultConfig().getDetector();
    }

    @Override
    public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
        DetectedContentType first = simple.detect(filenameHint, contentTypeHint, contentPrefix);
        if (first.category() != ContentCategory.UNKNOWN || contentPrefix == null) {
            return first;
        }
        Metadata metadata = new Metadata();
        if (filenameHint != null && !filenameHint.isEmpty()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filenameHint);
        }
        try (InputStream in = TikaInputStream.get(new ByteArrayInputStream(contentPrefix))) {
            MediaType type = detector.detect(in, metadata);
            String mime = type.getBaseType().toString();
            return new DetectedContentType(mime, category(mime), filenameHint);
        } catch (IOException | RuntimeException e) {
            return first;
        }
    }

    static ContentCategory category(String mime) {
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.equals("text/markdown") || m.equals("text/x-web-markdown")) {
            return ContentCategory.MARKDOWN;
        }
        if (m.equals("text/html") || m.equals("application/xhtml+xml")) {
            return ContentCategory.HTML;
        }
        if (m.equals("application/pdf")) {
            return ContentCategory.PDF;
        }
        if (m.startsWith("application/vnd.openxmlformats") || m.startsWith("application/vnd.ms-")
                || m.startsWith("application/vnd.oasis.opendocument") || m.equals("application/msword")
                || m.equals("application/rtf") || m.equals("message/rfc822")
                || m.equals("application/vnd.ms-outlook")) {
            return ContentCategory.OFFICE_DOCUMENT;
        }
        if (m.equals("application/json") || m.endsWith("/xml") || m.endsWith("+xml") || m.equals("text/csv")) {
            return ContentCategory.STRUCTURED_DATA;
        }
        if (m.startsWith("text/")) {
            return ContentCategory.PLAIN_TEXT;
        }
        if (m.equals("application/octet-stream")) {
            return ContentCategory.UNKNOWN;
        }
        return ContentCategory.BINARY;
    }
}
