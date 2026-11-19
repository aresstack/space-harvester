package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.source.TrustBundles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Manuelle Probe gegen ein laufendes Ollama auf diesem Rechner (gemma4:12b, bge-m3): Modelle mit Fähigkeiten,
 * Embeddings, Chat und Bildbeschreibung über den echten Weg. Läuft nur mit der Umgebungsvariablen {@code OLLAMA_PROBE},
 * etwa {@code OLLAMA_PROBE=1 ./gradlew :space-harvester-core:test --tests '*OllamaLiveProbe'}.
 */
class OllamaLiveProbe {

    @Test
    void probe() throws Exception {
        Assumptions.assumeTrue(System.getenv("OLLAMA_PROBE") != null);
        AiServiceSettings settings = new AiServiceSettings(URI.create("http://localhost:11434"), null, "gemma4:12b",
                "bge-m3:latest", 1024, Duration.ofMinutes(5), null, Set.of(), null, "gemma4:12b")
                .withProvider(AiProvider.OLLAMA);
        RemoteAi ai = new RemoteAi(settings, HttpRoutePort.direct(), TrustBundles.NONE, "probe");
        ai.models().forEach(model -> System.out.println("MODEL " + model.id() + " " + model.categories()));
        System.out.println("DIMENSION " + ai.measureDimension("bge-m3:latest"));
        System.out.println("EMBED " + ai.embeddings().embed(List.of("Betrieb der Plattform")).get(0).length);
        System.out.println("CHAT " + ai.termCompletions().apply("Antworte nur mit dem Wort OK.", "{}"));
        long start = System.currentTimeMillis();
        Path frame = Path.of("..", "build", "video-debug", "slide-1-258.png");
        Assumptions.assumeTrue(Files.isRegularFile(frame), "kein Standbild unter build/video-debug");
        byte[] image = Files.readAllBytes(frame);
        System.out.println("VISION " + ai.describeImage(image, "image/png"));
        System.out.println("VISION_MS " + (System.currentTimeMillis() - start));
    }
}
