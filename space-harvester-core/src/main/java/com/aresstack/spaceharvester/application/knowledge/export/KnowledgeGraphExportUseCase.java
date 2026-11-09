package com.aresstack.spaceharvester.application.knowledge.export;

import com.aresstack.spaceharvester.application.knowledge.*;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Export des Knowledge Graph als JSON-LD 1.1 (#499 Abschnitt G, ADR 0024), als eigener Auftrag
 * {@link JobType#KNOWLEDGE_GRAPH_EXPORT} über {@link Jobs} – getrennt vom Dokumentexport ({@code ExportConversions}),
 * weil der Graph quellenübergreifend ist und keinen Raw-First-Datenbestand eines Space hat. Die Datei entsteht wie
 * eine Konvertierung im Ausgabeordner ({@code knowledge-graph-<umfang>-<auftrag>.jsonld}), unsichtbar geschrieben und
 * erst fertig umbenannt; nie überschrieben.
 *
 * <p>Exportiert werden nur vorhandene, für den Aufrufer lesbare Analysen ({@link KnowledgeGraph}); standardmäßig nur
 * {@link PageStatements.State#CURRENT}, veraltete nur auf ausdrücklichen Wunsch. Der Export startet nie eine
 * Analyse, lädt keine Seite und verändert den Graphen nicht. Die Analysen werden zu Beginn auf einmal gelesen (jede
 * Seite atomar), damit ein laufender Analyseauftrag den Export nicht vermischt.
 */
public class KnowledgeGraphExportUseCase {

    /** Komponente in Diagnoseereignissen. */
    public static final String COMPONENT = "Knowledge-Graph-Export";
    /** Quelle eines Auftrags über den ganzen Graphen (alle Quellen). */
    public static final String ALL_SOURCES = "*";

    private final Jobs jobs;
    private final KnowledgeGraph graph;
    private final Function<PageKey, Optional<String>> parents;
    private final Clock clock;
    private final String owner;

    /**
     * @param parents Elternseite einer Seite aus dem Bestand (für den Umfang „Unterzweig“), ohne Quellzugriff
     */
    public KnowledgeGraphExportUseCase(Jobs jobs, KnowledgeGraph graph, Function<PageKey, Optional<String>> parents,
                                       Clock clock, String owner) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.parents = Objects.requireNonNull(parents, "parents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner;
    }

    /** Startet den Export; der Aufrufer wartet nicht. */
    public JobStatus start(AccessContext caller, Request request) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(request, "request");
        Scope scope = request.scope();
        return jobs.submit(JobType.KNOWLEDGE_GRAPH_EXPORT, scope.sourceId() == null ? ALL_SOURCES : scope.sourceId(),
                scope.spaceKey(), scope.pageId(), owner, JobTask.blocking(context -> run(caller, request, context)));
    }

    /** Die Seiten, die ein Export dieses Umfangs enthielte, ohne etwas zu schreiben (Vorschau im Dialog). */
    public List<KnowledgeGraph.AnalysedPage> select(AccessContext caller, Scope scope, boolean includeStale) {
        List<KnowledgeGraph.AnalysedPage> selected = new ArrayList<>();
        for (KnowledgeGraph.AnalysedPage page : graph.analysed(caller)) {
            PageStatements.State state = page.page().state();
            if (state == PageStatements.State.STALE && !includeStale) {
                continue;
            }
            if (scope.contains(page.page(), this::ancestors)) {
                selected.add(page);
            }
        }
        return selected;
    }

    private void run(AccessContext caller, Request request, JobContext context) {
        context.phase(COMPONENT, "Graph lesen");
        List<KnowledgeGraph.AnalysedPage> pages = select(caller, request.scope(), request.includeStale());
        context.discovered(pages.size());
        context.checkpoint();
        List<KnowledgeGraphJsonLd.Entry> entries = pages.stream()
                .map(page -> new KnowledgeGraphJsonLd.Entry(page.analysis(), page.page().state(),
                        page.page().reason())).toList();
        context.phase(COMPONENT, "Ablegen");
        Path directory = request.directory();
        String name = "knowledge-graph-" + request.scope().slug() + "-"
                + context.jobId().substring(0, Math.min(8, context.jobId().length())) + KnowledgeGraphJsonLd.EXTENSION;
        Path target = directory.resolve(name);
        Path temp = directory.resolve("." + name + ".tmp");
        try {
            Files.createDirectories(directory);
            if (Files.exists(target)) {
                throw new JobFailedException("TARGET_EXISTS", "Die Zieldatei gibt es schon; sie wird nicht "
                        + "überschrieben.");
            }
            try (OutputStream out = Files.newOutputStream(temp)) {
                KnowledgeGraphJsonLd.write(out, new KnowledgeGraphJsonLd.Header(context.jobId(),
                        request.scope().describe(), clock.instant(), request.includeStale(), true), entries);
            }
            context.checkpoint();
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Aufräumen ist best effort; die unsichtbare Datei beginnt mit einem Punkt.
            }
        }
        context.processed(pages.size());
        context.written(pages.size());
        context.record(JobEvent.Draft.info(COMPONENT, pages.size() + " Seiten exportiert"));
    }

    /** Vorfahren einer Seite laut Bestand, von unten nach oben, ohne Zyklen. */
    private List<String> ancestors(PageKey key) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Optional<String> parent = parents.apply(key);
        while (parent.isPresent() && seen.add(parent.get())) {
            result.add(parent.get());
            parent = parents.apply(new PageKey(key.sourceId(), parent.get()));
        }
        return result;
    }

    /**
     * @param includeStale veraltete Analysen zusätzlich exportieren (mit Status und Grund)
     * @param directory    Ausgabeordner
     */
    public record Request(Scope scope, boolean includeStale, Path directory) {

        public Request {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(directory, "directory");
        }
    }

    /** Umfang eines Exports. */
    public sealed interface Scope {

        default String sourceId() {
            return null;
        }

        default String spaceKey() {
            return null;
        }

        default String pageId() {
            return null;
        }

        boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors);

        String describe();

        default String slug() {
            return describe().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT);
        }

        /** Der ganze Graph, soweit lesbar. */
        record All() implements Scope {
            @Override
            public boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors) {
                return true;
            }

            @Override
            public String describe() {
                return "alle";
            }
        }

        record Source(String sourceId) implements Scope {
            public Source {
                Objects.requireNonNull(sourceId, "sourceId");
            }

            @Override
            public boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors) {
                return page.key().sourceId().equals(sourceId);
            }

            @Override
            public String describe() {
                return "quelle " + sourceId;
            }
        }

        record Space(String sourceId, String spaceKey) implements Scope {
            public Space {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(spaceKey, "spaceKey");
            }

            @Override
            public boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors) {
                return page.key().sourceId().equals(sourceId) && spaceKey.equals(page.spaceKey());
            }

            @Override
            public String describe() {
                return "space " + sourceId + " " + spaceKey;
            }
        }

        record Subtree(String sourceId, String pageId) implements Scope {
            public Subtree {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(pageId, "pageId");
            }

            @Override
            public boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors) {
                return page.key().sourceId().equals(sourceId) && (page.key().pageId().equals(pageId)
                        || ancestors.apply(page.key()).contains(pageId));
            }

            @Override
            public String describe() {
                return "zweig " + sourceId + " " + pageId;
            }
        }

        record Page(String sourceId, String pageId) implements Scope {
            public Page {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(pageId, "pageId");
            }

            @Override
            public boolean contains(KnowledgeGraph.PageRef page, Function<PageKey, List<String>> ancestors) {
                return page.key().equals(new PageKey(sourceId, pageId));
            }

            @Override
            public String describe() {
                return "seite " + sourceId + " " + pageId;
            }
        }
    }
}
