package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Markiert in einer neu geladenen Leseansicht, was sich gegenüber dem zuvor gezeigten Stand geändert hat, wie bei Git
 * (Angelo, 17.11.2026): neue Blöcke grün, geänderte blau, entfernte rot und durchgestrichen an ihrer alten Stelle.
 *
 * <p>Verglichen werden die innersten Textblöcke (Absätze, Listenpunkte, Überschriften, Tabellenzellen …) nach ihrem
 * Text. Zwischen zwei gleichen Blöcken gelten entfernte und neue paarweise als geändert, überzählige als neu bzw.
 * entfernt. Bilder ohne Text zählen nicht. Sehr lange Seiten bleiben ohne Markierung.
 */
final class ReadingChanges {

    /** Hintergrund neuer Blöcke. */
    static final String ADDED = Comic.css(Comic.CHANGE_ADDED);
    /** Hintergrund geänderter Blöcke. */
    static final String CHANGED = Comic.css(Comic.CHANGE_CHANGED);
    /** Hintergrund entfernter Blöcke. */
    static final String REMOVED = Comic.css(Comic.CHANGE_REMOVED);
    /** Mehr Blöcke je Stand vergleicht die Markierung nicht. */
    static final int MAX_BLOCKS = 3000;

    /** Blöcke, die nur in einer Liste oder Tabelle stehen dürfen. */
    private static final Set<String> CONTAINED = Set.of("li", "td", "th", "dt", "dd");
    private static final Set<String> BLOCKS = Set.of("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
            "blockquote", "td", "th", "dt", "dd");

    private ReadingChanges() {
    }

    /** Was markiert wurde. */
    record Marked(String html, int added, int changed, int removed) {

        boolean any() {
            return added + changed + removed > 0;
        }
    }

    /** {@code after} mit markierten Änderungen gegenüber {@code before}; unverändert, wenn sich nichts vergleichen lässt. */
    static Marked mark(String before, String after) {
        if (before == null || before.isBlank() || after == null || after.isBlank()) {
            return new Marked(after == null ? "" : after, 0, 0, 0);
        }
        Document old = Jsoup.parse(before);
        Document fresh = Jsoup.parse(after);
        List<Element> x = blocks(old);
        List<Element> y = blocks(fresh);
        if (x.size() > MAX_BLOCKS || y.size() > MAX_BLOCKS) {
            return new Marked(after, 0, 0, 0);
        }
        List<String> a = x.stream().map(ReadingChanges::text).toList();
        List<String> b = y.stream().map(ReadingChanges::text).toList();
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int added = 0;
        int changed = 0;
        int removed = 0;
        List<Element> gone = new ArrayList<>();
        List<Element> come = new ArrayList<>();
        Element previous = null;
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                int[] counts = flush(gone, come, y.get(j), previous, fresh.body());
                added += counts[0];
                changed += counts[1];
                removed += counts[2];
                previous = y.get(j);
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                come.add(y.get(j++));
            } else {
                gone.add(x.get(i++));
            }
        }
        int[] counts = flush(gone, come, null, previous, fresh.body());
        added += counts[0];
        changed += counts[1];
        removed += counts[2];
        if (added + changed + removed == 0) {
            return new Marked(after, 0, 0, 0);
        }
        return new Marked(fresh.body().html(), added, changed, removed);
    }

    /**
     * Schreibt einen Abschnitt zwischen zwei gleichen Blöcken aus: paarweise geändert, Rest neu bzw. entfernt. Entfernte
     * stehen vor {@code next}, dem nächsten gleichen Block; am Ende hinter dem letzten neuen Block, sonst hinter
     * {@code previous}, sonst am Ende des Texts.
     */
    private static int[] flush(List<Element> gone, List<Element> come, Element next, Element previous, Element body) {
        int pairs = Math.min(gone.size(), come.size());
        for (int k = 0; k < pairs; k++) {
            paint(come.get(k), CHANGED, false);
        }
        for (int k = pairs; k < come.size(); k++) {
            paint(come.get(k), ADDED, false);
        }
        Element after = !come.isEmpty() ? come.get(come.size() - 1) : previous;
        Element chain = null;
        for (int k = pairs; k < gone.size(); k++) {
            Element copy = gone.get(k).clone();
            paint(copy, REMOVED, true);
            String name = copy.normalName();
            if (chain != null) {
                chain.after(copy);
            } else if (after != null && after.normalName().equals(name)) {
                // Neben seinesgleichen (Listenpunkt hinter Listenpunkt, Zelle hinter Zelle).
                after.after(copy);
            } else if (next != null && next.normalName().equals(name)) {
                next.before(copy);
            } else {
                if (CONTAINED.contains(name)) {
                    // Ohne passenden Nachbarn stünde ein Listenpunkt oder eine Zelle außerhalb seiner Liste/Tabelle.
                    copy.tagName("p");
                }
                if (next != null) {
                    next.before(copy);
                } else if (after != null) {
                    after.after(copy);
                } else {
                    body.prependChild(copy);
                }
            }
            chain = copy;
        }
        int[] counts = {come.size() - pairs, pairs, Math.max(0, gone.size() - pairs)};
        gone.clear();
        come.clear();
        return counts;
    }

    private static void paint(Element element, String color, boolean struck) {
        String style = element.attr("style");
        String add = "background-color: " + color + ";" + (struck ? " text-decoration: line-through;" : "");
        element.attr("style", style.isBlank() ? add : style + "; " + add);
    }

    /** Die innersten Textblöcke in Textreihenfolge; Blöcke ohne Text zählen nicht. */
    private static List<Element> blocks(Document document) {
        List<Element> found = new ArrayList<>();
        for (Element element : document.body().getAllElements()) {
            if (!BLOCKS.contains(element.normalName()) || text(element).isEmpty()) {
                continue;
            }
            boolean inner = true;
            for (Element child : element.getAllElements()) {
                if (child != element && BLOCKS.contains(child.normalName()) && !text(child).isEmpty()) {
                    inner = false;
                    break;
                }
            }
            if (inner) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element element) {
        return element.text().strip().replaceAll("\\s+", " ");
    }
}
