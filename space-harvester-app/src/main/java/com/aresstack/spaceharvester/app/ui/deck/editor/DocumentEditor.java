package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Der gemeinsame Editor des Command Deck (#1141/#1142) für ein Dokument: RSyntaxTextArea mit Zeilennummern und
 * Syntaxhervorhebung nach Dateiendung, Strg+S speichert. Hat jemand die Datei seit dem Öffnen geändert, steht oben eine
 * Meldung mit „Trotzdem speichern“. Jeder Arbeitsbereich (Dateien, später FTP und NDV) öffnet ihn mit einer eigenen
 * {@link com.aresstack.spaceharvester.deck.editor.DocumentStore Ablage}; Laden und Speichern laufen im Hintergrund,
 * der Zustand kommt per Beobachter aus der {@link EditorSession}.
 */
public final class DocumentEditor extends JPanel {

    private final EditorSession session;
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll;
    private final MessagePlate plate = new MessagePlate();
    private final java.util.function.Consumer<EditorSession> observer =
            changed -> SwingUtilities.invokeLater(this::render);
    /** Welcher Ladestand ({@link EditorSession#revision()}) im Editor steht. */
    private long shownRevision;
    /** Während der Editor selbst Text setzt, ist das keine Bearbeitung. */
    private boolean filling;
    /** Fasst schnelles Tippen zusammen, bevor die Natural-Blöcke neu gefärbt werden. */
    private final javax.swing.Timer blocks;
    private final Runnable recolor = () -> SwingUtilities.invokeLater(this::recolorBlocks);

    public DocumentEditor(EditorSession session) {
        super(new BorderLayout());
        this.session = session;
        setOpaque(true);
        setBackground(Color.WHITE);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setTabSize(4);
        area.setHighlightCurrentLine(true);
        area.setCurrentLineHighlightColor(Comic.NOTICE);
        area.setSyntaxEditingStyle(SyntaxStyles.forExtension(extension(session.name())));
        blocks = new javax.swing.Timer(300, e -> NaturalBlockHighlighter.apply(area));
        blocks.setRepeats(false);
        area.setEditable(false);
        area.getAccessibleContext().setAccessibleName(session.name());
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Nur Attribute (Hervorhebung), kein Text.
            }
        });
        area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK), "deck-save");
        area.getActionMap().put("deck-save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                session.save();
            }
        });
        scroll = new RTextScrollPane(area, true);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // Balken nur, wenn der Text nicht passt.
        scroll.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getGutter().setBackground(Comic.SURFACE);
        scroll.getGutter().setLineNumberColor(Comic.MUTED);
        scroll.getGutter().setBorderColor(Comic.LINE);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        top.add(plate, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (isDisplayable()) {
                    watch();
                    render();
                } else {
                    session.unobserve(observer);
                    NaturalBlockHighlighter.unobserve(recolor);
                }
            }
        });
        watch();
        render();
    }

    /**
     * Meldet sich bei Sitzung und Blockfarben an, höchstens einmal: Konstruktor und „sichtbar geworden“ rufen das
     * beide; ein doppelt angemeldeter Beobachter würde beim Schließen nur einmal abgemeldet und hielte den Editor fest.
     */
    void watch() {
        session.unobserve(observer);
        NaturalBlockHighlighter.unobserve(recolor);
        session.observe(observer);
        NaturalBlockHighlighter.observe(recolor);
    }

    public EditorSession session() {
        return session;
    }

    /** Setzt den Fokus in den Text. */
    public void focusText() {
        area.requestFocusInWindow();
    }

    /** Der angezeigte Text (auch für Tests der Arbeitsbereiche). */
    public String text() {
        return area.getText();
    }

    /** Tippt Text an die Schreibmarke, wie eine Eingabe (für Tests). */
    public void type(String text) {
        area.replaceSelection(text);
    }

    /** Der Satz der Meldung oben, leer ohne Meldung (für Tests). */
    public String plateSentence() {
        return plate.sentence();
    }

    public void clickPlateAction() {
        plate.clickAction();
    }

    public String syntaxStyle() {
        return area.getSyntaxEditingStyle();
    }

    private void edited() {
        if (!filling) {
            session.edit(area.getText());
        }
        // Wie SplitPreviewTab.scheduleSubroutineHighlighting in MainframeMate: nach 300 ms Ruhe neu einfärben.
        if (natural()) {
            blocks.restart();
        }
    }

    private boolean natural() {
        return SyntaxStyles.NATURAL.equals(area.getSyntaxEditingStyle());
    }

    /** Färbt die Natural-Blöcke sofort neu (etwa nach geänderten Farben in den Einstellungen). */
    public void recolorBlocks() {
        if (natural()) {
            NaturalBlockHighlighter.apply(area);
        }
    }

    private void render() {
        EditorSession.State state = session.state();
        // Neuer Inhalt nur nach einem Laden; nach dem Speichern bleibt der Text, wie er im Editor steht. Gezählt wird
        // über die Ladenummer, nicht über Zustandswechsel, die zwischen zwei EDT-Durchläufen verloren gehen können.
        long revision = session.revision();
        if (revision != shownRevision && state != EditorSession.State.LOADING) {
            shownRevision = revision;
            fill(session.document().map(EditorDocument::text).orElse(""));
        }
        area.setEditable(state == EditorSession.State.READY || state == EditorSession.State.CONFLICT);
        if (state == EditorSession.State.CONFLICT) {
            plate.show(session.problem().orElse("Die Datei wurde geändert.") + " Speichern überschreibt das.",
                    "Trotzdem speichern", session::overwrite);
        } else if (state == EditorSession.State.FAILED) {
            plate.show(session.problem().orElse("Laden gescheitert."), null, null);
        } else {
            plate.dismiss();
        }
    }

    private void fill(String text) {
        filling = true;
        try {
            area.setText(text);
            area.setCaretPosition(0);
            area.discardAllEdits();
        } finally {
            filling = false;
        }
        if (natural()) {
            SwingUtilities.invokeLater(() -> NaturalBlockHighlighter.apply(area));
        }
        session.edit(area.getText());
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }
}
