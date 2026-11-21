package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.podcast.PodcastSpeech;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Sprachausgabe des KI-Dienstes für den Podcast: TTS-Modelle aus dem Modellkatalog ({@code GET /models}, Kategorie
 * {@code tts} wie im Enterprise AI Client) und {@link RemoteAi#speech()} für {@code POST /audio/speech}. Folgt der
 * wirksamen Konfiguration bei jedem Aufruf wie {@link ChatStepModel}; einstellen muss man nichts.
 */
public final class RemotePodcastSpeech implements PodcastSpeech {

    private final Supplier<AiServiceSettings> settings;
    private final Function<AiServiceSettings, RemoteAi> remote;

    public RemotePodcastSpeech(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                               TrustBundles trustBundles, String userAgent) {
        this(settings, current -> RemoteAi.of(current, catalog, trustBundles, userAgent));
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(trustBundles, "trustBundles");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    RemotePodcastSpeech(Supplier<AiServiceSettings> settings, Function<AiServiceSettings, RemoteAi> remote) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    @Override
    public boolean available() {
        AiServiceSettings current = settings.get();
        return current != null && current.configured() && current.provider() != AiProvider.OLLAMA;
    }

    @Override
    public List<String> models() {
        AiServiceSettings current = settings.get();
        if (!available()) {
            return List.of();
        }
        return remote.apply(current).models().stream()
                .filter(model -> model.categories().contains(AiModel.TTS))
                .map(AiModel::id).toList();
    }

    @Override
    public SpeechSynthesisPort port() {
        return remote.apply(settings.get()).speech();
    }
}
