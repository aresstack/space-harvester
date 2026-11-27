package com.aresstack.askai.browser.playwright;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Real PDFs for the PDF tests, written with the same PDFBox generation the extraction reads them with. */
final class TestPdfs {

    private TestPdfs() {
    }

    /** One page with {@code bodyText}; {@code metadataTitle} may be null (no title in the metadata). */
    static byte[] withText(String metadataTitle, String bodyText) throws IOException {
        PDDocument document = new PDDocument();
        try {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream content = new PDPageContentStream(document, page);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, 12);
            content.newLineAtOffset(50, 700);
            content.showText(bodyText);
            content.endText();
            content.close();
            if (metadataTitle != null) {
                document.getDocumentInformation().setTitle(metadataTitle);
            }
            return save(document);
        } finally {
            document.close();
        }
    }

    /** One page without any text, like an image-only scan. */
    static byte[] blank() throws IOException {
        PDDocument document = new PDDocument();
        try {
            document.addPage(new PDPage());
            return save(document);
        } finally {
            document.close();
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
