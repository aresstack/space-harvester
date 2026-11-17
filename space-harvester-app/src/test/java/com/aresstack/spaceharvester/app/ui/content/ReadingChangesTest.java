package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/** Angelo, 17.11.2026: Nach „Seite neu laden“ wie bei Git markiert, grün neu, blau geändert, rot entfernt. */
class ReadingChangesTest {

    @Test
    void addedChangedAndRemovedBlocksAreMarkedInPlace() {
        String before = "<h1>Titel</h1><p>Eins</p><p>Zwei</p><p>Drei</p><p>Vier</p>";
        String after = "<h1>Titel</h1><p>Eins</p><p>Zwei, neu formuliert</p><p>Vier</p><p>Fünf</p>";

        ReadingChanges.Marked marked = ReadingChanges.mark(before, after);

        // „Zwei“ und „Drei“ fallen weg, „Zwei, neu formuliert“ kommt: ein Paar geändert, einer entfernt.
        assertThat(marked.changed()).isEqualTo(1);
        assertThat(marked.removed()).isEqualTo(1);
        assertThat(marked.added()).isEqualTo(1);
        Document document = Jsoup.parse(marked.html());
        assertThat(style(document, "Titel")).isEmpty();
        assertThat(style(document, "Eins")).isEmpty();
        assertThat(style(document, "Zwei, neu formuliert")).contains(ReadingChanges.CHANGED);
        assertThat(style(document, "Drei")).contains(ReadingChanges.REMOVED).contains("line-through");
        assertThat(style(document, "Vier")).isEmpty();
        assertThat(style(document, "Fünf")).contains(ReadingChanges.ADDED);
        // Der entfernte Absatz steht an seiner alten Stelle, vor „Vier“.
        assertThat(document.body().text()).isEqualTo("Titel Eins Zwei, neu formuliert Drei Vier Fünf");
    }

    @Test
    void removedBlocksAtTheEndAndAtTheStartKeepTheirOrder() {
        ReadingChanges.Marked end = ReadingChanges.mark("<p>A</p><p>B</p><p>C</p>", "<p>A</p>");
        assertThat(Jsoup.parse(end.html()).body().text()).isEqualTo("A B C");
        assertThat(end.removed()).isEqualTo(2);

        ReadingChanges.Marked start = ReadingChanges.mark("<p>A</p><p>B</p><p>C</p>", "<p>C</p>");
        assertThat(Jsoup.parse(start.html()).body().text()).isEqualTo("A B C");
    }

    @Test
    void theMarkedPageKeepsTheShellOfThePreparedPage() {
        // So wie ReadingPane.prepare den Stand liefert.
        String before = "<html><body><p>Eins</p><p>Zwei</p></body></html>";
        String after = "<html><body><p>Eins</p><p>Zwei</p><p>Drei</p></body></html>";

        ReadingChanges.Marked marked = ReadingChanges.mark(before, after);

        assertThat(marked.any()).isTrue();
        assertThat(marked.html()).startsWith("<html><body>").endsWith("</body></html>");
        assertThat(after).startsWith("<html><body>").endsWith("</body></html>");
    }

    @Test
    void aRemovedParagraphWithAnImageKeepsOnlyItsText() {
        String before = "<p>Eins</p><p><img src=\"file:/alt/bild.png\" width=\"400\">Bildunterschrift</p><p>Zwei</p>";
        String after = "<p>Eins</p><p>Zwei</p>";

        ReadingChanges.Marked marked = ReadingChanges.mark(before, after);

        Document document = Jsoup.parse(marked.html());
        assertThat(marked.removed()).isEqualTo(1);
        assertThat(style(document, "Bildunterschrift")).contains(ReadingChanges.REMOVED).contains("line-through");
        assertThat(document.select("img")).isEmpty();
        assertThat(document.body().text()).isEqualTo("Eins Bildunterschrift Zwei");
    }

    @Test
    void anUnchangedTextStaysAsItIs() {
        String html = "<p>Gleich</p><ul><li>Punkt</li></ul>";
        ReadingChanges.Marked marked = ReadingChanges.mark(html, html);
        assertThat(marked.any()).isFalse();
        assertThat(marked.html()).isEqualTo(html);
    }

    @Test
    void aChangedPartTooLargeForTheComparisonStaysUnmarked() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            before.append("<p>alt ").append(i).append("</p>");
            after.append("<p>neu ").append(i).append("</p>");
        }
        ReadingChanges.Marked marked = ReadingChanges.mark(before.toString(), after.toString());
        assertThat(marked.any()).isFalse();
        assertThat(marked.html()).isEqualTo(after.toString());
    }

    @Test
    void tableCellsAndListItemsAreMarkedOnTheirOwn() {
        String before = "<table><tr><td>Montag</td><td>18 Uhr</td></tr></table><ul><li>Eins</li></ul>";
        String after = "<table><tr><td>Montag</td><td>19 Uhr</td></tr></table><ul><li>Eins</li><li>Zwei</li></ul>";
        Document document = Jsoup.parse(ReadingChanges.mark(before, after).html());
        assertThat(style(document, "Montag")).isEmpty();
        assertThat(style(document, "19 Uhr")).contains(ReadingChanges.CHANGED);
        assertThat(style(document, "Eins")).isEmpty();
        assertThat(style(document, "Zwei")).contains(ReadingChanges.ADDED);
    }

    @Test
    void aRemovedListItemStaysInItsList() {
        Document document = Jsoup.parse(ReadingChanges.mark("<ul><li>Eins</li><li>Zwei</li></ul><p>Text</p>",
                "<ul><li>Eins</li></ul><p>Text</p>").html());
        Element removed = document.select("li").get(1);
        assertThat(removed.text()).isEqualTo("Zwei");
        assertThat(removed.parent().normalName()).isEqualTo("ul");
        assertThat(removed.attr("style")).contains(ReadingChanges.REMOVED);
    }

    /** AT: Die gelbe Suchmarkierung bleibt über einem farbigen Absatz sichtbar. */
    @Test
    void theYellowSearchMarkStaysVisibleOverAColouredBlock() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            javax.swing.JEditorPane pane = new javax.swing.JEditorPane();
            pane.setContentType("text/html");
            pane.setText("<html><body><p style=\"background-color: " + ReadingChanges.CHANGED
                    + ";\">Vorher&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;nachher</p></body></html>");
            pane.setSize(400, 120);
            String text;
            try {
                text = pane.getDocument().getText(0, pane.getDocument().getLength());
                int start = text.indexOf("Vorher") + "Vorher".length() + 1;
                int end = start + 6;
                java.awt.Color hit = new java.awt.Color(0xFFF59D);
                pane.getHighlighter().addHighlight(start, end,
                        new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(hit));
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(400, 120,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                pane.paint(g);
                g.dispose();
                java.awt.geom.Rectangle2D first = pane.modelToView2D(start);
                java.awt.geom.Rectangle2D last = pane.modelToView2D(end);
                int x = (int) ((first.getX() + last.getX()) / 2);
                int y = (int) (first.getY() + first.getHeight() / 2);
                assertThat(new java.awt.Color(image.getRGB(x, y))).as("Suchmarkierung über dem blauen Absatz")
                        .isEqualTo(hit);
                int bx = (int) last.getX() + 40;
                assertThat(new java.awt.Color(image.getRGB(Math.min(bx, 390), y)))
                        .isNotEqualTo(hit);
            } catch (javax.swing.text.BadLocationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static String style(Document document, String text) {
        for (Element element : document.body().getAllElements()) {
            if (element != document.body() && element.ownText().equals(text)) {
                return element.attr("style");
            }
        }
        throw new AssertionError("nicht gefunden: " + text);
    }
}
