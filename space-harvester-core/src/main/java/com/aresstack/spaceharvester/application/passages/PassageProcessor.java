package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.DocumentPosition;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
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
    /** Version des Ergebnisformats; 2 seit {@code textSha256} (Suchschnipsel aus Absätzen). */
    static final String FORMAT = "2";
    /** Höchstzahl gespeicherter Passagen je Seite. */
    static final int MAX_PASSAGES = 5_000;

    private final PassageSegmentation segmentation;
    private final EmbeddingProcessor embedding;

    /**
     * @param sentences Satzgrenzen, produktiv {@link OpenNlpSentenceSegmenter#german}
     * @param tokens    Zählweise der Token, produktiv {@code KnowledgeTextSegmentation.defaults()}
     * @param embedding Quelle des Modells und der Freigabe je Quelle oder {@code null}: dann immer nach Struktur
     */
    public PassageProcessor(SentenceSegmenter sentences, TextSegmentation tokens, EmbeddingProcessor embedding) {
        this.segmentation = new PassageSegmentation(Objects.requireNonNull(sentences, "sentences"), tokens);
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

    /**
     * Eine nicht freigegebene Quelle geht nie ans Netz: Ihre Absätze nach Struktur entstehen sofort im Abgleich oder
     * beim Öffnen der Seite, statt im begrenzten Hintergrund zu warten oder bei voller Warteschlange zu entfallen.
     */
    @Override
    public boolean background(PageKey key) {
        return background() && embedding.covers(key);
    }

    /**
     * Auch Anhänge und enthaltene Dateien (ADR 0026, AP9a): je Abschnitt aus dem Lesetext ({@code document-text/v1}),
     * eine Zeile je Block, Tabellenzeilen eigene Blöcke; ohne Grenze, die Freigabe gilt über die Seite.
     */
    @Override
    public boolean documents() {
        return true;
    }

    /**
     * Absätze eines Dokuments nach Struktur, weil die Quelle nicht freigegeben war, gelten nicht mehr, sobald sie es
     * ist, und umgekehrt Absätze, die mit Modell entstanden, nicht mehr, sobald die Seite ihre Freigabe verlor
     * (ADR 0028); bei Seiten erledigt das die Neuberechnung nach der Freigabe.
     */
    @Override
    public boolean outdated(Enrichment stored) {
        return !stored.document().isPage() && embedding != null && embedding.available()
                && notApproved(stored) == embedding.covers(stored.key());
    }

    /**
     * Ein Abschnitt eines Dokuments, der nicht ganz unter die Grenze für das Embedding-Modell fällt, wird vollständig
     * nach Struktur zerlegt: Die Grenze greift vor jedem Aufruf des Modells, auch für die Satzvektoren der Absätze.
     */
    @Override
    public EnrichmentOutput process(DocumentKey document, PageText text, DocumentPosition position) {
        Optional<EmbeddingProcessor.Generation> generation = generation();
        if (position.embeddingBudget() < text.text().length() && generation.isPresent()
                && embedding.covers(document.page())) {
            return structure(text, PassagePayload.REASON_EMBEDDING_LIMIT, version(generation.get().modelId()),
                    List.of("Grenze für das Embedding-Modell je Dokument erreicht, Absätze nach Struktur"));
        }
        return process(document.page(), text);
    }

    /**
     * Wie {@link #outdated}, dazu für Dokumente: Liegt der Abschnitt jetzt anders zur Grenze als beim Rechnen, gilt der
     * Stand nicht mehr, sofern das Modell überhaupt gefragt würde.
     */
    @Override
    public boolean current(Enrichment stored, PageText text, DocumentPosition position) {
        if (outdated(stored)) {
            return false;
        }
        if (stored.document().isPage()) {
            return true;
        }
        boolean limited = position.embeddingBudget() < text.text().length();
        boolean recorded = stored.payloadJson().contains("\"reason\":\"" + PassagePayload.REASON_EMBEDDING_LIMIT
                + "\"");
        return limited == recorded || generation().isEmpty() || !embedding.covers(stored.key());
    }

    static boolean notApproved(Enrichment stored) {
        return stored.payloadJson().contains("\"reason\":\"" + PassagePayload.REASON_NOT_APPROVED + "\"");
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
        if (!embedding.covers(key)) {
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
        return output(text, PassagePayload.Mode.SEMANTIC, null, model, result, warnings, version(model));
    }

    private EnrichmentOutput structure(PageText text, String reason, String version, List<String> warnings) {
        return output(text, PassagePayload.Mode.STRUCTURE, reason, null, segmentation.segment(text, null),
                new ArrayList<>(warnings), version);
    }

    private EnrichmentOutput output(PageText text, PassagePayload.Mode mode, String reason, String model,
                                    PassageSegmentation.Result result, List<String> warnings, String version) {
        List<PassageSegmentation.Passage> passages = result.passages();
        if (passages.size() > MAX_PASSAGES) {
            warnings.add("Nur die ersten " + MAX_PASSAGES + " von " + passages.size() + " Absätzen gespeichert");
            passages = passages.subList(0, MAX_PASSAGES);
        }
        return new EnrichmentOutput(PassagePayload.write(mode, reason, model, segmentation.fingerprint(),
                result.sentences(), PassagePayload.textSha256(text.text()), passages), warnings, version);
    }

    private Optional<EmbeddingProcessor.Generation> generation() {
        return embedding == null ? Optional.empty() : embedding.current();
    }
}
