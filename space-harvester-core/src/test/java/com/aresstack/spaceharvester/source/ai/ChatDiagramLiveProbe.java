package com.aresstack.spaceharvester.source.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.podcast.DiagramComposer;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Echttest Schaubild im Chat (Angelo 27.11.2026) gegen ein Ollama: Plan und Einzelteile wie im Podcast über die
 * Zeilen der Bildgenerierung. Nur mit {@code OLLAMA_PROBE_DIAGRAM} (Basis-URL), optional {@code OLLAMA_PROBE_MODEL}
 * (Vorgabe {@code gemma4:12b}); in der CI übersprungen.
 */
class ChatDiagramLiveProbe {

    @Test
    void aRealModelDrawsADiagramForTheChat() throws Exception {
        String base = System.getenv("OLLAMA_PROBE_DIAGRAM");
        Assumptions.assumeTrue(base != null && !base.isBlank());
        String model = Optional.ofNullable(System.getenv("OLLAMA_PROBE_MODEL")).orElse("gemma4:12b");
        AiServiceSettings settings = new AiServiceSettings(URI.create(base), null, model, null, null,
                Duration.ofMinutes(5), null, Set.of(), null, null).withProvider(AiProvider.OLLAMA);
        RemoteAi ai = new RemoteAi(settings, HttpRoutePort.direct(), TrustBundles.NONE, "chat-diagram-probe");
        ChatStepModel planner = new ChatStepModel("chat-diagram-plan", () -> settings, () -> null, s -> ai);
        ChatStepModel icons = new ChatStepModel("chat-diagram-parts", () -> settings, () -> null, s -> ai);
        ExecutorService parts = Executors.newFixedThreadPool(2);
        long start = System.currentTimeMillis();
        try {
            Optional<DiagramComposer.Diagram> diagram = new DiagramComposer(new DiagramComposer.Models(planner, icons),
                    parts, attempt -> { }).compose("Ablauf im Space Harvester",
                    "Erst gleicht der Benutzer eine Quelle ab. Dann baut die App den Suchindex. Danach beantwortet der "
                            + "Chat Fragen mit Quellen.", DiagramComposer.Memory.NONE);
            System.out.println("LIVE model=" + model + " ms=" + (System.currentTimeMillis() - start)
                    + " present=" + diagram.isPresent());
            diagram.ifPresent(d -> System.out.println("LIVE plan=" + d.plan() + " svgChars="
                    + d.svg().length() + " repairs=" + d.repairs()));
            assertThat(diagram).isPresent();
            assertThat(diagram.get().svg()).startsWith("<svg").contains("</svg>");
            java.nio.file.Files.writeString(java.nio.file.Path.of("build", "chat-diagram-probe.svg"),
                    diagram.get().svg());
        } finally {
            parts.shutdownNow();
        }
    }
}
