package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.reading.ForeignContent;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingPaneTest {

    private static final Instant T0 = Instant.parse("2026-10-18T05:00:00Z");
    private static final PageHeader HEADER = PageHeader.of("wiki", new PageMetadata("42", "confluence:wiki/page/42",
            "Betrieb", "OPS", null, "3", T0, URI.create("https://wiki.example.org/pages/42"),
            new TreeMap<>(Map.of())), T0);

    @Test
    void movingTheMouseOverTextOrEmptySpaceKeepsTheDefaultCursor() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ReadingPane reading = new ReadingPane();
            reading.setSize(400, 300);
            javax.swing.JEditorPane pane = find(reading);
            pane.setSize(400, 300);
            for (java.awt.Point at : List.of(new java.awt.Point(5, 5), new java.awt.Point(390, 290))) {
                for (java.awt.event.MouseMotionListener listener : pane.getMouseMotionListeners()) {
                    listener.mouseMoved(new java.awt.event.MouseEvent(pane, java.awt.event.MouseEvent.MOUSE_MOVED,
                            0, 0, at.x, at.y, 0, false));
                }
            }
            assertThat(pane.getCursor()).isEqualTo(java.awt.Cursor.getDefaultCursor());
            assertThat(pane.getToolTipText()).isNull();
        });
    }

    private static javax.swing.JEditorPane find(java.awt.Container container) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JEditorPane pane) {
                return pane;
            }
            if (child instanceof java.awt.Container inner) {
                javax.swing.JEditorPane pane = find(inner);
                if (pane != null) {
                    return pane;
                }
            }
        }
        return null;
    }

    @Test
    void imagesComeFromTheCoreAndMissingOnesAreMarked() throws IOException {
        HarvestSource.ViewImage logo = new HarvestSource.ViewImage("/download/attachments/42/logo.png", "logo.png",
                "att1", "image/png", "1");
        HarvestSource.ViewImage broken = new HarvestSource.ViewImage("/download/attachments/42/kaputt.png",
                "kaputt.png", "att2", "image/png", "1");
        byte[] png = png(1600, 400);
        List<HarvestSource.ViewImage> asked = new ArrayList<>();
        ReadingView view = available("<h1 onclick=\"x()\">Titel</h1><script>alert(1)</script>"
                        + "<iframe src=\"https://evil.example\"></iframe>"
                        + "<p><img src=\"/download/attachments/42/logo.png\"></p>"
                        + "<p><img src=\"/download/attachments/42/kaputt.png\"></p>"
                        + "<p><img src=\"https://tracker.example/pixel.gif\" alt=\"Fremd\"></p>"
                        + "<a href=\"/display/OPS/Andere\">Andere Seite</a><a href=\"javascript:x()\">Böse</a>",
                List.of(logo, broken));

        ReadingPane.Prepared prepared = ReadingPane.prepare(view, image -> {
            asked.add(image);
            return image.equals(logo) ? png : null;
        }, source -> null);

        assertThat(asked).containsExactly(logo, broken);
        assertThat(prepared.images()).isEqualTo(1);
        assertThat(prepared.missing()).isEqualTo(2);
        assertThat(prepared.html())
                .doesNotContain("script", "iframe", "onclick", "tracker.example", "javascript:")
                .contains("http://reading.invalid/image/0", "width=\"720\"", "height=\"180\"")
                .contains("[Bild fehlt: kaputt.png]", "[Bild fehlt: Fremd]")
                .contains("href=\"https://wiki.example.org/display/OPS/Andere\"");
        assertThat(prepared.originals().get("http://reading.invalid/image/0").getWidth()).isEqualTo(1600);
        assertThat(prepared.note()).contains("1 Bild", "2 nicht darstellbar");
    }

    @Test
    void mermaidComesFromTheCoreOrIsMarked() throws IOException {
        ReadingView view = available("<div class=\"mermaid\">graph TD; A--&gt;B;</div>"
                + "<div class=\"mermaid\">das ist kein diagramm %%%</div>", List.of());

        List<String> asked = new ArrayList<>();
        byte[] png = png(40, 20);

        ReadingPane.Prepared prepared = ReadingPane.prepare(view, image -> null, source -> {
            asked.add(source);
            return source.startsWith("graph") ? png : null;
        });

        assertThat(asked).containsExactly("graph TD; A-->B;", "das ist kein diagramm %%%");
        assertThat(prepared.images()).isEqualTo(1);
        assertThat(prepared.missing()).isEqualTo(1);
        assertThat(prepared.html()).contains("http://reading.invalid/image/0", "[Diagramm nicht darstellbar]")
                .contains("das ist kein diagramm");
    }

    @Test
    void restrictedAndUnsupportedViewsOnlyExplain() {
        ReadingView restricted = new ReadingView(HEADER, ReadingView.Status.RESTRICTED, "3", "", "", "", List.of(),
                new ForeignContent(List.of("include"), false), false, null);
        ReadingView unsupported = new ReadingView(HEADER, ReadingView.Status.UNSUPPORTED, "3", "", "", "",
                List.of(), ForeignContent.NONE, false, null);

        assertThat(ReadingPane.prepare(restricted, image -> null, source -> null).html()).isEmpty();
        assertThat(ReadingPane.prepare(restricted, image -> null, source -> null).note()).contains("nicht nachweislich lesen");
        assertThat(ReadingPane.prepare(unsupported, image -> null, source -> null).note()).contains("keine Leseansicht");
    }

    private static ReadingView available(String html, List<HarvestSource.ViewImage> images) {
        return new ReadingView(HEADER, ReadingView.Status.AVAILABLE, "3", html, "https://wiki.example.org/", "Text",
                images, ForeignContent.NONE, true, T0);
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
