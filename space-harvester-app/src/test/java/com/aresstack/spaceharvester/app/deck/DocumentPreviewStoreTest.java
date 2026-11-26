package com.aresstack.spaceharvester.app.deck;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPreviewStoreTest {

    @TempDir
    Path dir;

    @Test
    void withoutAConfiguredFileSizeEveryFileGetsAPreview() throws Exception {
        Path file = Files.writeString(dir.resolve("bericht.eml"),
                "From: a@example.org\r\nSubject: Bericht\r\nContent-Type: text/plain\r\n\r\nHallo Welt\r\n");

        assertThat(new DocumentPreviewStore(DocumentTexts::text, () -> 0).load(file.toString()).text())
                .contains("Hallo");
    }

    @Test
    void aFileAboveTheConfiguredSizeIsRefusedWithTheSetting() throws Exception {
        Path file = Files.writeString(dir.resolve("bericht.rtf"), "{\\rtf1 Hallo}");
        DocumentText never = DocumentTexts.text();

        assertThatThrownBy(() -> new DocumentPreviewStore(() -> never, () -> 4).load(file.toString()))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("max-file-size");
    }
}
