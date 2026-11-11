package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** „Speichern“ eines Anhangs schreibt als Strom (#571) und hinterlässt bei einem Abbruch keine halbe Datei. */
class SaveAssetTest {

    @TempDir
    Path dir;

    @Test
    void writesTheStreamAndReportsItsSize() throws IOException {
        Path target = dir.resolve("anhang.txt");
        Files.writeString(target, "alt");

        long bytes = PageDetailPanel.writeReplacing(
                new ByteArrayInputStream("Wartungsfenster".getBytes(StandardCharsets.UTF_8)), target);

        assertThat(bytes).isEqualTo(15);
        assertThat(Files.readString(target)).isEqualTo("Wartungsfenster");
        assertThat(dir).isDirectoryNotContaining("glob:**.part");
    }

    @Test
    void anExistingPartFileOfSomeoneElseStaysUntouched() throws IOException {
        Path target = dir.resolve("anhang.txt");
        Path foreign = dir.resolve("anhang.txt.part");
        Files.writeString(foreign, "fremd");

        PageDetailPanel.writeReplacing(new ByteArrayInputStream(new byte[] {1, 2, 3}), target);

        assertThat(Files.readString(foreign)).isEqualTo("fremd");
        assertThat(Files.size(target)).isEqualTo(3);
    }

    @Test
    void aBrokenStreamLeavesNoHalfFileAndKeepsTheOldOne() throws IOException {
        Path target = dir.resolve("anhang.txt");
        Files.writeString(target, "alt");
        InputStream broken = new InputStream() {
            private int left = 4;

            @Override
            public int read() throws IOException {
                if (left-- > 0) {
                    return 'x';
                }
                throw new IOException("Prüfsumme stimmt nicht");
            }
        };

        assertThatThrownBy(() -> PageDetailPanel.writeReplacing(broken, target)).isInstanceOf(IOException.class);

        assertThat(Files.readString(target)).isEqualTo("alt");
        assertThat(dir).isDirectoryNotContaining("glob:**.part");
    }
}
