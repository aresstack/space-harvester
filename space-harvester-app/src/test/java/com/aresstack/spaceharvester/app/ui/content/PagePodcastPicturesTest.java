package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Bilder und Markierung des Seiten-Podcasts im Lesetext, mit einer erfundenen Seite über „Nordlicht“. */
class PagePodcastPicturesTest {

    private static final String TEXT = "Wozu es den Nachtlauf Nordlicht gibt.\n" + "Füllzeile.\n".repeat(30)
            + "Ablauf\nErst Prüfung, dann Start.\n" + "Füllzeile.\n".repeat(30) + "Störungen\nBereitschaft anrufen.";

    @Test
    void picturesStandRightOfTheTextAtTheirPlaceAndGoAwayAgain() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                JTextPane text = new JTextPane();
                text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
                text.setText(TEXT);
                text.setSize(900, 2000);
                text.doLayout();
                PagePodcastPictures pictures = new PagePodcastPictures(text);
                int ablauf = TEXT.indexOf("Ablauf");
                int stoerungen = TEXT.indexOf("Störungen");

                pictures.show(List.of(placed(2, stoerungen, "Wer nachts hilft"), placed(1, ablauf, "Der Ablauf"),
                        placed(3, TEXT.length() + 50, "außerhalb")));
                text.setSize(900, 2000);
                pictures.show(List.of(placed(2, stoerungen, "Wer nachts hilft"), placed(1, ablauf, "Der Ablauf")));

                // in der Reihenfolge der Seite, rechts neben dem Text, der Text bricht links davon um
                assertThat(pictures.captions()).containsExactly("Der Ablauf", "Wer nachts hilft");
                assertThat(text.getInsets().right).isEqualTo(12 + PagePodcastPictures.COLUMN + PagePodcastPictures.GAP);
                List<java.awt.Rectangle> bounds = pictures.bounds();
                assertThat(bounds.get(0).x).isGreaterThanOrEqualTo(900 - 12 - PagePodcastPictures.COLUMN - 1);
                assertThat(bounds.get(1).y).isGreaterThanOrEqualTo(bounds.get(0).y + bounds.get(0).height);
                assertThat(bounds.get(0).y).isGreaterThan(text.modelToView2D(0).getBounds().y);

                pictures.mark(List.of(new int[]{ablauf, stoerungen}));
                assertThat(pictures.marked()).isEqualTo(1);
                pictures.mark(List.of());
                assertThat(pictures.marked()).isZero();

                pictures.show(List.of());
                assertThat(pictures.captions()).isEmpty();
                assertThat(text.getInsets().right).isEqualTo(12);
                assertThat(text.getComponentCount()).isZero();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void picturesWithoutAPlaceStandAtTheEndAndTheTextGrowsDownToTheLastOne() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                JTextPane text = new JTextPane();
                text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
                text.setText("Der Nachtlauf Nordlicht in Kürze.");
                text.setSize(900, 400);
                text.doLayout();
                PagePodcastPictures pictures = new PagePodcastPictures(text);

                // Themen über Unterseiten haben keine Stelle im Text der Seite: ihr Bild geht nicht verloren
                pictures.show(List.of(placed(1, 0, "Einleitung"), placed(2, Integer.MAX_VALUE, "Unterseite Eskalation"),
                        placed(3, Integer.MAX_VALUE, "Unterseite Rufnummern")));

                assertThat(pictures.captions()).containsExactly("Einleitung", "Unterseite Eskalation",
                        "Unterseite Rufnummern");
                List<java.awt.Rectangle> bounds = pictures.bounds();
                java.awt.Rectangle last = bounds.get(2);
                java.awt.Rectangle end = text.modelToView2D(text.getDocument().getLength()).getBounds();
                // die Bilder ragen weit unter die eine Textzeile: der Text wird so hoch, dass man hinrollen kann
                assertThat(last.y + last.height).isGreaterThan(end.y + end.height);
                assertThat(text.getPreferredSize().height).isGreaterThanOrEqualTo(last.y + last.height);

                pictures.show(List.of());
                assertThat(text.getInsets().bottom).isEqualTo(10);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void theFocusOfAPageArrivesAsPairsOfStartAndEnd() {
        assertThat(PageDetailPanel.spans(List.of(4, 9, 20, 31, 7))).extracting(span -> span[0] + "-" + span[1])
                .containsExactly("4-9", "20-31");
    }

    @Test
    void whatThePodcastNeverTellsIsGreyWithoutTheWhitespaceAround() {
        String text = "Einleitung.\n\nAblauf: erst Prüfung.\n\nHistorie: angelegt.\n";
        int ablauf = text.indexOf("Ablauf");
        int historie = text.indexOf("Historie");

        assertThat(PagePodcastPictures.gaps(text, List.of(new int[]{0, 11}, new int[]{ablauf, historie})))
                .extracting(gap -> text.substring(gap[0], gap[1])).containsExactly("Historie: angelegt.");
        // alles erzählt: nichts grau; nichts erzählt: alles grau außer Leerraum am Rand
        assertThat(PagePodcastPictures.gaps(text, List.of(new int[]{0, text.length()}))).isEmpty();
        assertThat(PagePodcastPictures.gaps(text, List.of())).extracting(gap -> text.substring(gap[0], gap[1]))
                .containsExactly(text.strip());
        // nur Leerraum zwischen zwei erzählten Stellen ist keine Lücke
        assertThat(PagePodcastPictures.gaps(text, List.of(new int[]{0, 11}, new int[]{13, text.length()}))).isEmpty();
    }

    @Test
    void theGreyComesAndGoesWithThePodcast() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                JTextPane text = new JTextPane();
                text.setText("Der Nachtlauf Nordlicht startet um zwei.\nHistorie: angelegt von Erika Beispiel.");
                PagePodcastPictures pictures = new PagePodcastPictures(text);

                pictures.cover(java.util.Optional.of(List.of(new int[]{0, 40})));
                assertThat(pictures.gaps()).isEqualTo(1);
                // gelb liegt nur auf Erzähltem, daneben bleibt das Grau
                pictures.mark(List.<int[]>of(new int[]{0, 40}));
                assertThat(pictures.marked()).isEqualTo(1);
                assertThat(pictures.gaps()).isEqualTo(1);

                pictures.cover(java.util.Optional.empty());
                assertThat(pictures.gaps()).isZero();
                assertThat(text.getHighlighter().getHighlights()).hasSize(1);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static PagePodcastPictures.Placed placed(int topic, int offset, String caption) {
        BufferedImage image = new BufferedImage(480, 270, BufferedImage.TYPE_INT_RGB);
        return new PagePodcastPictures.Placed(topic, offset, image, caption,
                new ImageViewer.Picture(image, caption, null));
    }
}
