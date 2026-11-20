package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Wiederverwendbare Assistentennachricht mit gedrosselten Streaming-Aktualisierungen (aus askai-java8
 * {@code MarkdownMessageView}).
 *
 * <p>Während des Streamings wird nicht nach jedem Delta neu gerendert, sondern höchstens alle
 * {@value #MIN_STREAM_RENDER_DELAY_MILLIS} ms; dauert ein Rendering länger, wächst die Pause auf das Dreifache der
 * gemessenen Renderzeit (bis {@value #MAX_STREAM_RENDER_DELAY_MILLIS} ms). So bleibt die Oberfläche auch bei langen
 * Antworten und dichten Deltas flüssig, zeigt den Text aber laufend an. Mermaid-Diagramme werden erst nach dem
 * Ende des Streamings gerendert.</p>
 */
public final class MarkdownMessageView extends JPanel implements WidthAwareHeight {

    /**
     * Client-Property der gerenderten Blöcke oberster Ebene: ihre Startposition (Zeichen) im Markdown-Text, sofern
     * {@link #sourceOffsetsExact()}. Damit springt der Lesebereich zu Quellpositionen der Dokumentnavigation.
     */
    public static final String SOURCE_START = "enterpriseai.markdown.sourceStart";

    private static final int MIN_STREAM_RENDER_DELAY_MILLIS = 90;
    private static final int MAX_STREAM_RENDER_DELAY_MILLIS = 2000;
    private static final int RENDER_COST_FACTOR = 3;

    private final StringBuilder markdown = new StringBuilder();
    private final FlexmarkSwingRenderer renderer;
    private final Timer renderTimer;
    private boolean streaming;
    private boolean sourceOffsetsExact;
    private QuoteSelectionListener quoteListener;
    private final SelectionWatcher selectionWatcher = new SelectionWatcher();

    public MarkdownMessageView(MarkdownTheme theme, DesktopLinkOpener linkOpener,
                               MermaidImageRenderer mermaidImageRenderer) {
        if (theme == null || linkOpener == null || mermaidImageRenderer == null) {
            throw new IllegalArgumentException("theme, linkOpener and mermaidImageRenderer must not be null");
        }
        this.renderer = new FlexmarkSwingRenderer(theme, linkOpener, mermaidImageRenderer);
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        this.renderTimer = new Timer(MIN_STREAM_RENDER_DELAY_MILLIS, event -> renderThrottled());
        this.renderTimer.setRepeats(false);
        renderNow();
    }

    public void setMarkdown(String value) {
        assertEventDispatchThread();
        streaming = false;
        renderTimer.stop();
        markdown.setLength(0);
        if (value != null) {
            markdown.append(value);
        }
        renderNow();
    }

    public void startStreaming() {
        assertEventDispatchThread();
        streaming = true;
        markdown.setLength(0);
        renderNow();
    }

    public void appendMarkdownDelta(String delta) {
        assertEventDispatchThread();
        if (delta == null || delta.isEmpty()) {
            return;
        }
        markdown.append(delta);
        if (!streaming) {
            renderNow();
        } else if (!renderTimer.isRunning()) {
            renderTimer.start(); // läuft der Timer schon, erfasst das anstehende Rendering auch dieses Delta
        }
    }

    public void finishStreaming() {
        assertEventDispatchThread();
        streaming = false;
        renderTimer.stop();
        renderNow();
    }

    public boolean isStreaming() {
        return streaming;
    }

    public String getMarkdown() {
        return markdown.toString();
    }

    /**
     * Berechnet deterministisch die Höhe dieser Ansicht bei der gegebenen Breite, damit ein Wirt, der die Zielbreite
     * kennt (eine Sprechblase/Zeile), schon bei der ersten Messung die richtige Höhe bekommt — ohne auf einen
     * späteren Swing-Layoutdurchlauf zu warten, der einen umbrochenen Absatz korrigiert.
     */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        Component content = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        int contentHeight = MarkdownHeights.forWidth(content, width - insets.left - insets.right);
        return contentHeight + insets.top + insets.bottom;
    }

    /**
     * {@code true}, wenn die Blöcke oberster Ebene ({@link #renderedBlocks()}) ihre Startposition im übergebenen
     * Markdown-Text tragen ({@link #SOURCE_START}); sonst wurde der Text vor dem Rendern umgeformt.
     */
    public boolean sourceOffsetsExact() {
        return sourceOffsetsExact;
    }

    /** Die gerenderten Blöcke oberster Ebene in Lesereihenfolge (ohne Abstandhalter). */
    public java.util.List<javax.swing.JComponent> renderedBlocks() {
        java.util.List<javax.swing.JComponent> blocks = new java.util.ArrayList<javax.swing.JComponent>();
        Component content = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (content instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) content).getComponents()) {
                if (child instanceof javax.swing.JComponent && !(child instanceof javax.swing.Box.Filler)) {
                    blocks.add((javax.swing.JComponent) child);
                }
            }
        }
        return blocks;
    }

    /**
     * Meldet Textauswahlen in den gerenderten Blöcken (Zitieren): nach dem Loslassen der Maus mit markiertem Text
     * {@link QuoteSelectionListener#selectionMade}, sonst {@link QuoteSelectionListener#selectionCleared}. Eine
     * Auswahl reicht nie über mehrere Blöcke (je Block eine eigene Textkomponente). {@code null} schaltet ab.
     */
    public void setQuoteSelectionListener(QuoteSelectionListener listener) {
        this.quoteListener = listener;
    }

    /**
     * Die zitierbare Auswahl einer Textkomponente dieser Ansicht oder {@code null} ohne markierten Text. Die
     * Quellposition wird nur angegeben, wenn sie eindeutig ist: die Blöcke tragen ihre Startposition
     * ({@link #sourceOffsetsExact()}) und der markierte Text steht genau einmal wörtlich im Quelltext seines Blocks
     * (Inline-Formatierung wie {@code **fett**} dazwischen macht die Position unbekannt, nicht falsch).
     */
    public QuoteSelection selectionOf(JTextComponent component) {
        if (component == null) {
            return null;
        }
        String selected = component.getSelectedText();
        if (selected == null || selected.trim().isEmpty()) {
            return null;
        }
        int[] source = sourceRange(component, selected);
        return new QuoteSelection(selected, source[0], source[1], component, selectionBounds(component));
    }

    private int[] sourceRange(JTextComponent component, String selected) {
        int[] unknown = {QuoteSelection.UNKNOWN_OFFSET, QuoteSelection.UNKNOWN_OFFSET};
        if (!sourceOffsetsExact) {
            return unknown;
        }
        java.util.List<javax.swing.JComponent> blocks = renderedBlocks();
        for (int index = 0; index < blocks.size(); index++) {
            javax.swing.JComponent block = blocks.get(index);
            if (block != component && !SwingUtilities.isDescendingFrom(component, block)) {
                continue;
            }
            Object start = block.getClientProperty(SOURCE_START);
            if (!(start instanceof Integer)) {
                return unknown;
            }
            int blockStart = (Integer) start;
            int blockEnd = markdown.length();
            for (int next = index + 1; next < blocks.size(); next++) {
                Object nextStart = blocks.get(next).getClientProperty(SOURCE_START);
                if (nextStart instanceof Integer) {
                    blockEnd = (Integer) nextStart;
                    break;
                }
            }
            if (blockStart < 0 || blockEnd > markdown.length() || blockStart >= blockEnd) {
                return unknown;
            }
            String blockSource = markdown.substring(blockStart, blockEnd);
            int found = blockSource.indexOf(selected);
            if (found < 0 || found != blockSource.lastIndexOf(selected)) {
                return unknown;
            }
            return new int[]{blockStart + found, blockStart + found + selected.length()};
        }
        return unknown;
    }

    private static Rectangle selectionBounds(JTextComponent component) {
        try {
            Rectangle first = component.modelToView(component.getSelectionStart());
            Rectangle last = component.modelToView(component.getSelectionEnd());
            if (first == null || last == null) {
                return new Rectangle(0, 0, component.getWidth(), component.getHeight());
            }
            return first.union(last);
        } catch (BadLocationException outside) {
            return new Rectangle(0, 0, component.getWidth(), component.getHeight());
        }
    }

    private void watchSelections(Component component) {
        if (component instanceof JTextComponent) {
            component.addMouseListener(selectionWatcher);
            ((JTextComponent) component).addCaretListener(selectionWatcher);
        }
        if (component instanceof SelectableTextHost) {
            // Watch texts that enter the component tree only while being selected (table cells).
            for (JTextComponent text : ((SelectableTextHost) component).selectableTexts()) {
                watchSelections(text);
            }
        }
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) {
                watchSelections(child);
            }
        }
    }

    /** Ein Beobachter für alle Textkomponenten der Ansicht (sie werden bei jedem Rendering neu angelegt). */
    private final class SelectionWatcher extends MouseAdapter implements CaretListener {

        @Override
        public void mousePressed(MouseEvent event) {
            if (quoteListener != null) {
                quoteListener.selectionCleared();
            }
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (quoteListener == null || !(event.getSource() instanceof JTextComponent)) {
                return;
            }
            QuoteSelection selection = selectionOf((JTextComponent) event.getSource());
            if (selection == null) {
                quoteListener.selectionCleared();
            } else {
                quoteListener.selectionMade(selection);
            }
        }

        @Override
        public void caretUpdate(CaretEvent event) {
            if (quoteListener != null && event.getDot() == event.getMark()) {
                quoteListener.selectionCleared();
            }
        }
    }

    private static boolean hasSourceOffsets(JPanel rendered) {
        for (Component child : rendered.getComponents()) {
            if (child instanceof javax.swing.JComponent
                    && ((javax.swing.JComponent) child).getClientProperty(SOURCE_START) != null) {
                return true;
            }
        }
        return rendered.getComponentCount() == 0;
    }

    private void renderThrottled() {
        long started = System.nanoTime();
        renderNow();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        long delay = Math.max(MIN_STREAM_RENDER_DELAY_MILLIS,
                Math.min(MAX_STREAM_RENDER_DELAY_MILLIS, elapsedMillis * RENDER_COST_FACTOR));
        renderTimer.setInitialDelay((int) delay);
    }

    private void renderNow() {
        removeAll();
        boolean complete = !streaming;
        String normalized = MarkdownResponseNormalizer.normalize(markdown.toString(), complete);
        JPanel rendered = renderer.render(normalized, complete);
        sourceOffsetsExact = normalized.equals(markdown.toString()) && hasSourceOffsets(rendered);
        add(rendered, BorderLayout.CENTER);
        watchSelections(rendered);
        revalidate();
        repaint();
    }

    private static void assertEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("MarkdownMessageView must be updated on the Swing EDT.");
        }
    }
}
