package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The one PDF extraction: text in reading order, metadata title, honest failures, the header signature check. */
public class PdfDocumentTextTest {

    @Test
    public void extractsTextTitleAndPages() throws Exception {
        PdfDocumentText pdf = PdfDocumentText.extract(
                TestPdfs.withText("Site Safety Report", "Wearables warn workers before a fall."));

        assertEquals(PdfDocumentText.Failure.NONE, pdf.failure);
        assertEquals("Site Safety Report", pdf.title);
        assertEquals("Wearables warn workers before a fall.", pdf.text);
        assertEquals(1, pdf.pages);
        assertFalse(pdf.encrypted);
    }

    @Test
    public void aDocumentWithoutTextYieldsEmptyTextNotAFailure() throws Exception {
        PdfDocumentText pdf = PdfDocumentText.extract(TestPdfs.blank());

        assertEquals(PdfDocumentText.Failure.NONE, pdf.failure);
        assertEquals("", pdf.text);
        assertEquals("", pdf.title);
        assertEquals(1, pdf.pages);
    }

    @Test
    public void brokenOrMissingBytesAreUnparseable() {
        PdfDocumentText broken = PdfDocumentText.extract("%PDF-1.4 truncated".getBytes(StandardCharsets.US_ASCII));
        assertEquals(PdfDocumentText.Failure.UNPARSEABLE, broken.failure);
        assertEquals("", broken.text);

        assertEquals(PdfDocumentText.Failure.UNPARSEABLE, PdfDocumentText.extract(new byte[0]).failure);
        assertEquals(PdfDocumentText.Failure.UNPARSEABLE, PdfDocumentText.extract(null).failure);
    }

    @Test
    public void recognisesTheHeaderWithinTheFirstKilobyteOnly() {
        byte[] plain = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
        assertTrue(PdfDocumentText.looksLikePdf(plain, plain.length));

        byte[] prefixed = "\r\n\r\ngarbage%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        assertTrue("producers may write a few bytes before the header",
                PdfDocumentText.looksLikePdf(prefixed, prefixed.length));

        byte[] html = "<!doctype html><title>Report</title>".getBytes(StandardCharsets.US_ASCII);
        assertFalse(PdfDocumentText.looksLikePdf(html, html.length));

        byte[] late = new byte[2048];
        java.util.Arrays.fill(late, (byte) ' ');
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, late, 1500, 5);
        assertFalse(PdfDocumentText.looksLikePdf(late, late.length));

        assertFalse("only the bytes actually read count", PdfDocumentText.looksLikePdf(plain, 3));
        assertFalse(PdfDocumentText.looksLikePdf(null, 0));
    }
}
