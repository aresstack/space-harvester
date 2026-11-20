package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.text.JTextComponent;
import java.awt.Rectangle;

/**
 * Eine Textauswahl innerhalb eines gerenderten Blocks einer {@link MarkdownMessageView}, die zitiert werden kann: der
 * markierte Text, wenn möglich seine Position im Markdown-Quelltext, und wo die Auswahl auf dem Bildschirm steht
 * (für ein Overlay). Die Rendering-Schicht meldet nur dieses Ereignis; was ein Zitat fachlich bedeutet, entscheidet
 * der Empfänger.
 */
public final class QuoteSelection {

    /** Keine Quellposition bekannt. */
    public static final int UNKNOWN_OFFSET = -1;

    private final String selectedText;
    private final int sourceStart;
    private final int sourceEnd;
    private final JTextComponent anchor;
    private final Rectangle bounds;

    QuoteSelection(String selectedText, int sourceStart, int sourceEnd, JTextComponent anchor, Rectangle bounds) {
        if (selectedText == null || selectedText.trim().isEmpty()) {
            throw new IllegalArgumentException("selectedText must not be blank");
        }
        boolean known = sourceStart >= 0 && sourceEnd > sourceStart;
        this.selectedText = selectedText;
        this.sourceStart = known ? sourceStart : UNKNOWN_OFFSET;
        this.sourceEnd = known ? sourceEnd : UNKNOWN_OFFSET;
        this.anchor = anchor;
        this.bounds = bounds == null ? new Rectangle() : new Rectangle(bounds);
    }

    /** Der markierte Text, genau wie angezeigt. */
    public String selectedText() {
        return selectedText;
    }

    /** Ob die Position im Markdown-Quelltext bekannt ist ({@link #sourceStart()}..{@link #sourceEnd()}). */
    public boolean hasSourceOffsets() {
        return sourceStart != UNKNOWN_OFFSET;
    }

    /** Beginn im Markdown-Quelltext (inklusive) oder {@link #UNKNOWN_OFFSET}. */
    public int sourceStart() {
        return sourceStart;
    }

    /** Ende im Markdown-Quelltext (exklusive) oder {@link #UNKNOWN_OFFSET}. */
    public int sourceEnd() {
        return sourceEnd;
    }

    /** Die Textkomponente, in der markiert wurde. */
    public JTextComponent anchor() {
        return anchor;
    }

    /** Die Fläche der Auswahl in Koordinaten von {@link #anchor()}. */
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    /** Hebt die Markierung auf (die Schreibmarke bleibt am Ende der Auswahl). */
    public void clearHighlight() {
        if (anchor != null) {
            int end = anchor.getSelectionEnd();
            anchor.select(end, end);
        }
    }

    @Override
    public String toString() {
        // Bewusst ohne Text: Verlaufsinhalte gehören nicht in Logs.
        return "QuoteSelection{length=" + selectedText.length() + ", source=" + sourceStart + ".." + sourceEnd + '}';
    }
}
