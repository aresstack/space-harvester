package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.changelog.ParagraphAlignment;
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
 * Text, mit derselben Zuordnung wie der Reiter „Text“ ({@link ParagraphAlignment}). Bilder ohne Text zählen nicht.
 * Ist der geänderte Teil für den Vergleich zu groß, bleibt die Seite ohne Markierung.
 */
final class ReadingChanges {

    /** Hintergrund neuer Blöcke. */
    static final String ADDED = Comic.css(Comic.CHANGE_ADDED);
    /** Hintergrund geänderter Blöcke. */
    static final String CHANGED = Comic.css(Comic.CHANGE_CHANGED);
    /** Hintergrund entfernter Blöcke. */
    static final String REMOVED = Comic.css(Comic.CHANGE_REMOVED);
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

    /**
     * {@code after} mit markierten Änderungen gegenüber {@code before}; unverändert, wenn sich nichts vergleichen lässt.
     * Die Zuordnung der Blöcke kommt aus dem Core ({@link ParagraphAlignment}), dieselbe wie im Reiter „Text“.
     */
    static Marked mark(String before, String after) {
        if (before == null || before.isBlank() || after == null || after.isBlank()) {
            return new Marked(after == null ? "" : after, 0, 0, 0);
        }
        Document old = Jsoup.parse(before);
        Document fresh = Jsoup.parse(after);
        List<Element> x = blocks(old);
        List<Element> y = blocks(fresh);
        ParagraphAlignment.Alignment alignment = ParagraphAlignment.align(
                x.stream().map(ReadingChanges::text).toList(), y.stream().map(ReadingChanges::text).toList());
        if (!(alignment instanceof ParagraphAlignment.Aligned aligned)) {
            // Zu groß für den Vergleich: der neue Stand ohne Farben.
            return new Marked(after, 0, 0, 0);
        }
        int added = 0;
        int changed = 0;
        int removed = 0;
        List<Element> gone = new ArrayList<>();
        Element previous = null;
        for (ParagraphAlignment.Step step : aligned.steps()) {
            if (step.kind() == ParagraphAlignment.Kind.REMOVE) {
                gone.add(x.get(step.before()));
                continue;
            }
            Element current = y.get(step.after());
            removed += insertRemoved(gone, previous, current, fresh.body());
            switch (step.kind()) {
                case CHANGE -> {
                    paint(current, CHANGED, false);
                    changed++;
                }
                case ADD -> {
                    paint(current, ADDED, false);
                    added++;
                }
                default -> {
                }
            }
            previous = current;
        }
        removed += insertRemoved(gone, previous, null, fresh.body());
        if (added + changed + removed == 0) {
            return new Marked(after, 0, 0, 0);
        }
        return new Marked(fresh.body().html(), added, changed, removed);
    }

    /**
     * Setzt die entfallenen Blöcke als rote, durchgestrichene Kopien an ihre alte Stelle zwischen {@code previous} und
     * {@code next} (beide aus dem neuen Stand, je {@code null} am Anfang bzw. Ende): neben einen Block derselben Art,
     * sonst vor den nächsten; Listenpunkte und Zellen ohne passenden Nachbarn werden zu Absätzen.
     */
    private static int insertRemoved(List<Element> gone, Element previous, Element next, Element body) {
        Element chain = null;
        for (Element block : gone) {
            Element copy = block.clone();
            paint(copy, REMOVED, true);
            String name = copy.normalName();
            if (chain != null) {
                chain.after(copy);
            } else if (previous != null && previous.normalName().equals(name)) {
                // Neben seinesgleichen (Listenpunkt hinter Listenpunkt, Zelle hinter Zelle).
                previous.after(copy);
            } else if (next != null && next.normalName().equals(name)) {
                next.before(copy);
            } else {
                if (CONTAINED.contains(name)) {
                    // Ohne passenden Nachbarn stünde ein Listenpunkt oder eine Zelle außerhalb seiner Liste/Tabelle.
                    copy.tagName("p");
                }
                if (next != null) {
                    next.before(copy);
                } else if (previous != null) {
                    previous.after(copy);
                } else {
                    body.prependChild(copy);
                }
            }
            chain = copy;
        }
        int count = gone.size();
        gone.clear();
        return count;
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
