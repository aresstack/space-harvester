package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.util.ArrayList;
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
 * <p>Zählt nur Seiten im Cache, deren Begriffe zur aktuellen Revision gehören. Starten darf ihn, wer {@code sync} auf
 * die Quelle hat; sichtbar werden die Vorschläge je Leser nur mit lesbarem Beleg.
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

    public AcronymRelationRule(SourceCatalog catalog, TermIndex index, AccessGuard access,
                               Function<PageKey, Optional<PageHeader>> headers, TermRelations relations,
                               String processorVersion, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.relations = Objects.requireNonNull(relations, "relations");
        this.processorVersion = Objects.requireNonNull(processorVersion, "processorVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        TermIndex.Filter filter = new TermIndex.Filter(Set.of(sourceId), TermKind.ACRONYM, null, null,
                processorVersion);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<PageKey, Optional<PageHeader>> pages = new LinkedHashMap<>();
        String from = null;
        while (true) {
            List<TermPosting> batch = index.postings(filter, from, false, TermIndex.MAX_BATCH);
            for (TermPosting posting : batch) {
                if (posting.expansion() == null || posting.occurrences().isEmpty()
                        || !current(posting, pages.computeIfAbsent(posting.page(), headers))) {
                    continue;
                }
                String key = posting.normalized() + '\u0000' + TermText.normalize(posting.expansion());
                Candidate candidate = candidates.computeIfAbsent(key,
                        ignored -> new Candidate(posting.term(), posting.expansion()));
                if (candidate.evidence.size() < TermRelations.MAX_EVIDENCE) {
                    TermOccurrence first = posting.occurrences().get(0);
                    candidate.evidence.add(new RelationEvidence(sourceId, posting.page().pageId(), first.start(),
                            first.end(), posting.inputSha256()));
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
                "terms-" + processorVersion + "@" + clock.instant(), proposals);
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
