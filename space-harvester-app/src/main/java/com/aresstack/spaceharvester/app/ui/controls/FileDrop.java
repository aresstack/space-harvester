package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.TransferHandler;

/**
 * Dateien hineinziehen (aus dem Explorer) und Bilder einfügen (Strg+V, etwa ein Bildschirmfoto): beides kommt als
 * Liste von Dateien bei {@code onFiles} an, wie aus einer Dateiauswahl. Ein eingefügtes Bild ohne Datei wird vorher als
 * PNG in einen temporären Ordner geschrieben. In einem Textfeld bleibt alles andere (Text ziehen, kopieren, einfügen)
 * beim bisherigen Handler des Felds.
 */
public final class FileDrop extends TransferHandler {

    private static final DateTimeFormatter PASTED_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final TransferHandler text;
    private final Consumer<List<Path>> onFiles;

    private FileDrop(TransferHandler text, Consumer<List<Path>> onFiles) {
        this.text = text;
        this.onFiles = Objects.requireNonNull(onFiles, "onFiles");
    }

    /**
     * Nimmt auf jeder der Komponenten Dateien und Bilder an. Textfelder behalten ihren Handler für Text; andere
     * Komponenten bekommen nur den Datei-Empfang.
     */
    public static void install(Consumer<List<Path>> onFiles, JComponent... components) {
        for (JComponent component : components) {
            if (component != null) {
                component.setTransferHandler(new FileDrop(component.getTransferHandler(), onFiles));
            }
        }
    }

    /**
     * Ob {@code flavors} Dateien oder ein Bild tragen, die hier ankommen. Ein Bild nur, wenn kein Text dabei ist: Wer
     * Zellen aus Excel kopiert, bekommt Text und Bild zugleich und will den Text einfügen.
     */
    static boolean carriesFiles(DataFlavor[] flavors) {
        boolean image = false;
        boolean text = false;
        for (DataFlavor flavor : flavors) {
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return true;
            }
            image |= DataFlavor.imageFlavor.equals(flavor);
            text |= DataFlavor.stringFlavor.equals(flavor);
        }
        return image && !text;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (carriesFiles(support.getDataFlavors())) {
            if (support.isDrop() && (support.getSourceDropActions() & COPY) != 0) {
                support.setDropAction(COPY);
            }
            return true;
        }
        return text != null && text.canImport(support);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!carriesFiles(support.getDataFlavors())) {
            return text != null && text.importData(support);
        }
        try {
            List<Path> files = files(support.getTransferable());
            if (files.isEmpty()) {
                return false;
            }
            onFiles.accept(files);
            return true;
        } catch (UnsupportedFlavorException | IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Die Dateien der Übergabe; ein Bild ohne Datei wird als PNG in einen temporären Ordner geschrieben. */
    static List<Path> files(Transferable transferable) throws UnsupportedFlavorException, IOException {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            List<Path> files = new ArrayList<>();
            for (Object each : (List<?>) transferable.getTransferData(DataFlavor.javaFileListFlavor)) {
                if (each instanceof File file && file.isFile()) {
                    files.add(file.toPath());
                }
            }
            return files;
        }
        Object image = transferable.getTransferData(DataFlavor.imageFlavor);
        return image instanceof Image picture ? List.of(pasted(picture)) : List.of();
    }

    /** Schreibt ein eingefügtes Bild als „Bild ‹Zeit›.png“ (Name nur ASCII, gilt in jeder Dateisystem-Kodierung) in einen eigenen temporären Ordner. */
    static Path pasted(Image image) throws IOException {
        RenderedImage rendered = image instanceof RenderedImage ready ? ready : toBuffered(image);
        Path folder = Files.createTempDirectory("chat-bild");
        folder.toFile().deleteOnExit();
        Path file = folder.resolve("Bild " + LocalDateTime.now().format(PASTED_NAME) + ".png");
        if (!ImageIO.write(rendered, "png", file.toFile())) {
            throw new IOException("PNG nicht schreibbar");
        }
        file.toFile().deleteOnExit();
        return file;
    }

    private static BufferedImage toBuffered(Image image) {
        BufferedImage buffered = new BufferedImage(Math.max(1, image.getWidth(null)), Math.max(1, image.getHeight(null)),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = buffered.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return buffered;
    }

    // Kopieren und Ausschneiden bleibt beim Handler des Textfelds (Text herausziehen ist im Chat aus).

    @Override
    public int getSourceActions(JComponent c) {
        return text == null ? NONE : text.getSourceActions(c);
    }

    @Override
    public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clip, int action) {
        if (text != null) {
            text.exportToClipboard(comp, clip, action);
        }
    }
}
