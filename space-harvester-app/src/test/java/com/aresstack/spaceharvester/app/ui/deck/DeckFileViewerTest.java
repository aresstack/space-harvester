package com.aresstack.spaceharvester.app.ui.deck;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Lokale Dokumente im Command Deck mit den Anhang-Viewern: Word formatiert, Text umschaltbar. */
class DeckFileViewerTest {

    private static final long WAIT_MILLIS = TimeUnit.SECONDS.toMillis(20);

    @TempDir
    Path folder;

    @Test
    void showsWordFormattedAndSwitchesToText() throws Exception {
        Path file = folder.resolve("bericht.docx");
        try (XWPFDocument word = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            word.createParagraph().createRun().setText("Laborbericht");
            word.write(out);
        }
        AtomicReference<DeckFileViewer> viewer = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            viewer.set(new DeckFileViewer(file, "bericht.docx", new JLabel("Text")));
            viewer.get().setSize(1000, 700);
        });
        assertThat(viewer.get().textShown()).isFalse();
        long end = System.currentTimeMillis() + WAIT_MILLIS;
        AtomicBoolean rendered = new AtomicBoolean();
        while (!rendered.get()) {
            assertThat(System.currentTimeMillis()).as("Word gezeigt").isLessThan(end);
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> rendered.set(
                    viewer.get().viewer().textContent().contains("Laborbericht")));
        }
        SwingUtilities.invokeAndWait(() -> viewer.get().showText(true));
        assertThat(viewer.get().textShown()).isTrue();
    }

    @Test
    void knowsWhichFormatsTheViewersShow() {
        assertThat(DeckFileViewer.shows("a.docx")).isTrue();
        assertThat(DeckFileViewer.shows("a.doc")).isTrue();
        assertThat(DeckFileViewer.shows("a.pdf")).isTrue();
        assertThat(DeckFileViewer.shows("a.pptx")).isTrue();
        assertThat(DeckFileViewer.shows("a.xlsx")).isTrue();
        assertThat(DeckFileViewer.shows("a.rtf")).isFalse();
        // E-Mails kann der eine Viewer überall (Angelo 24.11.2026): Outlook und .eml
        assertThat(DeckFileViewer.shows("a.msg")).isTrue();
        assertThat(DeckFileViewer.shows("a.eml")).isTrue();
    }
}
