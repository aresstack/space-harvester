package com.aresstack.enterpriseai.proasteion.emporion.deigma.tika;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.BlockKind;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback-Extraktion mit Apache Tika ({@code AutoDetectParser}) für alles, was die einfachen Extraktoren
 * (Klartext, Markdown) nicht abdecken: PDF, Word, Excel, PowerPoint, OpenDocument, RTF, HTML, E-Mails ...
 * Übernommen aus MainframeMate {@code TikaFallbackExtractor}. Der Text wird absatzweise in TEXT-Blöcke zerlegt.
 */
public final class TikaResourceExtractor implements ResourceExtractor {

    /** Standardobergrenze des extrahierten Texts je Dokument (Zeichen). */
    public static final int DEFAULT_MAX_CHARACTERS = 10 * 1024 * 1024;

    private final AutoDetectParser parser = new AutoDetectParser();
    private final int maxCharacters;

    public TikaResourceExtractor() {
        this(DEFAULT_MAX_CHARACTERS);
    }

    public TikaResourceExtractor(int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be >= 1");
        }
        this.maxCharacters = maxCharacters;
    }

    @Override
    public boolean supports(DetectedContentType contentType) {
        return contentType != null && contentType.category() != ContentCategory.BINARY;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        DetectedContentType type = request.detectedContentType() != null ? request.detectedContentType()
                : new DetectedContentType("application/octet-stream", ContentCategory.UNKNOWN, request.filenameHint());
        byte[] bytes = request.content();
        if (bytes.length == 0) {
            return ExtractionResult.failure(request.resourceId(), type, "Datei ist leer");
        }
        Metadata metadata = new Metadata();
        if (request.filenameHint() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, request.filenameHint());
        }
        if (type.category() != ContentCategory.UNKNOWN) {
            metadata.set(Metadata.CONTENT_TYPE, type.mimeType());
        }
        BodyContentHandler handler = new BodyContentHandler(maxCharacters);
        List<String> warnings = new ArrayList<String>();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            // Nur beim Größenlimit bleibt der bis dahin gelesene Text verwendbar; andere Fehler (defekt,
            // verschlüsselt) sind ein Fehlschlag.
            if (!WriteLimitReachedException.isWriteLimitReached(e) || handler.toString().isEmpty()) {
                return ExtractionResult.failure(request.resourceId(), type,
                        "Tika-Extraktion fehlgeschlagen: " + e.getClass().getSimpleName());
            }
            warnings.add("Text nach " + maxCharacters + " Zeichen gekürzt");
        }
        ExtractedDocument.Builder document = ExtractedDocument.builder().contentType(type);
        String title = metadata.get(TikaCoreProperties.TITLE);
        if (title != null && !title.trim().isEmpty()) {
            document.title(title.trim());
        }
        int index = 0;
        for (String paragraph : handler.toString().split("\\n\\s*\\n")) {
            String text = paragraph.trim();
            if (!text.isEmpty()) {
                document.addBlock(new ExtractedBlock(index++, BlockKind.TEXT, text));
            }
        }
        if (index == 0) {
            warnings.add("Keine Textinhalte extrahiert");
        }
        return ExtractionResult.successWithWarnings(request.resourceId(), type, document.build(), warnings);
    }
}
