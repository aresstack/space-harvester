package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WordDocumentPreviewTest {

    @Test
    void displaysDocxParagraphsWithoutAConfluencePreview() throws Exception {
        byte[] bytes;
        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("HaVdB Labor");
            word.createParagraph().createRun().setText("Betriebsorganisation");
            word.write(output);
            bytes = output.toByteArray();
        }
        assertThat(WordDocumentPreview.read("labor.docx", bytes))
                .contains("HaVdB Labor", "Betriebsorganisation");
    }

    @Test
    void rejectsNonWordFileTypesWithoutTryingToParseTheirBytes() {
        assertThat(WordDocumentPreview.supports("labor.doc")).isTrue();
        assertThat(WordDocumentPreview.supports("labor.docx")).isTrue();
        assertThat(WordDocumentPreview.supports("labor.pptx")).isFalse();
    }
}
