package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dateien in den Chat ziehen und Bilder einfügen (Angelo 27.11.2026); Text bleibt beim Textfeld. */
class FileDropTest {

    @TempDir
    Path folder;

    @Test
    void filesFromTheExplorerArriveAsAList() throws Exception {
        Path notes = Files.writeString(folder.resolve("notiz.txt"), "Hallo");
        Path photo = Files.write(folder.resolve("tafel.png"), new byte[] {1});
        List<Path> received = new ArrayList<>();
        JTextArea editor = new JTextArea();
        FileDrop.install(received::addAll, editor);

        boolean taken = take(editor, files(notes.toFile(), photo.toFile(),
                folder.toFile()));

        assertThat(taken).isTrue();
        assertThat(received).containsExactly(notes, photo); // Ordner werden nicht angehängt
        assertThat(editor.getText()).isEmpty();
    }

    @Test
    void textIsStillPastedIntoTheField() {
        List<Path> received = new ArrayList<>();
        JTextArea editor = new JTextArea();
        FileDrop.install(received::addAll, editor);

        take(editor, new StringSelection("Was steht da?"));

        assertThat(editor.getText()).isEqualTo("Was steht da?");
        assertThat(received).isEmpty();
    }

    @Test
    void aPastedScreenshotBecomesAPngFile() throws Exception {
        BufferedImage shot = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        List<Path> received = new ArrayList<>();
        JTextArea editor = new JTextArea();
        FileDrop.install(received::addAll, editor);

        take(editor, image(shot));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getFileName().toString()).startsWith("Bild ").endsWith(".png");
        BufferedImage written = ImageIO.read(received.get(0).toFile());
        assertThat(written.getWidth()).isEqualTo(4);
        assertThat(written.getHeight()).isEqualTo(3);
    }

    @Test
    void imageWithTextIsPastedAsText() {
        // Zellen aus Excel tragen Text und Bild zugleich: eingefügt wird der Text.
        assertThat(FileDrop.carriesFiles(new DataFlavor[] {DataFlavor.imageFlavor, DataFlavor.stringFlavor}))
                .isFalse();
        assertThat(FileDrop.carriesFiles(new DataFlavor[] {DataFlavor.imageFlavor})).isTrue();
        assertThat(FileDrop.carriesFiles(new DataFlavor[] {DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor}))
                .isTrue();
    }

    @Test
    void aPanelWithoutHandlerTakesOnlyFiles() throws Exception {
        Path notes = Files.writeString(folder.resolve("notiz.txt"), "Hallo");
        List<Path> received = new ArrayList<>();
        javax.swing.JPanel panel = new javax.swing.JPanel();
        FileDrop.install(received::addAll, panel);

        assertThat(take(panel, new StringSelection("x"))).isFalse();
        assertThat(take(panel, files(notes.toFile()))).isTrue();
        assertThat(received).containsExactly(notes);
    }

    /** Wie Swing beim Einfügen und Ablegen: erst fragen, dann übergeben. */
    private static boolean take(javax.swing.JComponent target, Transferable transferable) {
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(target, transferable);
        TransferHandler handler = target.getTransferHandler();
        return handler.canImport(support) && handler.importData(support);
    }

    private static Transferable files(File... files) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return List.of(files);
            }
        };
    }

    private static Transferable image(BufferedImage image) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return image;
            }
        };
    }
}
