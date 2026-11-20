package com.aresstack.spaceharvester.application.presentation;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Lesetext einer Videoseite (Angelo 20.11.2026): Die Analysen (Absätze, Begriffe, Aussagen, Graph) laufen über das
 * Transkript wie über normalen Text, über die zuletzt abgelegte Korrektur, wenn es eine gibt. Die Zeitmarken
 * {@code [00:01:13 – 00:01:40]} fallen weg, je Abschnitt bleibt ein Absatz. Steht in der Kette hinter den Projektoren
 * der Formate: Eine Seite, deren Payload schon Text hat, liest weiter diesen. Ohne Transkript leer.
 */
public final class VideoTranscriptTextProjector implements PageTextProjector {

    /** Projektionsregeln; ändern sie sich, rechnen die Analysen neu. */
    public static final String SCHEMA = "video-transcript-text/v1";

    private final Supplier<Assets> assets;

    /** @param assets die Ablage; {@code null}, solange sie beim Start noch nicht steht */
    public VideoTranscriptTextProjector(Supplier<Assets> assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        Assets store = assets.get();
        if (store == null) {
            return Optional.empty();
        }
        return store.latestVideoTranscriptText(snapshot.key().sourceId(), snapshot.key().pageId(),
                snapshot.revision()).map(VideoTranscriptTextProjector::text).filter(text -> !text.isBlank())
                .map(text -> new PageText(SCHEMA, text, List.of()));
    }

    /** Der Text ohne Zeitmarken, ein Absatz je Abschnitt. */
    static String text(String transcript) {
        return TranscriptSections.parse(transcript).stream().map(TranscriptSections.Section::text)
                .collect(Collectors.joining("\n\n"));
    }
}
