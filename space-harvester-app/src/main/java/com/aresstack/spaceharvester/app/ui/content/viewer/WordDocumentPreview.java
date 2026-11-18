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
            throw new IOException("The Word document is empty");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text;
        if (name.endsWith(".docx") || name.endsWith(".docm") || name.endsWith(".dotx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                text = extractor.getText();
            }
        } else if (name.endsWith(".doc") || name.endsWith(".dot")) {
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

    static boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".docm")
                || name.endsWith(".dot") || name.endsWith(".dotx");
    }
}
