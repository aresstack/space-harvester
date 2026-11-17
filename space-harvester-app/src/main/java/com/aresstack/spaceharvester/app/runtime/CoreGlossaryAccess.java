package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.glossary.Glossary;
import com.aresstack.spaceharvester.application.glossary.GlossaryClassification;
import com.aresstack.spaceharvester.application.glossary.GlossaryConflictException;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry;
import com.aresstack.spaceharvester.application.glossary.GlossaryUnavailableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.core.Harvester;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Das Fachglossar des Core ({@code Harvester.glossary()}, {@code glossaryClassification()}) mit den Rechten dieser App,
 * wie der Dienst es unter {@code /api/v1/glossary} anbietet. Die Ausnahmen des Core werden in die der Schnittstelle
 * übersetzt (Revision → {@link ConflictException}, kein Modell → {@link AiUnavailableException}, keine Freigabe →
 * {@link NotAllowedException}); alles andere kommt unverändert durch.
 *
 * <p>Ohne Statusfilter liefert der Core nur die nicht abgelehnten Einträge; damit „Alle“ im Reiter auch die
 * abgelehnten zeigt (grau, am Ende wie unter Beziehungen), folgt nach der letzten Seite der offenen eine zweite
 * Folge Seiten mit den abgelehnten. Der Cursor trägt die Phase: {@value #REJECTED_CURSOR} leitet sie ein.
 */
final class CoreGlossaryAccess implements GlossaryAccess {

    static final String REJECTED_CURSOR = "rejected:";

    private final Harvester harvester;
    private final AccessContext caller;

    CoreGlossaryAccess(Harvester harvester, AccessContext caller) {
        this.harvester = Objects.requireNonNull(harvester, "harvester");
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    @Override
    public Page search(Search search) {
        Glossary glossary = harvester.glossary();
        boolean aiAvailable = harvester.glossaryClassification().available();
        if (search.status() != null) {
            Glossary.Page page = glossary.entries(caller, core(search, search.status(), search.cursor()));
            return new Page(entries(page.entries()), page.nextCursor(), aiAvailable);
        }
        String cursor = search.cursor();
        if (cursor != null && cursor.startsWith(REJECTED_CURSOR)) {
            String rest = cursor.substring(REJECTED_CURSOR.length());
            Glossary.Page page = glossary.entries(caller, core(search, ReviewStatus.REJECTED,
                    rest.isEmpty() ? null : rest));
            return new Page(entries(page.entries()), page.nextCursor() == null ? null
                    : REJECTED_CURSOR + page.nextCursor(), aiAvailable);
        }
        Glossary.Page open = glossary.entries(caller, core(search, null, cursor));
        // Die offenen sind zu Ende: die abgelehnten folgen als eigene Folge Seiten.
        return new Page(entries(open.entries()), open.nextCursor() != null ? open.nextCursor() : REJECTED_CURSOR,
                aiAvailable);
    }

    private static Glossary.Search core(Search search, ReviewStatus status, String cursor) {
        return new Glossary.Search(search.q(), status, search.origin(), search.sourceId(), search.spaceKey(),
                search.startsWith(), search.limit(), cursor);
    }

    private static List<Entry> entries(List<GlossaryEntry> entries) {
        List<Entry> result = new ArrayList<>(entries.size());
        for (GlossaryEntry entry : entries) {
            result.add(entry(entry));
        }
        return result;
    }

    static Entry entry(GlossaryEntry e) {
        return new Entry(e.id(), e.term(), e.normalized(), e.scope().sourceId(), e.scope().spaceKey(), e.origin(),
                e.status(), e.evidence(), e.evidenceStale(), e.explanation(), e.generation(), e.createdAt(),
                e.updatedAt(), e.reviewedAt(), e.revision());
    }

    @Override
    public Entry add(NewEntry entry) {
        RelationScope scope = entry.sourceId() == null ? RelationScope.GLOBAL
                : new RelationScope(entry.sourceId(), entry.spaceKey());
        return entry(harvester.glossary().add(caller, new Glossary.NewEntry(entry.term(), scope,
                entry.explanation(), List.of())));
    }

    @Override
    public Entry explanation(String id, String explanation, long revision) {
        return conflicts(() -> entry(harvester.glossary().explain(caller, id, explanation, revision)));
    }

    @Override
    public Entry confirm(String id, long revision) {
        return conflicts(() -> entry(harvester.glossary().confirm(caller, id, revision)));
    }

    @Override
    public Entry reject(String id, long revision) {
        return conflicts(() -> entry(harvester.glossary().reject(caller, id, revision)));
    }

    @Override
    public Entry restore(String id, long revision) {
        return conflicts(() -> entry(harvester.glossary().restore(caller, id, revision)));
    }

    @Override
    public Optional<Entry> remove(String id, long revision) {
        return conflicts(() -> harvester.glossary().remove(caller, id, revision).map(CoreGlossaryAccess::entry));
    }

    private static <T> T conflicts(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (GlossaryConflictException e) {
            throw new ConflictException(e.entryId());
        }
    }

    @Override
    public List<JobStatus> classify(String sourceId, String spaceKey) {
        try {
            return harvester.glossaryClassification().start(caller, sourceId, spaceKey);
        } catch (GlossaryUnavailableException e) {
            if (e.reason() == GlossaryUnavailableException.Reason.NO_MODEL) {
                throw new AiUnavailableException();
            }
            throw new NotAllowedException(e.sourceId() == null ? "(alle)" : e.sourceId());
        }
    }

    @Override
    public JobStatus classification(String jobId) {
        return jobs().status(jobId, caller.jobOwner());
    }

    @Override
    public JobStatus cancelClassification(String jobId) {
        GlossaryClassification classification = harvester.glossaryClassification();
        if (!classification.started(jobId)) {
            throw new IllegalArgumentException("Kein Auftrag „Fachbegriffe ermitteln“: " + jobId);
        }
        return jobs().cancel(jobId, caller.jobOwner());
    }

    @Override
    public Runnable observe(Runnable onChange) {
        return harvester.glossary().subscribe(onChange);
    }

    @Override
    public Runnable observeJobs(java.util.function.Consumer<JobStatus> onChange) {
        // Die Läufe gehören dem Aufrufer der App (wie classification), nicht dem Core-Eigentümer der Abgleiche.
        com.aresstack.spaceharvester.application.observe.Subscription subscription = jobs().subscribe(
                caller.jobOwner(), new com.aresstack.spaceharvester.application.jobs.JobListener() {
                    @Override
                    public void changed(JobStatus status) {
                        onChange.accept(status);
                    }
                });
        return subscription::close;
    }

    private Jobs jobs() {
        return harvester.jobs();
    }

    @Override
    public Optional<TermQueries.Snippet> snippet(RelationEvidence evidence, int chars) {
        return harvester.terms().snippet(caller, evidence.sourceId(), evidence.pageId(), evidence.inputSha256(),
                evidence.start(), evidence.end(), chars);
    }

    @Override
    public Optional<String> pageTitle(String sourceId, String pageId) {
        try {
            String title = harvester.pages().page(caller, sourceId, pageId, false).header().metadata().title();
            return title == null || title.isBlank() ? Optional.empty() : Optional.of(title);
        } catch (RuntimeException e) {
            // Nicht im Cache, nicht sichtbar oder die Quelle antwortet nicht: dann steht die Kennung im Fenster.
            return Optional.empty();
        }
    }
}
