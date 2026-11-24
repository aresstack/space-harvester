package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.ParallelChoice;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/**
 * Die Grenzen der Parallelität beim Erzeugen eines Podcasts (Angelo 24.11.2026), im Menü am Dreieck der Lese- und der
 * Podcast-Ansicht, mit demselben Code wie im Chat und bei den Aufträgen ({@link ParallelChoice}). Die App merkt sie
 * selbst, nicht in den Einstellungen. Der Plan entsteht immer zuerst und allein; danach laufen Texte und Audio im
 * einen, die Bilder in einem eigenen Pool.
 */
final class PodcastParallel {

    static final String TEXTS = "Podcast: parallele Texte";
    static final String PICTURES = "Podcast: parallele Bilder";
    static final String TEXTS_KEY = "podcast.parallel.texts";
    static final String PICTURES_KEY = "podcast.parallel.pictures";
    static final int TEXTS_DEFAULT = 4;
    /** Mit weniger kommen die Bilder nach dem ersten meist zu spät (Angelo 24.11.2026). */
    static final int PICTURES_DEFAULT = 8;
    private static final String TEXTS_TIP = "Podcast: wie viele Abschnitte, Sprechertexte und Audios gleichzeitig "
            + "entstehen, nachdem der Plan steht";
    private static final String PICTURES_TIP = "Podcast: wie viele Anfragen für Bilder (Auswahl, Schaubild-Plan, "
            + "Einzelteile) gleichzeitig laufen, in einem eigenen Pool neben den Texten";

    private PodcastParallel() {
    }

    static int texts() {
        return ParallelChoice.stored(TEXTS_KEY, TEXTS_DEFAULT);
    }

    static int pictures() {
        return ParallelChoice.stored(PICTURES_KEY, PICTURES_DEFAULT);
    }

    /** Die beiden Einträge für das Menü am Dreieck. */
    static List<javax.swing.JMenu> menus(Component owner, Consumer<String> invalid) {
        return List.of(ParallelChoice.stored(owner, TEXTS_TIP, TEXTS_KEY, TEXTS_DEFAULT, invalid).menu(TEXTS),
                ParallelChoice.stored(owner, PICTURES_TIP, PICTURES_KEY, PICTURES_DEFAULT, invalid).menu(PICTURES));
    }

    /** Gibt dem Core die Grenzen; sie gelten ab dem nächsten Lauf und werden dort jedes Mal frisch gelesen. */
    static Workspace apply(Workspace workspace) {
        workspace.podcastParallel(PodcastParallel::texts, PodcastParallel::pictures);
        return workspace;
    }
}
