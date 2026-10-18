package com.aresstack.enterpriseai.proasteion.emporion.deigma.tika;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.BlockKind;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;

import java.nio.charset.Charset;

/**
 * Extracts content from plain text resources.
 *
 * <p>Produces a single TEXT block containing the full decoded content.
 * Uses UTF-8 by default.
 */
public final class PlainTextExtractor implements ResourceExtractor {

    private final Charset charset;

    public PlainTextExtractor() {
        this(Charset.forName("UTF-8"));
    }

    public PlainTextExtractor(Charset charset) {
        if (charset == null) {
            throw new IllegalArgumentException("Charset must not be null");
        }
        this.charset = charset;
    }

    @Override
    public boolean supports(DetectedContentType contentType) {
        return contentType != null && (contentType.category() == ContentCategory.PLAIN_TEXT
                || contentType.category() == ContentCategory.SOURCE_CODE);
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        String text = new String(request.content(), charset);

        DetectedContentType type = request.detectedContentType() != null
                ? request.detectedContentType()
                : new DetectedContentType("text/plain", ContentCategory.PLAIN_TEXT, request.filenameHint());

        ExtractedDocument doc = ExtractedDocument.builder()
                .contentType(type)
                .addBlock(new ExtractedBlock(0, BlockKind.TEXT, text))
                .build();

        return ExtractionResult.success(request.resourceId(), type, doc);
    }
}
