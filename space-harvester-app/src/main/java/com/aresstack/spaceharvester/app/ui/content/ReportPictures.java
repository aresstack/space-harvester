package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.PictureStage;
import com.aresstack.spaceharvester.app.ui.controls.ViewerPopup;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Bilder eines Podcasts im Bericht einer Zusammenfassung (Angelo 22.11.2026): liest im Hintergrund, welche Bilder
 * der Podcast schon fertig hat, merkt sich, wo sie im Bericht stehen, und öffnet sie im rahmenlosen Betrachter, der aus
 * der Bildstelle aufwächst, auf Klick oder wenn der Podcast ihr Kapitel erreicht. Gemeinsam für die Updates
 * ({@link SourceUpdatesView}) und die Bereichszusammenfassung ({@link AreaSummaryView}); wo die Bilder stehen,
 * entscheidet die Ansicht.
 */
final class ReportPictures {

    private final JComponent owner;
    private final SourceUpdatesView.Runner runner;
    private final Supplier<Consumer<String>> status;
    private final Runnable cueClosedByUser;
    /** Die Bilder, wie sie im Bericht stehen, in seiner Reihenfolge. */
    private List<SummaryReport.PlacedPicture> placed = List.of();
    /** Die zuletzt gelesenen Bilder des Podcasts von {@link #of}. */
    private List<SummaryReport.LoadedPicture> loaded = List.of();
    private UpdateSummary of;
    private int loads;
    /** Gelesene Bilder je Datei und Stand: ein neu gezeichneter Bericht liest nichts erneut. */
    private final Map<String, PictureStage.Picture> cache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Der Betrachter, den der Podcast selbst geöffnet hat (und wieder schließt). */
    private ViewerPopup cuePopup;

    /**
     * @param status          wohin ein Fehler beim Lesen geht (Statuszeile)
     * @param cueClosedByUser der Benutzer hat das Bild geschlossen, das der Podcast geöffnet hatte
     */
    ReportPictures(JComponent owner, SourceUpdatesView.Runner runner, Supplier<Consumer<String>> status,
                   Runnable cueClosedByUser) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.status = Objects.requireNonNull(status, "status");
        this.cueClosedByUser = Objects.requireNonNull(cueClosedByUser, "cueClosedByUser");
    }

    /**
     * Fragt im Hintergrund, welche Bilder der Podcast zu {@code summary} schon fertig hat, und ruft dann {@code show},
     * solange {@code pictured} noch denselben Podcast meint.
     *
     * @param source die fertigen Bilder einer Zusammenfassung; liest Dateien, läuft nie auf dem EDT
     */
    void load(Function<UpdateSummary, List<TopicPicture>> source, UpdateSummary summary,
              Supplier<UpdateSummary> pictured, Runnable show) {
        if (of != null && !SummaryReport.samePodcast(of, summary)) {
            cache.clear();
        }
        int mine = ++loads;
        runner.run(() -> read(source, summary), found -> {
            if (mine == loads && SummaryReport.samePodcast(pictured.get(), summary)) {
                of = summary;
                loaded = found;
                show.run();
            }
        }, failure -> {
            // Bilder sind Beiwerk: der Bericht bleibt ohne sie stehen, der Grund steht in der Statuszeile.
            if (mine == loads) {
                status.get().accept("Bilder des Podcasts nicht geladen: " + Problems.describe(failure).title());
            }
        });
    }

    private List<SummaryReport.LoadedPicture> read(Function<UpdateSummary, List<TopicPicture>> source,
                                                   UpdateSummary summary) {
        List<SummaryReport.LoadedPicture> found = new ArrayList<>();
        for (TopicPicture topic : source.apply(summary)) {
            java.nio.file.Path file = topic.picture().file();
            String key;
            try {
                key = file + "|" + java.nio.file.Files.getLastModifiedTime(file).toMillis();
            } catch (java.io.IOException | RuntimeException e) {
                continue;
            }
            PictureStage.Picture read = cache.get(key);
            if (read == null) {
                try {
                    read = PodcastControl.read(topic.picture());
                } catch (java.io.IOException | RuntimeException e) {
                    // ein unlesbares Bild fehlt im Bericht; die übrigen stehen da
                    continue;
                }
                cache.put(key, read);
            }
            found.add(new SummaryReport.LoadedPicture(topic, read));
        }
        return found;
    }

    /** Die gelesenen Bilder, wenn sie zum Podcast von {@code summary} gehören, sonst keine. */
    List<SummaryReport.LoadedPicture> loaded(UpdateSummary summary) {
        return SummaryReport.samePodcast(summary, of) ? loaded : List.of();
    }

    /** Wo die Bilder jetzt im Bericht stehen. */
    void placed(List<SummaryReport.PlacedPicture> now) {
        placed = List.copyOf(now);
    }

    List<SummaryReport.PlacedPicture> placed() {
        return placed;
    }

    /** Öffnet das Bild von Kapitel {@code topic} des Podcasts, wenn es im Bericht steht. */
    boolean openTopic(int topic) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).topic() == topic) {
                JComponent anchor = placed.get(i).anchor();
                anchor.scrollRectToVisible(placed.get(i).bounds().get());
                return open(i, true);
            }
        }
        return false;
    }

    /**
     * Öffnet das Bild an Stelle {@code position} im rahmenlosen Betrachter, der aus der Bildstelle im Text aufwächst;
     * blättern geht durch alle Bilder des Berichts. Hat der Podcast es geöffnet und schließt der Benutzer es selbst,
     * bleibt es in diesem Kapitel zu.
     */
    boolean open(int position, boolean cued) {
        if (position < 0 || position >= placed.size() || SwingUtilities.getRootPane(owner) == null) {
            return false;
        }
        SummaryReport.PlacedPicture at = placed.get(position);
        JComponent anchor = at.anchor();
        ViewerPopup popup = ViewerPopup.open(owner, new ImageViewer(placed.stream()
                        .map(SummaryReport.PlacedPicture::viewer).toList(), position, Comic.viewerLook(
                        com.aresstack.spaceharvester.app.settings.ViewerBackground.DEFAULT)),
                () -> SwingUtilities.isDescendingFrom(anchor, owner)
                        ? SwingUtilities.convertRectangle(anchor, at.bounds().get(), owner) : null);
        if (cued) {
            cuePopup = popup;
            popup.onClosed(() -> {
                if (cuePopup == popup) {
                    cuePopup = null;
                    cueClosedByUser.run();
                }
            });
        }
        return true;
    }

    /** Schließt das Bild, das der Podcast geöffnet hat. */
    void closeCue() {
        ViewerPopup open = cuePopup;
        cuePopup = null;
        if (open != null) {
            open.close();
        }
    }

    /** Die Bilder im Bericht, als Bildunterschriften in seiner Reihenfolge (für Tests). */
    List<String> captions() {
        return placed.stream().map(SummaryReport.PlacedPicture::caption).toList();
    }
}
