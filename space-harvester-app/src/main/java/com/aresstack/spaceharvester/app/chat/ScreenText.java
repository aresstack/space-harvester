package com.aresstack.spaceharvester.app.chat;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JScrollBar;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

/**
 * „Was ich sehe, sieht der Bot“ (ADR 0033) für die Bereiche des Command Deck: liest den sichtbaren Teil einer
 * Oberfläche als Text – Beschriftungen, einzeilige Felder, Tabellen, Bäume, Listen und mehrzeilige Texte (Editor,
 * Spool, Dokument). Was nicht angezeigt wird (verdeckte Karten, zugeklappte Äste), fehlt; Kennwortfelder werden nie
 * gelesen. Allgemein für jeden Bereich, damit der Chat überall mindestens die aktuelle Ansicht erklären kann.
 *
 * <p>Läuft auf dem EDT; {@link #capture} wechselt selbst dorthin.
 */
public final class ScreenText {

    /** Höchstens so viele Zeilen je Tabelle, Baum oder Liste. */
    static final int MAX_ROWS = 300;

    /** Ein mehrzeiliger Text der Ansicht (Editor, Spool, Dokument), ganz lesbar über {@code text_lesen}. */
    public record Block(String name, String text) {
    }

    private final StringBuilder view = new StringBuilder();
    private final List<Block> blocks = new ArrayList<>();

    private ScreenText() {
    }

    /** Liest {@code root} auf dem EDT; {@code null} oder unsichtbar ergibt eine leere Ansicht. */
    public static ScreenText capture(Component root) {
        if (SwingUtilities.isEventDispatchThread()) {
            return read(root);
        }
        AtomicReference<ScreenText> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(read(root)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScreenText();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return new ScreenText();
        }
        return result.get();
    }

    private static ScreenText read(Component root) {
        ScreenText screen = new ScreenText();
        if (root != null && root.isVisible()) {
            screen.walk(root);
        }
        return screen;
    }

    /** Die Ansicht als Text; mehrzeilige Texte nur als Hinweis mit Nummer und Anfang. */
    public String view() {
        return view.toString().strip();
    }

    /** Die mehrzeiligen Texte der Ansicht in Anzeige-Reihenfolge. */
    public List<Block> blocks() {
        return List.copyOf(blocks);
    }

    /** Ob die Ansicht gar nichts Lesbares enthält. */
    public boolean isEmpty() {
        return view.isEmpty() && blocks.isEmpty();
    }

    /** Die Ansicht samt Anfang jedes Textes, höchstens {@code max} Zeichen (für die Systemnachricht). */
    public String format(int max, int perBlock) {
        StringBuilder b = new StringBuilder(view());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            b.append("\n\n[Text ").append(i + 1).append(block.name().isEmpty() ? "" : " – " + block.name())
                    .append(", ").append(block.text().length()).append(" Zeichen");
            if (block.text().length() > perBlock) {
                b.append(", hier nur der Anfang; ganz mit text_lesen nr=").append(i + 1);
            }
            b.append("]\n").append(ChatKnowledge.excerpt(block.text(), perBlock));
        }
        String text = b.toString().strip();
        int end = com.aresstack.spaceharvester.app.runtime.ModelBudget.fit(text, 0, max);
        return end >= text.length() ? text : text.substring(0, end) + "\n… (Ansicht gekürzt)";
    }

    private void walk(Component component) {
        if (!component.isVisible() || component instanceof JScrollBar || component instanceof JTableHeader) {
            return;
        }
        switch (component) {
            case JPasswordField ignored -> line("Kennwortfeld (nicht lesbar)");
            case JTextComponent text -> text(text);
            case JTable table -> table(table);
            case JTree tree -> tree(tree);
            case JList<?> list -> list(list);
            case JLabel label -> line(plain(label.getText()));
            case JToggleButton toggle -> {
                if (toggle.isSelected() && !plain(toggle.getText()).isEmpty()) {
                    line("Gewählt: " + plain(toggle.getText()));
                }
            }
            case AbstractButton ignored -> {
                // Knöpfe sind Bedienung, kein Inhalt.
            }
            case Container container -> {
                for (Component child : container.getComponents()) {
                    walk(child);
                }
            }
            default -> {
            }
        }
    }

    private void text(JTextComponent text) {
        String content;
        try {
            // Das Dokument, nicht getText(): HTML-Ansichten liefern sonst Auszeichnung statt Text.
            content = text.getDocument().getText(0, text.getDocument().getLength());
        } catch (javax.swing.text.BadLocationException e) {
            return;
        }
        if (content == null || content.isBlank()) {
            return;
        }
        String name = name(text);
        if (text instanceof javax.swing.JTextField) {
            line((name.isEmpty() ? "Feld" : name) + ": " + content.strip());
        } else if (!text.isEditable() && content.length() < 400 && !content.contains("\n")) {
            line(content.strip());
        } else {
            blocks.add(new Block(name, content));
            line("[Text " + blocks.size() + (name.isEmpty() ? "" : " – " + name) + ", " + content.length()
                    + " Zeichen]");
        }
    }

    private void table(JTable table) {
        int columns = table.getColumnCount();
        int rows = table.getRowCount();
        StringBuilder head = new StringBuilder("Tabelle").append(name(table).isEmpty() ? "" : " " + name(table))
                .append(" (").append(rows).append(rows == 1 ? " Zeile" : " Zeilen").append(", * = ausgewählt): ");
        for (int c = 0; c < columns; c++) {
            head.append(c == 0 ? "" : " | ").append(plain(table.getColumnName(c)));
        }
        line(head.toString());
        for (int r = 0; r < Math.min(rows, MAX_ROWS); r++) {
            StringBuilder row = new StringBuilder(table.isRowSelected(r) ? "* " : "  ");
            for (int c = 0; c < columns; c++) {
                row.append(c == 0 ? "" : " | ").append(cell(table, r, c));
            }
            line(row.toString());
        }
        more(rows);
    }

    private static String cell(JTable table, int row, int column) {
        try {
            TableCellRenderer renderer = table.getCellRenderer(row, column);
            Component shown = table.prepareRenderer(renderer, row, column);
            if (shown instanceof JLabel label && label.getText() != null) {
                return plain(label.getText());
            }
            if (shown instanceof AbstractButton button) {
                return button.isSelected() ? "ja" : "nein";
            }
        } catch (RuntimeException ignored) {
            // Ein eigenwilliger Renderer: dann der Wert selbst.
        }
        Object value = table.getValueAt(row, column);
        return value == null ? "" : plain(value.toString());
    }

    private void tree(JTree tree) {
        int rows = tree.getRowCount();
        line("Baum" + (name(tree).isEmpty() ? "" : " " + name(tree)) + " (aufgeklappte Einträge, * = ausgewählt):");
        int top = rows == 0 ? 0 : tree.getPathForRow(0).getPathCount();
        for (int r = 0; r < Math.min(rows, MAX_ROWS); r++) {
            TreePath path = tree.getPathForRow(r);
            Object node = path.getLastPathComponent();
            String text = tree.convertValueToText(node, tree.isRowSelected(r), tree.isExpanded(r),
                    tree.getModel().isLeaf(node), r, false);
            Component shown = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, tree.isRowSelected(r),
                    tree.isExpanded(r), tree.getModel().isLeaf(node), r, false);
            if (shown instanceof JLabel label && label.getText() != null && !label.getText().isBlank()) {
                text = label.getText();
            }
            line((tree.isRowSelected(r) ? "* " : "  ") + "  ".repeat(Math.max(0, path.getPathCount() - top))
                    + plain(text));
        }
        more(rows);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void list(JList list) {
        int rows = list.getModel().getSize();
        line("Liste" + (name(list).isEmpty() ? "" : " " + name(list)) + " (" + rows + ", * = ausgewählt):");
        ListCellRenderer renderer = list.getCellRenderer();
        for (int r = 0; r < Math.min(rows, MAX_ROWS); r++) {
            Object value = list.getModel().getElementAt(r);
            String text = value == null ? "" : value.toString();
            Component shown = renderer.getListCellRendererComponent(list, value, r, list.isSelectedIndex(r), false);
            if (shown instanceof JLabel label && label.getText() != null) {
                text = label.getText();
            }
            line((list.isSelectedIndex(r) ? "* " : "  ") + plain(text));
        }
        more(rows);
    }

    private void more(int rows) {
        if (rows > MAX_ROWS) {
            line("  … " + (rows - MAX_ROWS) + " weitere nicht gezeigt");
        }
    }

    private void line(String text) {
        if (text != null && !text.isBlank()) {
            view.append(text.stripTrailing()).append('\n');
        }
    }

    private static String name(JComponent component) {
        String name = component.getAccessibleContext().getAccessibleName();
        return name == null ? "" : plain(name);
    }

    /** Ohne HTML-Auszeichnung und Zeilenumbrüche. */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.toLowerCase(Locale.ROOT).startsWith("<html") ? text.replaceAll("<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&") : text;
        return stripped.replaceAll("\\s+", " ").strip();
    }
}
