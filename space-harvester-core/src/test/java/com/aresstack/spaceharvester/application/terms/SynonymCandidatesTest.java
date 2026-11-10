package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** {@link SynonymCandidates}: deterministische Regeln, Embedding-Vorauswahl nur aus freigegebenen Quellen, Budget. */
class SynonymCandidatesTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final Set<String> RELEASED = Set.of("wiki");

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"), source("intern"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return all().stream().filter(source -> source.id().equals(sourceId)).findFirst();
        }
    };
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final List<String> embedded = new ArrayList<>();
    private Optional<EmbeddingProvider> provider = Optional.empty();
    private TermRelations relations;
    private SynonymCandidates candidates;
    private int pages;

    private static HarvestSource source(String id) {
        HarvestSource source = Mockito.mock(HarvestSource.class);
        Mockito.when(source.id()).thenReturn(id);
        return source;
    }

    /** Vektoren nach Stichwort im Kontext: gleiche Stichwörter liegen nah, andere fern. */
    private final EmbeddingProvider keywords = new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "fake@1";
        }

        @Override
        public int dimensions() {
            return 8;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                embedded.add(text);
                float[] vector = new float[8];
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("bearbeiter")) {
                    vector[0] = 1;
                    vector[1] = 0.05f * (lower.hashCode() % 3);
                } else if (lower.contains("antrag")) {
                    vector[3] = 1;
                } else {
                    vector[5] = 1;
                    vector[6] = lower.hashCode() % 7;
                }
                vectors.add(vector);
            }
            return vectors;
        }
    };

    private void cached(String sourceId, String space, String text) {
        String pageId = String.valueOf(++pages);
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId,
                "Seite " + pageId, space, null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    private void cached(String text) {
        cached("wiki", "DEV", text);
    }

    private SynonymCandidates.Collected collect(SynonymScope scope) {
        return candidates.collect(scope, SynonymBudget.DEFAULT, null);
    }

    private static Optional<SynonymCandidate> find(SynonymCandidates.Collected collected, String a, String b) {
        String key = new SynonymCandidate(a, b, SynonymCandidate.Reason.SPELLING, List.of(), List.of(), 0).pairKey();
        return collected.candidates().stream().filter(candidate -> candidate.pairKey().equals(key)).findFirst();
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        candidates = new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, () -> provider,
                RELEASED::contains, new TermProcessor().version());
    }

    @Test
    void spellingVariantsBecomeCandidatesWithEvidenceOfBothTerms() {
        cached("Die E-Mail geht an die Fachabteilung.");
        cached("Jede Email wird im Postfach archiviert.");

        SynonymCandidates.Collected collected = collect(SynonymScope.source("wiki"));

        SynonymCandidate candidate = find(collected, "E-Mail", "Email").orElseThrow();
        assertThat(candidate.reason()).isEqualTo(SynonymCandidate.Reason.SPELLING);
        assertThat(candidate.reason().ruleType()).isEqualTo(RelationType.VARIANT);
        assertThat(candidate.fromContext()).isNotEmpty();
        assertThat(candidate.toContext()).isNotEmpty();
        assertThat(candidate.fromContext().get(0).text()).contains("[[");
        assertThat(candidate.fromContext().get(0).evidence().pageId())
                .isNotEqualTo(candidate.toContext().get(0).evidence().pageId());
        assertThat(collected.embeddingUsed()).isFalse();
    }

    @Test
    void umlautSpellingsAreVariantsButInflectionsAreNot() {
        cached("Die Rückmeldung kommt aus der Fachabteilung.");
        cached("Die Rueckmeldung wird geprüft. Der Beleg liegt vor.");
        cached("Die Belege liegen vor.");

        SynonymCandidates.Collected collected = collect(SynonymScope.source("wiki"));

        assertThat(find(collected, "Rückmeldung", "Rueckmeldung")).isPresent();
        assertThat(find(collected, "Beleg", "Belege")).isEmpty();
    }

    @Test
    void anExplicitPhraseBecomesACandidateWithThePhraseAsEvidence() {
        cached("Der Rückmeldeschlüssel, auch bezeichnet als Antwortschlüssel, steht in jeder Meldung.");
        cached("Der Antwortschlüssel ist Pflicht. Die Sachbearbeitung, auch Fallbearbeitung genannt, prüft ihn.");
        cached("Die Fallbearbeitung dauert.");

        SynonymCandidates.Collected collected = collect(SynonymScope.source("wiki"));

        SynonymCandidate phrase = find(collected, "Rückmeldeschlüssel", "Antwortschlüssel").orElseThrow();
        assertThat(phrase.reason()).isEqualTo(SynonymCandidate.Reason.PHRASE);
        assertThat(phrase.fromContext().get(0).evidence().pageId()).isEqualTo("1");
        assertThat(phrase.toContext().get(0).evidence().pageId()).isEqualTo("1");
        assertThat(phrase.toContext().get(0).text()).contains("[[Antwortschlüssel]]");
        SynonymCandidate named = find(collected, "Sachbearbeitung", "Fallbearbeitung").orElseThrow();
        assertThat(named.reason()).isEqualTo(SynonymCandidate.Reason.PHRASE);
    }

    @Test
    void twoConfirmedExpansionsOfOneAcronymBecomeACandidate() {
        cached("Der RSL steht für Rückmeldeschlüssel. Der Rückmeldeschlüssel ist Pflicht.");
        cached("Der RSL ist der Rückmeldungsschlüssel der Meldung. Der Rückmeldungsschlüssel wird vergeben.");
        relations.add(EDITOR, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION,
                RelationScope.GLOBAL, null, List.of()));
        relations.add(EDITOR, new TermRelations.NewRelation("RSL", "Rückmeldungsschlüssel",
                RelationType.ABBREVIATION, new RelationScope("wiki", null), null, List.of()));

        SynonymCandidates.Collected collected = collect(SynonymScope.source("wiki"));

        SynonymCandidate siblings = find(collected, "Rückmeldeschlüssel", "Rückmeldungsschlüssel").orElseThrow();
        assertThat(siblings.reason()).isEqualTo(SynonymCandidate.Reason.ACRONYM_SIBLINGS);
        assertThat(siblings.reason().ruleType()).isNull();
        assertThat(siblings.fromContext()).isNotEmpty();
        assertThat(siblings.toContext()).isNotEmpty();
    }

    @Test
    void aSynonymConfirmedInAnotherSourceIsACandidateHereButNotInItsOwnScope() {
        cached("Der Sachbearbeiter prüft die Akte. Der Bearbeiter zeichnet ab.");
        cached("intern", "OPS", "Der Sachbearbeiter und der Bearbeiter sind dieselbe Rolle.");
        relations.add(EDITOR, new TermRelations.NewRelation("Sachbearbeiter", "Bearbeiter", RelationType.SYNONYM,
                new RelationScope("intern", null), null, List.of()));

        SynonymCandidates.Collected wiki = collect(SynonymScope.source("wiki"));
        SynonymCandidates.Collected intern = collect(SynonymScope.source("intern"));

        assertThat(find(wiki, "Sachbearbeiter", "Bearbeiter")).isPresent()
                .get().extracting(SynonymCandidate::reason).isEqualTo(SynonymCandidate.Reason.CONFIRMED_ELSEWHERE);
        assertThat(find(intern, "Sachbearbeiter", "Bearbeiter")).isEmpty();
    }

    @Test
    void embeddingNeighboursBecomeCandidatesAndOnlyReleasedSourcesAreEmbedded() {
        provider = Optional.of(keywords);
        cached("Der Sachbearbeiter prüft den Vorgang in der Fachabteilung.");
        cached("Der Bearbeiter zeichnet die Akte ab.");
        cached("Der Antrag liegt der Behörde vor.");
        cached("intern", "OPS", "Das Geheimnis bleibt im Haus beim Bearbeiter.");

        SynonymCandidates.Collected collected = collect(SynonymScope.ALL);

        assertThat(collected.embeddingUsed()).isTrue();
        SynonymCandidate similar = find(collected, "Sachbearbeiter", "Bearbeiter").orElseThrow();
        assertThat(similar.reason()).isEqualTo(SynonymCandidate.Reason.EMBEDDING);
        assertThat(similar.similarity()).isGreaterThan(SynonymCandidates.MIN_SIMILARITY);
        assertThat(find(collected, "Antrag", "Bearbeiter")).isEmpty();
        assertThat(find(collected, "Antrag", "Sachbearbeiter")).isEmpty();
        // Text nicht freigegebener Quellen geht nie an das Modell, auch nicht als Kontext eines Begriffs.
        assertThat(embedded).noneSatisfy(text -> assertThat(text).contains("Geheimnis"));
        assertThat(embedded).noneSatisfy(text -> assertThat(text).contains("im Haus"));
        assertThat(embedded).anySatisfy(text -> assertThat(text).startsWith("Sachbearbeiter: "));
    }

    @Test
    void aFailingEmbeddingServiceLeavesTheRulesUsable() {
        provider = Optional.of(new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "down@1";
            }

            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new EmbeddingUnavailableException("nicht erreichbar");
            }
        });
        cached("Die E-Mail geht an die Fachabteilung.");
        cached("Jede Email wird archiviert.");

        SynonymCandidates.Collected collected = collect(SynonymScope.source("wiki"));

        assertThat(collected.embeddingUsed()).isFalse();
        assertThat(find(collected, "E-Mail", "Email")).isPresent();
    }

    @Test
    void theBudgetLimitsTermsAndEmbeddedTerms() {
        provider = Optional.of(keywords);
        cached("Der Sachbearbeiter prüft den Vorgang. Der Sachbearbeiter entscheidet.");
        cached("Der Sachbearbeiter und der Bearbeiter prüfen den Antrag.");

        SynonymCandidates.Collected limited = candidates.collect(SynonymScope.source("wiki"),
                new SynonymBudget(1, 500, 200), null);
        SynonymCandidates.Collected unembedded = candidates.collect(SynonymScope.source("wiki"),
                new SynonymBudget(500, 0, 200), null);

        assertThat(limited.terms()).isEqualTo(1);
        assertThat(limited.embeddingUsed()).isFalse();
        assertThat(unembedded.embeddingUsed()).isFalse();
        assertThat(unembedded.terms()).isGreaterThan(1);
    }

    @Test
    void aSpaceScopeOnlySeesPagesOfThatSpace() {
        cached("wiki", "DEV", "Die E-Mail geht an die Fachabteilung.");
        cached("wiki", "OPS", "Jede Email wird archiviert. Jede E-Mail auch.");
        cached("wiki", "OPS", "Die Email ist Pflicht.");

        SynonymCandidates.Collected dev = collect(SynonymScope.space("wiki", "DEV"));
        SynonymCandidates.Collected ops = collect(SynonymScope.space("wiki", "OPS"));

        assertThat(find(dev, "E-Mail", "Email")).isEmpty();
        SynonymCandidate inOps = find(ops, "E-Mail", "Email").orElseThrow();
        assertThat(inOps.fromContext()).allSatisfy(context -> assertThat(context.evidence().pageId())
                .isNotEqualTo("1"));
    }

    @Test
    void foldingKeepsLettersAndDigitsOnly() {
        assertThat(SynonymCandidates.fold("e-mail")).isEqualTo("email");
        assertThat(SynonymCandidates.fold("rückmeldung")).isEqualTo("rueckmeldung");
        assertThat(SynonymCandidates.fold("straße 1")).isEqualTo("strasse1");
    }
}
