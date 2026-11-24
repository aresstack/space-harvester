package com.aresstack.spaceharvester.app.ui.deck.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Filtert einen fertig gebauten Baum nach der Suche der Seitenleiste: Ein Knoten bleibt, wenn sein Text die Suche
 * enthält (dann mit allen Kindern) oder ein Nachfahre sie enthält. Groß/klein zählt nicht; leer lässt alles stehen.
 */
public final class TreeQuery {

    private TreeQuery() {
    }

    /** Beschnittener Baum; {@code text} liefert den sichtbaren Text eines Benutzerobjekts. */
    public static void prune(DefaultMutableTreeNode root, String query, Function<Object, String> text) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return;
        }
        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            children.add((DefaultMutableTreeNode) root.getChildAt(i));
        }
        for (DefaultMutableTreeNode child : children) {
            if (!keep(child, needle, text)) {
                root.remove(child);
            }
        }
    }

    private static boolean keep(DefaultMutableTreeNode node, String needle, Function<Object, String> text) {
        if (String.valueOf(text.apply(node.getUserObject())).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            children.add((DefaultMutableTreeNode) node.getChildAt(i));
        }
        boolean any = false;
        for (DefaultMutableTreeNode child : children) {
            if (keep(child, needle, text)) {
                any = true;
            } else {
                node.remove(child);
            }
        }
        return any;
    }
}
