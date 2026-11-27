package com.aresstack.askai.browser.playwright;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * The ONE PDF text extraction of AskAI (PDFBox 2.0.x): {@code PDFTextStripper} with position sorting yields the text
 * in reading order, the title comes from the PDF metadata. Deterministic, no model, no OCR: an image-only scan yields
 * empty text, an encrypted or broken file says so instead of inventing content. The research browser reads PDF search
 * hits with it ({@link PdfPageReader}) and the #39 import extracts user PDFs with it, so a PDF gives the same text
 * whichever way it entered the research.
 */
public final class PdfDocumentText {

    /** Why the document gave no text at all; {@link #NONE} when it was parsed. */
    public enum Failure {
        NONE,
        PASSWORD_PROTECTED,
        UNPARSEABLE
    }

    /** The PDF header signature ({@code %PDF-}). */
    private static final byte[] SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    /**
     * How far into the file the header may start: the PDF reference (implementation note 13) lets readers accept the
     * header anywhere in the first 1024 bytes, because some producers write a few bytes before it.
     */
    private static final int HEADER_SEARCH_BYTES = 1024;

    /** The metadata title, or {@code ""} when the document has none. */
    public final String title;
    /** The extracted text in reading order, trimmed; {@code ""} for an image-only scan or a failure. */
    public final String text;
    public final int pages;
    /** The document was encrypted (it opened with the empty user password; extraction may be incomplete). */
    public final boolean encrypted;
    public final Failure failure;
    /** The parser's message for {@link Failure#UNPARSEABLE}, otherwise {@code ""}. */
    public final String failureDetail;

    private PdfDocumentText(String title, String text, int pages, boolean encrypted, Failure failure,
                            String failureDetail) {
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.pages = pages;
        this.encrypted = encrypted;
        this.failure = failure;
        this.failureDetail = failureDetail == null ? "" : failureDetail;
    }

    /** Extracts {@code bytes} (never {@code null}; an empty array is reported as unparseable). */
    public static PdfDocumentText extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new PdfDocumentText("", "", 0, false, Failure.UNPARSEABLE, "no bytes");
        }
        PDDocument document = null;
        try {
            document = PDDocument.load(bytes);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).trim();
            PDDocumentInformation info = document.getDocumentInformation();
            String title = info == null || info.getTitle() == null ? "" : info.getTitle().trim();
            return new PdfDocumentText(title, text, document.getNumberOfPages(), document.isEncrypted(),
                    Failure.NONE, "");
        } catch (InvalidPasswordException locked) {
            return new PdfDocumentText("", "", 0, true, Failure.PASSWORD_PROTECTED, "");
        } catch (IOException unparseable) {
            return new PdfDocumentText("", "", 0, false, Failure.UNPARSEABLE, unparseable.getMessage());
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException ignored) {
                    // closing a read-only in-memory document failed: nothing to recover
                }
            }
        }
    }

    /** True when {@code head} (the first bytes of a file) carries the PDF header signature. */
    public static boolean looksLikePdf(byte[] head, int length) {
        if (head == null) {
            return false;
        }
        int end = Math.min(Math.min(length, head.length), HEADER_SEARCH_BYTES) - SIGNATURE.length;
        for (int start = 0; start <= end; start++) {
            boolean match = true;
            for (int i = 0; i < SIGNATURE.length && match; i++) {
                match = head[start + i] == SIGNATURE[i];
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
