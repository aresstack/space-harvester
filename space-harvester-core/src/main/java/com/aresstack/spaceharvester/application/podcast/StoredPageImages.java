package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.ImageStatus;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Die Seitenbilder für {@link PodcastPictures} aus dem Bestand: nur Bilder mit gespeicherter Beschreibung
 * ({@link PageImageAnalysis#read}, beschreibt nichts und fragt die Quelle nicht), nur aus aktuellen Analysen, nur von Seiten mit KI-Freigabe
 * (ADR 0028), ohne Folienbilder von Videos. Die Bytes kommen aus der Asset-Ablage, geprüft gegen ihren Fingerabdruck.
 */
public final class StoredPageImages implements PodcastPictures.PageImageSource {

    private final Function<PageKey, Optional<PageSnapshot>> pages;
    private final PageImageAnalysis analysis;
    private final Assets assets;

    public StoredPageImages(Function<PageKey, Optional<PageSnapshot>> pages, PageImageAnalysis analysis,
                            Assets assets) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public List<Candidate> candidates(String sourceId, List<String> pageIds) {
        if (sourceId == null) {
            // Ohne Quelle (etwa der Podcast zum Buch eines Ordners) gibt es keine Seitenbilder: Schaubild statt Foto.
            return List.of();
        }
        List<Candidate> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String pageId : pageIds) {
            PageKey key = new PageKey(sourceId, pageId);
            if (!analysis.released(key)) {
                continue;
            }
            Optional<PageSnapshot> snapshot = pages.apply(key);
            if (snapshot.isEmpty()) {
                continue;
            }
            PageImages read = analysis.read(snapshot.get());
            if (read.state() != PageImages.State.CURRENT) {
                continue; // Seite oder Bild seither geändert: die alten Beschreibungen gelten nicht mehr sicher
            }
            for (ImagePayload.Entry entry : read.images()) {
                if (entry.status() != ImageStatus.DESCRIBED || entry.description() == null
                        || entry.assetId() == null || Assets.videoMark(entry.assetId())
                        || (entry.sha256() != null && !seen.add(entry.sha256()))) {
                    continue;
                }
                String title = entry.title().isBlank() ? entry.filename() : entry.title();
                found.add(new Candidate(pageId, entry.assetId(), entry.sha256(), title, entry.description(),
                        entry.mediaType()));
            }
        }
        return found;
    }

    @Override
    public Optional<byte[]> content(String sourceId, Candidate candidate) {
        if (candidate.sha256() == null || !analysis.released(new PageKey(sourceId, candidate.pageId()))) {
            return Optional.empty();
        }
        return assets.stored(sourceId, candidate.assetId(), null, candidate.sha256());
    }
}
