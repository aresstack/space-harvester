package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.podcast.PodcastVideo;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.ParallelChoice;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/**
 * Größe und Bildrate für „Als Video exportieren …“ (Angelo 27.11.2026), im Menü am Dreieck unter
 * „Video-Einstellungen“, mit derselben Auswahl wie die Grenzen der Parallelität ({@link ParallelChoice}); die App merkt
 * sie selbst. Die Vorgabe 1280 × 720 ist die Größe der Schaubilder des Podcasts; weil sich das Bild nur mit dem Thema
 * ändert, reicht ein Bild je Sekunde, und der Export bleibt schnell.
 */
final class PodcastVideoSettings {

    static final String MENU = "Video-Einstellungen";
    static final String WIDTH = "Breite";
    static final String HEIGHT = "Höhe";
    static final String RATE = "Bilder je Sekunde";
    static final String WIDTH_KEY = "podcast.video.width";
    static final String HEIGHT_KEY = "podcast.video.height";
    static final String RATE_KEY = "podcast.video.fps";
    static final int WIDTH_DEFAULT = 1280;
    static final int HEIGHT_DEFAULT = 720;
    static final int RATE_DEFAULT = 1;
    private static final List<Integer> WIDTHS = List.of(854, 1280, 1920, 3840);
    private static final List<Integer> HEIGHTS = List.of(480, 720, 1080, 2160);
    private static final List<Integer> RATES = List.of(1, 2, 5, 10, 25, 30);

    private PodcastVideoSettings() {
    }

    /** Die gemerkten Werte für den nächsten Export. */
    static PodcastVideo.Options options() {
        return new PodcastVideo.Options(ParallelChoice.stored(WIDTH_KEY, WIDTH_DEFAULT),
                ParallelChoice.stored(HEIGHT_KEY, HEIGHT_DEFAULT), ParallelChoice.stored(RATE_KEY, RATE_DEFAULT));
    }

    /** Der Eintrag „Video-Einstellungen“ mit Breite, Höhe und Bildrate. */
    static javax.swing.JMenu menu(Component owner, Consumer<String> invalid) {
        javax.swing.JMenu menu = new javax.swing.JMenu(MENU);
        menu.setFont(Comic.regular(13f));
        menu.setToolTipText("Größe und Bildrate für „Als Video exportieren …“");
        menu.add(ParallelChoice.stored(owner, "Video: Breite in Pixeln", WIDTH_KEY, WIDTH_DEFAULT, WIDTHS, invalid)
                .menu(WIDTH));
        menu.add(ParallelChoice.stored(owner, "Video: Höhe in Pixeln", HEIGHT_KEY, HEIGHT_DEFAULT, HEIGHTS, invalid)
                .menu(HEIGHT));
        menu.add(ParallelChoice.stored(owner, "Video: Bilder je Sekunde", RATE_KEY, RATE_DEFAULT, RATES, invalid)
                .menu(RATE));
        return menu;
    }
}
