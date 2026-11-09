package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Übernommen aus dem Enterprise AI Client ({@code proasteion.exedra.ui.reader.ReaderFind}); hier sucht die Suchleiste
 * über der Statuszeile im gerade sichtbaren Bereich (Leseansicht, Text, Eigenschaften …).
 *
 * <p>Ursprünglich: Suchen im Inhalt EINES Lese-Reiters, wie {@code highlightSearch()} im {@code ConfluenceReaderTab} von MainframeMate:
 * alle Fundstellen ohne Groß-/Kleinschreibung gelb markieren und zur ersten rollen.</p>
 *
 * <p>MainframeMate hatte einen einzigen {@code JEditorPane}; hier besteht der Inhalt aus vielen nativen Swing-Komponenten
 * (Absätze und Überschriften als Textflächen, Codeblöcke, Tabellen, Beschriftungen). Gesucht wird deshalb in allen
 * Textkomponenten unterhalb der Wurzel in Bildschirmreihenfolge. Textflächen bekommen je Fundstelle eine gelbe
 * Markierung; Tabellenzellen und Beschriftungen haben keinen Highlighter und werden als Ganzes gelb hinterlegt.
 * Der aktuelle Treffer ist orange statt gelb.</p>
 */
final class ReaderFind {

    /** Alle Treffer leicht markiert (Angelo; MainframeMate hatte volles {@code Color.YELLOW}). */
    static final Color HIT = new Color(0xFFF59D);
    /** Der aktuelle Treffer (eigene Ergänzung, MainframeMates Amber 0xFFB74D). */
    static final Color CURRENT_HIT = new Color(0xFFB74D);

    static final String TABLE_HITS = ReaderFind.class.getName() + ".tableHits";

    private final JComponent root;
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<JLabel, LabelLook> markedLabels = new IdentityHashMap<JLabel, LabelLook>();
    private final List<JTable> markedTables = new ArrayList<JTable>();
    private int current = -1;
    private IntSupplier coveredTop = () -> 0;

    ReaderFind(JComponent root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root;
    }

    /**
     * Markiert alle Fundstellen und zeigt die erste.
     *
     * @return Anzahl der Treffer (0 bei leerer Suche)
     */
    int search(String query) {
        clear();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return 0;
        }
        collect(root, needle);
        if (!hits.isEmpty()) {
            current = 0;
            paint();
            reveal(hits.get(0));
        }
        return hits.size();
    }

    /** Wie hoch oben etwas über dem Inhalt liegt (der Navigationsbanner): Treffer landen darunter, nicht dahinter. */
    void setCoveredTop(IntSupplier coveredTop) {
        this.coveredTop = coveredTop == null ? () -> 0 : coveredTop;
    }

    /** Springt zum nächsten Treffer (nach dem letzten wieder zum ersten). */
    void next() {
        step(1);
    }

    /** Springt zum vorigen Treffer (vor dem ersten zum letzten). */
    void previous() {
        step(-1);
    }

    /** Entfernt alle Markierungen. */
    void clear() {
        for (Hit hit : hits) {
            if (hit.text != null) {
                Highlighter highlighter = hit.text.getHighlighter();
                if (highlighter != null && hit.tag != null) {
                    highlighter.removeHighlight(hit.tag);
                }
            }
        }
        for (Map.Entry<JLabel, LabelLook> entry : markedLabels.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        for (JTable table : markedTables) {
            table.putClientProperty(TABLE_HITS, null);
            table.repaint();
            if (table.getTableHeader() != null) {
                table.getTableHeader().repaint();
            }
        }
        markedLabels.clear();
        markedTables.clear();
        hits.clear();
        current = -1;
    }

    int hitCount() {
        return hits.size();
    }

    /** Index des aktuellen Treffers oder -1. */
    int currentIndex() {
        return current;
    }

    /** Der gefundene Text des Treffers {@code index} (für Tests). */
    String hitText(int index) {
        return hits.get(index).found;
    }

    /** Die Komponente des Treffers {@code index} (für Tests). */
    Component hitComponent(int index) {
        Hit hit = hits.get(index);
        return hit.text != null ? hit.text : hit.label != null ? hit.label : hit.table;
    }

    // ------------------------------------------------------------------ intern

    private void step(int delta) {
        if (hits.isEmpty()) {
            return;
        }
        current = (current + delta + hits.size()) % hits.size();
        paint();
        reveal(hits.get(current));
    }

    private void collect(Component component, String needle) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JTextComponent) {
            collectText((JTextComponent) component, needle);
            return;
        }
        if (component instanceof JTable) {
            collectTable((JTable) component, needle);
            return;
        }
        if (component instanceof JTableHeader) {
            return; // gehört zur Tabelle, die ihre Spaltentitel selbst mitsucht
        }
        if (component instanceof JLabel) {
            collectLabel((JLabel) component, needle);
            return;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collect(child, needle);
            }
        }
    }

    private void collectText(JTextComponent text, String needle) {
        Document document = text.getDocument();
        String content;
        try {
            content = document.getText(0, document.getLength());
        } catch (BadLocationException ex) {
            return;
        }
        // Auf dem Originaltext vergleichen: toLowerCase kann die Länge ändern („İ“), dann stimmten die Stellen nicht.
        int length = needle.length();
        int position = 0;
        while (position + length <= content.length()) {
            if (content.regionMatches(true, position, needle, 0, length)) {
                hits.add(Hit.text(text, position, position + length, content.substring(position, position + length),
                        document, document.getLength()));
                position += length;
            } else {
                position++;
            }
        }
    }

    /**
     * Ob sich der durchsuchte Inhalt seit der Suche geändert hat (anderer Reiter, neu geladenes Dokument): dann
     * passen die gemerkten Stellen nicht mehr und es muss neu gesucht werden.
     */
    boolean stale() {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (hit.text != null && (hit.text.getDocument() != hit.document
                    || hit.text.getDocument().getLength() != hit.length)) {
                return true;
            }
            if (!SwingUtilities.isDescendingFrom(hitComponent(i), root)) {
                return true;
            }
        }
        return false;
    }

    private void collectTable(JTable table, String needle) {
        List<Cell> cells = new ArrayList<Cell>();
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (contains(headerText(table, column), needle)) {
                cells.add(new Cell(-1, column));
            }
        }
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                if (contains(cellText(table, row, column), needle)) {
                    cells.add(new Cell(row, column));
                }
            }
        }
        if (cells.isEmpty()) {
            return;
        }
        installTableMarking(table);
        markedTables.add(table);
        for (Cell cell : cells) {
            String value = cell.row < 0 ? headerText(table, cell.column) : cellText(table, cell.row, cell.column);
            hits.add(Hit.cell(table, cell, value));
        }
    }

    /** Der angezeigte Text der Zelle (über den Renderer, z. B. „Läuft“ statt RUNNING), sonst der Wert. */
    private static String cellText(JTable table, int row, int column) {
        Object value = table.getValueAt(row, column);
        try {
            Component shown = table.getCellRenderer(row, column).getTableCellRendererComponent(table, value, false,
                    false, row, column);
            if (shown instanceof JLabel && ((JLabel) shown).getText() != null) {
                return ((JLabel) shown).getText();
            }
        } catch (RuntimeException ignored) {
            // fällt auf den Wert zurück
        }
        return value == null ? null : String.valueOf(value);
    }

    /** Der angezeigte Spaltentitel. */
    private static String headerText(JTable table, int column) {
        Object value = table.getColumnModel().getColumn(column).getHeaderValue();
        TableCellRenderer renderer = table.getColumnModel().getColumn(column).getHeaderRenderer();
        if (renderer == null && table.getTableHeader() != null) {
            renderer = table.getTableHeader().getDefaultRenderer();
        }
        try {
            Component shown = renderer == null ? null
                    : renderer.getTableCellRendererComponent(table, value, false, false, -1, column);
            if (shown instanceof JLabel && ((JLabel) shown).getText() != null) {
                return ((JLabel) shown).getText();
            }
        } catch (RuntimeException ignored) {
            // fällt auf den Wert zurück
        }
        return value == null ? null : String.valueOf(value);
    }

    private void collectLabel(JLabel label, String needle) {
        String text = label.getText();
        if (text == null || text.startsWith("<html>")) {
            return; // Hinweise des Lesebereichs („Lade …“), kein Dokumentinhalt
        }
        if (text.toLowerCase(Locale.ROOT).contains(needle)) {
            hits.add(Hit.label(label, text));
        }
    }

    private static boolean contains(Object value, String needle) {
        return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Zeichnet alle Treffer neu: gelb, der aktuelle orange. */
    private void paint() {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            Color color = i == current ? CURRENT_HIT : HIT;
            if (hit.text != null) {
                markText(hit, color);
            } else if (hit.label != null) {
                markLabel(hit.label, color);
            }
        }
        for (JTable table : markedTables) {
            Map<Cell, Color> colors = new java.util.HashMap<Cell, Color>();
            for (int i = 0; i < hits.size(); i++) {
                Hit hit = hits.get(i);
                if (hit.table == table) {
                    Color color = i == current ? CURRENT_HIT : HIT;
                    Color known = colors.get(hit.cell);
                    colors.put(hit.cell, CURRENT_HIT.equals(known) ? known : color);
                }
            }
            table.putClientProperty(TABLE_HITS, colors);
            table.repaint();
            if (table.getTableHeader() != null) {
                table.getTableHeader().repaint();
            }
        }
    }

    private static void markText(Hit hit, Color color) {
        Highlighter highlighter = hit.text.getHighlighter();
        if (highlighter == null) {
            // z. B. die Ausschnitte der Quellenliste, die ohne Highlighter gebaut werden
            highlighter = new DefaultHighlighter();
            hit.text.setHighlighter(highlighter);
        }
        try {
            if (hit.tag != null) {
                highlighter.removeHighlight(hit.tag);
            }
            hit.tag = highlighter.addHighlight(hit.start, hit.end,
                    new DefaultHighlighter.DefaultHighlightPainter(color));
        } catch (BadLocationException ignored) {
            hit.tag = null;
        }
    }

    private void markLabel(JLabel label, Color color) {
        if (!markedLabels.containsKey(label)) {
            markedLabels.put(label, new LabelLook(label));
        }
        label.setOpaque(true);
        label.setBackground(color);
        label.repaint();
    }

    /** Hängt einmal je Tabelle einen Renderer davor, der markierte Zellen gelb bzw. orange hinterlegt. */
    private static void installTableMarking(JTable table) {
        TableCellRenderer cells = table.getDefaultRenderer(Object.class);
        if (!(cells instanceof MarkingRenderer)) {
            table.setDefaultRenderer(Object.class, new MarkingRenderer(cells, false));
        }
        JTableHeader header = table.getTableHeader();
        if (header != null && !(header.getDefaultRenderer() instanceof MarkingRenderer)) {
            header.setDefaultRenderer(new MarkingRenderer(header.getDefaultRenderer(), true));
        }
    }

    private void reveal(Hit hit) {
        Component component;
        Rectangle area;
        if (hit.text != null) {
            component = hit.text;
            area = textArea(hit);
        } else if (hit.table != null) {
            if (hit.cell.row < 0) {
                component = hit.table.getTableHeader() == null ? hit.table : hit.table.getTableHeader();
                area = hit.table.getTableHeader() == null ? new Rectangle(0, 0, 1, 1)
                        : hit.table.getTableHeader().getHeaderRect(hit.cell.column);
            } else {
                component = hit.table;
                area = hit.table.getCellRect(hit.cell.row, hit.cell.column, true);
            }
        } else {
            component = hit.label;
            area = new Rectangle(0, 0, hit.label.getWidth(), hit.label.getHeight());
        }
        if (component instanceof JComponent) {
            // Zuerst ein innerer Rollbereich (Codeblock, Tabelle), dann der Lesebereich selbst.
            ((JComponent) component).scrollRectToVisible(area);
        }
        if (SwingUtilities.isDescendingFrom(component, root) && component != root) {
            Rectangle inRoot = SwingUtilities.convertRectangle(component, area, root);
            inRoot.grow(0, 24);
            int covered = Math.max(0, coveredTop.getAsInt());
            inRoot.y -= covered;
            inRoot.height += covered;
            root.scrollRectToVisible(inRoot);
        }
        if (hit.text != null && hit.text.isFocusable()) {
            hit.text.setCaretPosition(Math.max(0, Math.min(hit.start, hit.text.getDocument().getLength())));
        }
    }

    @SuppressWarnings("deprecation") // modelToView: modelToView2D gibt es erst ab Java 9
    private static Rectangle textArea(Hit hit) {
        try {
            Rectangle start = hit.text.modelToView(hit.start);
            Rectangle end = hit.text.modelToView(hit.end);
            if (start != null) {
                return end == null ? start : start.union(end);
            }
        } catch (BadLocationException ignored) {
            // fällt auf die ganze Komponente zurück
        }
        return new Rectangle(0, 0, hit.text.getWidth(), hit.text.getHeight());
    }

    /** Ein Treffer: Textstelle, Tabellenzelle oder Beschriftung. */
    private static final class Hit {
        final JTextComponent text;
        final int start;
        final int end;
        final JTable table;
        final Cell cell;
        final JLabel label;
        final String found;
        /** Dokument und Länge zur Zeit der Suche, um veraltete Stellen zu erkennen. */
        Document document;
        int length;
        Object tag;

        private Hit(JTextComponent text, int start, int end, JTable table, Cell cell, JLabel label, String found) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.table = table;
            this.cell = cell;
            this.label = label;
            this.found = found;
        }

        static Hit text(JTextComponent text, int start, int end, String found, Document document, int length) {
            Hit hit = new Hit(text, start, end, null, null, null, found);
            hit.document = document;
            hit.length = length;
            return hit;
        }

        static Hit cell(JTable table, Cell cell, String found) {
            return new Hit(null, 0, 0, table, cell, null, found);
        }

        static Hit label(JLabel label, String found) {
            return new Hit(null, 0, 0, null, null, label, found);
        }
    }

    /** Eine Tabellenzelle; Zeile -1 ist der Spaltentitel. */
    private static final class Cell {
        final int row;
        final int column;

        Cell(int row, int column) {
            this.row = row;
            this.column = column;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Cell && ((Cell) other).row == row && ((Cell) other).column == column;
        }

        @Override
        public int hashCode() {
            return row * 31 + column;
        }
    }

    /** Wie die Beschriftung vor dem Markieren aussah. */
    private static final class LabelLook {
        final boolean opaque;
        final Color background;

        LabelLook(JLabel label) {
            this.opaque = label.isOpaque();
            this.background = label.getBackground();
        }

        void restore(JLabel label) {
            label.setOpaque(opaque);
            label.setBackground(background);
            label.repaint();
        }
    }

    /** Hinterlegt markierte Zellen; sonst zeichnet der ursprüngliche Renderer unverändert. */
    private static final class MarkingRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;
        private final boolean header;

        MarkingRenderer(TableCellRenderer delegate, boolean header) {
            this.delegate = delegate;
            this.header = header;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            Component component = delegate.getTableCellRendererComponent(table, value, selected, focused, row,
                    column);
            Object property = table == null ? null : table.getClientProperty(TABLE_HITS);
            Color mark = property instanceof Map
                    ? ((Map<Cell, Color>) property).get(new Cell(header ? -1 : row, column)) : null;
            if (header) {
                // Den Renderer des Spaltentitels nicht umfärben (er gehört dem Look and Feel): markiert wird eine Kopie.
                if (mark != null && component instanceof JLabel) {
                    JLabel original = (JLabel) component;
                    JLabel marked = new JLabel(original.getText(), original.getHorizontalAlignment());
                    marked.putClientProperty("html.disable", Boolean.TRUE);
                    marked.setFont(original.getFont());
                    marked.setForeground(original.getForeground());
                    marked.setBorder(original.getBorder());
                    marked.setOpaque(true);
                    marked.setBackground(mark);
                    return marked;
                }
                return component;
            }
            if (component instanceof JComponent) {
                JComponent target = (JComponent) component;
                if (mark != null) {
                    target.setOpaque(true);
                    target.setBackground(mark);
                } else {
                    target.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
                }
            }
            return component;
        }
    }
}
