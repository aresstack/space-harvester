package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/** Extract a local, read-only Word preview without relying on source-specific conversion endpoints. */
final class WordDocumentPreview {

    static final int MAX_PREVIEW_CHARACTERS = 2_000_000;

    private WordDocumentPreview() {
    }

    static String read(String fileName, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Die Quelle liefert 0 Byte; kein Word-Dokumentinhalt vorhanden");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text;
        boolean zipDocument = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        if (name.endsWith(".docx") || name.endsWith(".docm") || name.endsWith(".dotx")
                || (ReaderAttachmentKind.extension(name).isEmpty() && zipDocument)) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                text = extractor.getText();
            }
        } else if (name.endsWith(".doc") || name.endsWith(".dot")
                || (ReaderAttachmentKind.extension(name).isEmpty() && !zipDocument)) {
            try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
                text = extractor.getText();
            }
        } else {
            throw new IOException("Unsupported Word file format");
        }
        if (text == null || text.isBlank()) {
            return "Dieses Dokument enthält keinen extrahierbaren Text.";
        }
        if (text.length() > MAX_PREVIEW_CHARACTERS) {
            return text.substring(0, MAX_PREVIEW_CHARACTERS)
                    + "\n\n[Die Vorschau wurde gekürzt. Das Original bleibt unverändert.]";
        }
        return text;
    }

    static boolean supports(String fileName, String mediaType) {
        if (supports(fileName)) {
            return true;
        }
        if (!ReaderAttachmentKind.extension(fileName).isEmpty()) {
            return false;
        }
        String type = ReaderAttachmentKind.baseType(mediaType);
        return "application/msword".equals(type) || type.contains("wordprocessingml");
    }

    static boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".docm")
                || name.endsWith(".dot") || name.endsWith(".dotx");
    }
}
