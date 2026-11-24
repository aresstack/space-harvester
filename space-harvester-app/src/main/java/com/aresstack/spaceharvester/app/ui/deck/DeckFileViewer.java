package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachment;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachmentLoader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Eine lokale Datei im Command Deck mit den Anhang-Viewern der Jira-/Confluence-Anbindung ({@link AttachmentViewerPanel}:
 * Word mit Überschriften, Tabellen und Bildern, PDF-Seiten, PowerPoint-Folien, Excel-Tabellen, Bilder), 1:1 wie dort.
 * Dahinter liegt der Text, so wie die KI das Dokument liest; der Knopf „Textansicht“ oben rechts schaltet um, geöffnet
 * wird formatiert (Angelo, 24.11.2026).
 */
public final class DeckFileViewer extends JPanel {

    static final String SHOW_TEXT = "Textansicht: das Dokument als Text, so wie die KI es liest";
    static final String SHOW_FORMATTED = "Zurück zur formatierten Ansicht";
    private static final String FORMATTED_CARD = "formatiert";
    private static final String TEXT_CARD = "text";

    private final AttachmentViewerPanel viewer;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final IconButton textToggle = new IconButton(ComposerIcons.document(), SHOW_TEXT);
    private boolean textShown;

    /**
     * @param file  die lokale Datei (auch eine Kopie aus der Quelle)
     * @param name  Anzeigename mit Endung; die Endung wählt den Viewer
     * @param text  die Textansicht (etwa der Editor mit dem Text der Dokumentvorschau); {@code null}: ohne Umschalter
     */
    public DeckFileViewer(Path file, String name, JComponent text) {
        super(new BorderLayout());
        setOpaque(false);
        viewer = new AttachmentViewerPanel(new ReaderAttachment(file.toString(), name, null, size(file)),
                new FileLoader(file), null, null);
        center.setOpaque(false);
        center.add(viewer, FORMATTED_CARD);
        if (text != null) {
            center.add(text, TEXT_CARD);
            textToggle.getAccessibleContext().setAccessibleName("Textansicht");
            textToggle.addActionListener(e -> showText(!textShown));
            JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            toolRow.setOpaque(false);
            toolRow.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            toolRow.add(textToggle);
            add(toolRow, BorderLayout.NORTH);
        }
        add(center, BorderLayout.CENTER);
        showText(false);
    }

    /** Ob die Anhang-Viewer {@code name} formatiert zeigen (PDF, Word, PowerPoint, Excel, Bild). */
    public static boolean shows(String name) {
        return AttachmentViewerPanel.showsFormatted(new ReaderAttachment(name, name, null, -1L));
    }

    /** {@code true}: der Text, wie die KI ihn liest; {@code false}: formatiert. */
    public void showText(boolean text) {
        textShown = text;
        textToggle.marked(text);
        textToggle.setToolTipText(text ? SHOW_FORMATTED : SHOW_TEXT);
        cards.show(center, text ? TEXT_CARD : FORMATTED_CARD);
    }

    public boolean textShown() {
        return textShown;
    }

    public AttachmentViewerPanel viewer() {
        return viewer;
    }

    /** Gibt den Viewer frei (PDF-Seiten, Bilder). */
    public void close() {
        viewer.close();
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    /** Liest die Datei abseits des EDT; Ergebnis und Fehler kommen auf dem EDT. */
    private record FileLoader(Path file) implements ReaderAttachmentLoader {

        @Override
        public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
            Thread reader = new Thread(() -> {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    SwingUtilities.invokeLater(() -> done.accept(bytes));
                } catch (IOException | RuntimeException e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    SwingUtilities.invokeLater(() -> failed.accept(reason));
                }
            }, "deck-file-viewer");
            reader.setDaemon(true);
            reader.start();
        }
    }
}
