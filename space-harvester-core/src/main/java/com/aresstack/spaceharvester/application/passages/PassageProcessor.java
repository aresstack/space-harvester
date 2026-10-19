package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Semantische Absätze einer Seite als Anreicherung {@value #ID} (#181, ADR 0020), Ergebnis im Format
 * {@link PassagePayload}. Zerlegt mit {@link PassageSegmentation}; die Satzvektoren kommen vom Embedding-Modell des
 * Prozessors {@code embedding}, also vom externen KI-Dienst (ADR 0011) und nur für Quellen, deren Inhalte an ihn gehen
 * dürfen. Sonst entstehen die Passagen ohne Netz nach Struktur ({@link PassagePayload.Mode#STRUCTURE}) mit dem Grund:
 *
 * <ul>
 *   <li>{@code no-model}: kein Embedding-Modell eingestellt; kommt eines hinzu, veraltet das Ergebnis.</li>
 *   <li>{@code source-not-approved}: Quelle nicht für den KI-Dienst freigegeben; gilt, bis sich Text oder Modell
 *       ändern oder neu berechnet wird.</li>
 *   <li>{@code model-unavailable}: Modell nicht erreichbar; das Ergebnis ist sofort veraltet und wird beim nächsten
 *       Abgleich oder Abruf wiederholt.</li>
 * </ul>
 *
 * <p>Die Version enthält Zerlegung, Satzverfahren und Modell: ein Wechsel macht genau diese Ergebnisse veraltet.
 */
public class PassageProcessor implements EnrichmentProcessor {

    /** Kennung des Prozessors. */
    public static final String ID = "passages";
    /** Version des Ergebnisformats. */
    static final String FORMAT = "1";
    /** Höchstzahl gespeicherter Passagen je Seite. */
    static final int MAX_PASSAGES = 5_000;

    private final PassageSegmentation segmentation;
    private final EmbeddingProcessor embedding;

    /**
     * @param sentences Satzgrenzen, produktiv {@link OpenNlpSentenceSegmenter#german()}
     * @param embedding Quelle des Modells und der Freigabe je Quelle oder {@code null}: dann immer nach Struktur
     */
    public PassageProcessor(SentenceSegmenter sentences, EmbeddingProcessor embedding) {
        this.segmentation = new PassageSegmentation(Objects.requireNonNull(sentences, "sentences"));
        this.embedding = embedding;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return version(generation().map(EmbeddingProcessor.Generation::modelId).orElse(null));
    }

    private String version(String model) {
        return FORMAT + "+" + segmentation.fingerprint() + "+" + (model == null ? "structure" : "model=" + model);
    }

    /** Mit Modell über das Netz, also im Hintergrund wie {@code embedding}. */
    @Override
    public boolean background() {
        return embedding != null && embedding.background() && embedding.available();
    }

    /** Ohne Seite weiß der Prozessor nicht, ob die Quelle freigegeben ist: nur nach Struktur. */
    @Override
    public EnrichmentOutput process(PageText text) {
        return structure(text, PassagePayload.REASON_NOT_APPROVED, version(), List.of());
    }

    @Override
    public EnrichmentOutput process(PageKey key, PageText text) {
        Optional<EmbeddingProcessor.Generation> generation = generation();
        if (generation.isEmpty()) {
            return structure(text, PassagePayload.REASON_NO_MODEL, version(null), List.of());
        }
        String model = generation.get().modelId();
        if (!embedding.covers(key.sourceId())) {
            return structure(text, PassagePayload.REASON_NOT_APPROVED, version(model), List.of());
        }
        PassageSegmentation.Result result;
        try {
            // Ein Modell für den ganzen Lauf, auch wenn es währenddessen gewechselt wird.
            result = segmentation.segment(text, generation.get()::embed);
        } catch (EmbeddingUnavailableException e) {
            // Mit der Version ohne Modell: das Ergebnis gilt sofort als veraltet und wird wiederholt.
            return structure(text, PassagePayload.REASON_MODEL_UNAVAILABLE, version(null),
                    List.of("Embedding-Modell nicht erreichbar, Absätze nach Struktur"));
        }
        List<String> warnings = new ArrayList<>();
        if (result.truncated()) {
            warnings.add("Nur die ersten " + PassageSegmentation.MAX_EMBEDDED_SENTENCES
                    + " Sätze nach Themenwechsel geteilt, danach nach Struktur");
        }
        return output(PassagePayload.Mode.SEMANTIC, null, model, result, warnings, version(model));
    }

    private EnrichmentOutput structure(PageText text, String reason, String version, List<String> warnings) {
        return output(PassagePayload.Mode.STRUCTURE, reason, null, segmentation.segment(text, null),
                new ArrayList<>(warnings), version);
    }

    private EnrichmentOutput output(PassagePayload.Mode mode, String reason, String model,
                                    PassageSegmentation.Result result, List<String> warnings, String version) {
        List<PassageSegmentation.Passage> passages = result.passages();
        if (passages.size() > MAX_PASSAGES) {
            warnings.add("Nur die ersten " + MAX_PASSAGES + " von " + passages.size() + " Absätzen gespeichert");
            passages = passages.subList(0, MAX_PASSAGES);
        }
        return new EnrichmentOutput(PassagePayload.write(mode, reason, model, segmentation.fingerprint(),
                result.sentences(), passages), warnings, version);
    }

    private Optional<EmbeddingProcessor.Generation> generation() {
        return embedding == null ? Optional.empty() : embedding.current();
    }
}
