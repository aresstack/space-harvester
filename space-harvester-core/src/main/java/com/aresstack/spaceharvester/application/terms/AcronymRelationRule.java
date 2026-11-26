package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.LocalReadingText;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Regelbasierte Vorschläge (#120 Abschnitt 2, Vorarbeit für #122): Jede Ausschreibung, die der Prozessor {@code terms}
 * nach der Anfangsbuchstabenregel direkt neben einem Akronym fand („Application Programming Interface (API)“), wird
 * eine vorgeschlagene Abkürzungsbeziehung ({@code RULE}, {@code PROPOSED}) für genau diese Quelle, belegt mit der
 * ersten Fundstelle je Seite. Ohne KI und ohne Netz, nur aus dem Begriffsindex; nichts wird bestätigt. Der Lauf gleicht
 * über {@link TermRelations#propose} ab: Abgelehnte bleiben gesperrt, bestätigte unberührt.
 *
 * <p>Zählt nur Seiten im Cache, deren Begriffe zur aktuellen Revision gehören. Der Core startet ihn nach jeder
 * Anreicherung einer Quelle selbst ({@link #refresh}); von Hand starten darf ihn, wer {@code sync} auf die Quelle hat; sichtbar werden die Vorschläge je Leser nur mit lesbarem Beleg.
 */
public class AcronymRelationRule {

    /** Kennung des Laufs in Protokoll und {@code createdBy}. */
    public static final String ACTOR = "rule:acronyms";

    private final SourceCatalog catalog;
    private final TermIndex index;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final TermRelations relations;
    private final String processorVersion;
    private final Clock clock;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final LocalReadingText localReading;

    public AcronymRelationRule(SourceCatalog catalog, TermIndex index, AccessGuard access,
                               Function<PageKey, Optional<PageHeader>> headers, TermRelations relations,
                               String processorVersion, Clock clock) {
        this(catalog, index, access, headers, relations, processorVersion, clock, null, null);
    }

    public AcronymRelationRule(SourceCatalog catalog, TermIndex index, AccessGuard access,
                               Function<PageKey, Optional<PageHeader>> headers, TermRelations relations,
                               String processorVersion, Clock clock,
                               Function<PageKey, Optional<PageSnapshot>> snapshots,
                               LocalReadingText localReading) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.relations = Objects.requireNonNull(relations, "relations");
        this.processorVersion = Objects.requireNonNull(processorVersion, "processorVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshots = snapshots;
        this.localReading = localReading;
    }

    /**
     * Gleicht die Regelvorschläge einer Quelle ab.
     *
     * @throws UnknownSourceException bei unbekannter Quelle
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code sync}
     */
    public TermRelations.ProposalRun run(AccessContext caller, String sourceId) {
        if (catalog.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        return refresh(sourceId);
    }

    /**
     * Gleicht die Regelvorschläge einer Quelle ohne Aufrufer ab: für den Anreicherungsauftrag nach einem Abgleich, der
     * schon unter den Rechten seines Auslösers läuft. Idempotent; unverändert belegte Vorschläge werden nicht neu
     * geschrieben, abgelehnte nie wieder vorgeschlagen. Eine unbekannte Quelle ergibt einen leeren Lauf.
     *
     * <p>Läufe sind serialisiert: Anreicherungen zweier Spaces derselben Quelle können gleichzeitig enden, und ein
     * älterer Durchgang durch den Index darf den Abgleich eines neueren nicht überschreiben.
     */
    public synchronized TermRelations.ProposalRun refresh(String sourceId) {
        if (catalog.find(sourceId).isEmpty()) {
            return new TermRelations.ProposalRun(0, 0, 0, 0, 0);
        }
        TermIndex.Filter filter = new TermIndex.Filter(Set.of(sourceId), TermKind.ACRONYM, null, null,
                processorVersion);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<PageKey, Optional<PageHeader>> pages = new LinkedHashMap<>();
        Map<PageKey, Optional<LocalReadingText.Reading>> readings = new HashMap<>();
        String from = null;
        while (true) {
            List<TermPosting> batch = index.postings(filter, from, false, TermIndex.MAX_BATCH);
            for (TermPosting posting : batch) {
                if (posting.expansion() == null || posting.occurrences().isEmpty()) {
                    continue;
                }
                Optional<RelationEvidence> evidence = evidence(posting,
                        pages.computeIfAbsent(posting.page(), headers), readings);
                if (evidence.isEmpty()) {
                    continue;
                }
                String key = posting.normalized() + '\u0000' + TermText.normalize(posting.expansion());
                Candidate candidate = candidates.computeIfAbsent(key,
                        ignored -> new Candidate(posting.term(), posting.expansion()));
                if (candidate.evidence.size() < TermRelations.MAX_EVIDENCE) {
                    candidate.evidence.add(evidence.get());
                }
            }
            if (batch.size() < TermIndex.MAX_BATCH) {
                break;
            }
            from = batch.get(batch.size() - 1).sortKey();
        }
        List<TermRelations.Proposal> proposals = new ArrayList<>();
        candidates.values().forEach(candidate -> proposals.add(new TermRelations.Proposal(candidate.term,
                candidate.expansion, RelationType.ABBREVIATION, candidate.evidence)));
        return relations.propose(ACTOR, RelationOrigin.RULE, new RelationScope(sourceId, null),
                Set.of(RelationType.ABBREVIATION), "terms-" + processorVersion + "@" + clock.instant(), proposals);
    }

    private Optional<RelationEvidence> evidence(TermPosting posting, Optional<PageHeader> header,
                                                Map<PageKey, Optional<LocalReadingText.Reading>> readings) {
        if (header.isEmpty()) {
            return Optional.empty();
        }
        if (posting.document().localOriginal()) {
            if (snapshots == null || localReading == null || posting.extraction() == null) {
                return Optional.empty();
            }
            if (!readings.containsKey(posting.page())) {
                readings.clear();
            }
            return readings.computeIfAbsent(posting.page(), key -> snapshots.apply(key).flatMap(localReading::read))
                    .filter(reading -> posting.extraction().startsWith(
                            com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments.basis(
                                    reading.extraction()) + "|"))
                    .flatMap(reading -> reading.slices().stream()
                            .filter(slice -> slice.document().equals(posting.document())
                                    && slice.section() == posting.documentSection()
                                    && slice.text().inputSha256().equals(posting.inputSha256()))
                            .flatMap(slice -> posting.occurrences().stream()
                                    .filter(occurrence -> slice.contains(occurrence.start(), occurrence.end()))
                                    .map(occurrence -> new RelationEvidence(posting.page().sourceId(),
                                            posting.page().pageId(), slice.offset() + occurrence.start(),
                                            slice.offset() + occurrence.end(), reading.text().inputSha256())))
                            .findFirst());
        }
        if (!current(posting, header)) {
            return Optional.empty();
        }
        TermOccurrence first = posting.occurrences().getFirst();
        return Optional.of(new RelationEvidence(posting.page().sourceId(), posting.page().pageId(),
                first.start(), first.end(), posting.inputSha256()));
    }

    private static boolean current(TermPosting posting, Optional<PageHeader> header) {
        // Ohne Revision zählt der letzte Lauf; ein Beleg nennt seinen Texthash, Snippets prüfen ihn beim Abruf.
        return header.isPresent() && Objects.equals(posting.revision(), header.get().revision());
    }

    private static final class Candidate {

        final String term;
        final String expansion;
        final List<RelationEvidence> evidence = new ArrayList<>();

        Candidate(String term, String expansion) {
            this.term = term;
            this.expansion = expansion;
        }
    }
}
