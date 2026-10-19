package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Prozessor {@value #ID}: die Dokumentnavigation einer Seite (#181, ADR 0020) wie
 * {@code BuildDocumentNavigationUseCase} im Enterprise AI Client, Überschriften plus Themenabschnitte. Eingabe sind
 * die gespeicherten Embeddings je Absatz des Prozessors {@code embedding} ({@link EmbeddingProcessor#withPassages}) für
 * genau diesen Text, wie im Client die indexierten Passagen dieser Fassung; eingebettet wird hier nichts.
 *
 * <p>Ohne Modell, ohne Freigabe der Quelle oder solange die Embeddings je Absatz für diesen Text fehlen, bleiben die
 * Überschriften (FA-01) mit Grund. Die beiden letzten Fälle tragen eine Version, die sofort veraltet: Die nächste
 * Anreicherung oder der nächste Abruf versucht es wieder, ohne dass etwas an den KI-Dienst geht.
 */
public final class NavigationProcessor implements EnrichmentProcessor {

    /** Kennung des Prozessors. */
    public static final String ID = "navigation";
    /** Version des Ergebnisformats. */
    static final String FORMAT = "1";

    private final DocumentNavigationBuilder builder;
    private final EmbeddingProcessor embedding;
    private final Function<PageKey, List<Enrichment>> stored;
    private final NavigationLabelModel labels;

    /**
     * @param embedding der Prozessor {@code embedding} oder {@code null} ohne Modell
     * @param stored    die gespeicherten Anreicherungen einer Seite, z. B. {@code EnrichmentStore::find}
     */
    public NavigationProcessor(DocumentNavigationBuilder builder, EmbeddingProcessor embedding,
                               Function<PageKey, List<Enrichment>> stored) {
        this(builder, embedding, stored, null);
    }

    /**
     * @param labels benennt Themenabschnitte über das Chat-Modell ({@code ChatSectionLabeler} im Client) oder
     *               {@code null}: Bezeichnungen aus der Analyse
     */
    public NavigationProcessor(DocumentNavigationBuilder builder, EmbeddingProcessor embedding,
                               Function<PageKey, List<Enrichment>> stored, NavigationLabelModel labels) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.embedding = embedding;
        this.stored = Objects.requireNonNull(stored, "stored");
        this.labels = labels;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return FORMAT + "+" + builder.fingerprint() + "+"
                + (embedding == null || !embedding.available() ? "no-model" : "embedding=" + embedding.version())
                // Ein anderes Chat-Modell benennt anders: die Navigation wird neu berechnet.
                + (labels != null && labels.available() ? "+labels=" + labels.version() : "");
    }

    /**
     * Mit Modell im Hintergrund wie {@code embedding}: Die Warteschlange rechnet eine Berechnung nach der anderen, die
     * Navigation einer Seite also nach ihren Embeddings.
     */
    @Override
    public boolean background() {
        return embedding != null && embedding.available() && embedding.background();
    }

    /**
     * Nur Seiten, für die es Embeddings je Absatz geben kann, warten im Hintergrund auf sie; alle anderen bekommen ihre
     * Überschriften sofort und belegen keinen Platz in der Warteschlange.
     */
    @Override
    public boolean background(PageKey key) {
        return background() && embedding.covers(key.sourceId()) && embedding.passages();
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        throw new UnsupportedOperationException("Navigation braucht die Seite");
    }

    @Override
    public EnrichmentOutput process(PageKey key, PageText text) {
        String version = version();
        if (embedding == null || !embedding.available()) {
            return headings(text, NavigationPayload.REASON_NO_MODEL, version);
        }
        if (!embedding.covers(key.sourceId())) {
            // Wie bei passages: gilt, bis Text, Modell oder Verfahren wechseln.
            return headings(text, NavigationPayload.REASON_NOT_APPROVED, version);
        }
        Enrichment vectors = stored.apply(key).stream()
                .filter(e -> e.processorId().equals(embedding.id()) && e.status() == Enrichment.Status.OK
                        && e.freshness(text.inputSha256(), embedding.version()) == Enrichment.Freshness.CURRENT)
                .findFirst().orElse(null);
        if (!embedding.passages()) {
            // Embeddings ohne Absätze: Es kommen keine, auf die sich das Warten lohnte.
            return headings(text, NavigationPayload.REASON_NOT_EMBEDDED, version);
        }
        if (vectors == null) {
            return headings(text, NavigationPayload.REASON_NOT_EMBEDDED, version + "+pending");
        }
        List<DocumentNavigationBuilder.Passage> passages = new ArrayList<>();
        for (EmbeddingPayload.Chunk chunk : EmbeddingPayload.parse(vectors.payloadJson()).chunks()) {
            passages.add(new DocumentNavigationBuilder.Passage(chunk.charStart(), chunk.charEnd(), chunk.vector()));
        }
        boolean[] incomplete = {false};
        SectionLabeler labeler = labels == null || !labels.available() || !labels.mayLabel(key.sourceId()) ? null
                : (title, texts) -> {
                    try {
                        NavigationLabelModel.Labels named = labels.labels(title, texts);
                        incomplete[0] = !named.complete();
                        return named.labels();
                    } catch (RuntimeException e) {
                        // Nie Text oder Antwort weitergeben: die Bezeichnungen der Analyse bleiben.
                        incomplete[0] = true;
                        throw e;
                    }
                };
        DocumentNavigationBuilder.Navigation navigation = builder.build(null, text, passages, labeler);
        if (incomplete[0]) {
            // Chat-Modell nicht erreichbar: Bezeichnungen der Analyse mit Grund, beim nächsten Lauf erneut.
            return new EnrichmentOutput(NavigationPayload.write(new DocumentNavigationBuilder.Navigation(
                    navigation.analysis(), NavigationPayload.REASON_MODEL_UNAVAILABLE, navigation.chapters())),
                    List.of(), version + "+pending");
        }
        return new EnrichmentOutput(NavigationPayload.write(navigation), List.of(), version);
    }

    private EnrichmentOutput headings(PageText text, String reason, String version) {
        return new EnrichmentOutput(NavigationPayload.write(builder.headingsOnly(text, reason)), List.of(), version);
    }
}
