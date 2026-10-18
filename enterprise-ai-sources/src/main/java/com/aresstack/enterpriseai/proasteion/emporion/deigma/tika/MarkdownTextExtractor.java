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
import java.util.Collections;

/**
 * Extracts content from markdown resources with lightweight structure recognition.
 *
 * <p>Recognizes headings (lines starting with {@code #}) and fenced code blocks
 * (delimited by {@code ```}). All other content is treated as TEXT blocks.
 * This is intentionally shallow — full markdown parsing is not the goal.
 */
public final class MarkdownTextExtractor implements ResourceExtractor {

    private final Charset charset;

    public MarkdownTextExtractor() {
        this(Charset.forName("UTF-8"));
    }

    public MarkdownTextExtractor(Charset charset) {
        if (charset == null) {
            throw new IllegalArgumentException("Charset must not be null");
        }
        this.charset = charset;
    }

    @Override
    public boolean supports(DetectedContentType contentType) {
        return contentType != null && contentType.category() == ContentCategory.MARKDOWN;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        String text = new String(request.content(), charset);
        String[] lines = text.split("\n", -1);

        DetectedContentType type = request.detectedContentType() != null
                ? request.detectedContentType()
                : new DetectedContentType("text/markdown", ContentCategory.MARKDOWN, request.filenameHint());

        ExtractedDocument.Builder docBuilder = ExtractedDocument.builder().contentType(type);

        int blockIndex = 0;
        StringBuilder currentText = new StringBuilder();
        boolean inCodeBlock = false;
        StringBuilder codeContent = new StringBuilder();
        String codeLang = null;

        for (String line : lines) {
            if (!inCodeBlock && line.startsWith("```")) {
                // Flush any accumulated text
                if (currentText.length() > 0) {
                    docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.TEXT, currentText.toString().trim()));
                    currentText.setLength(0);
                }
                inCodeBlock = true;
                codeLang = line.length() > 3 ? line.substring(3).trim() : null;
                codeContent.setLength(0);
            } else if (inCodeBlock && line.startsWith("```")) {
                // End code block
                java.util.Map<String, String> attrs = codeLang != null && !codeLang.isEmpty()
                        ? Collections.singletonMap("language", codeLang)
                        : null;
                docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.CODE, codeContent.toString(), attrs));
                inCodeBlock = false;
                codeLang = null;
            } else if (inCodeBlock) {
                if (codeContent.length() > 0) {
                    codeContent.append('\n');
                }
                codeContent.append(line);
            } else if (line.startsWith("#")) {
                // Flush text
                if (currentText.length() > 0) {
                    docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.TEXT, currentText.toString().trim()));
                    currentText.setLength(0);
                }
                // Extract heading text (strip # prefix)
                String heading = line.replaceFirst("^#+\\s*", "");
                int level = 0;
                for (int i = 0; i < line.length() && line.charAt(i) == '#'; i++) {
                    level++;
                }
                docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.HEADING, heading,
                        Collections.singletonMap("level", String.valueOf(level))));
            } else {
                if (currentText.length() > 0) {
                    currentText.append('\n');
                }
                currentText.append(line);
            }
        }

        // Flush remaining
        if (inCodeBlock && codeContent.length() > 0) {
            docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.CODE, codeContent.toString()));
        } else if (currentText.length() > 0) {
            String trimmed = currentText.toString().trim();
            if (!trimmed.isEmpty()) {
                docBuilder.addBlock(new ExtractedBlock(blockIndex++, BlockKind.TEXT, trimmed));
            }
        }

        return ExtractionResult.success(request.resourceId(), type, docBuilder.build());
    }
}
