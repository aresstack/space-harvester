import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.acropolis.chalcotheca.anagraphai.LuceneRawKnowledgeIndex;
import com.aresstack.enterpriseai.astu.acropolis.chalcotheca.pinakes.LuceneKnowledgeIndex;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.ExportDocumentNode;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.FoundResource;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.IndexKnowledgeUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.IndexingListener;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.IndexingReport;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.KnowledgeDocumentReader;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.KnowledgePrefetchService;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.SearchDrivenIndexingUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.SourceCalls;
import com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymExtractor;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymFindings;
import com.aresstack.enterpriseai.astu.acropolis.textmining.AcronymSettings;
import com.aresstack.enterpriseai.astu.acropolis.textmining.TextFinding;
import com.aresstack.enterpriseai.searchrun.api.KnowledgeSearchRun;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.SearchKnowledgeSourcesUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.SourceSearchOutcome;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.KnowledgeBookmarkIds;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.KnowledgeExportUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.PageNavigationUseCase;
import com.aresstack.enterpriseai.astu.acropolis.source.KnowledgeSourceManagement;
import com.aresstack.enterpriseai.astu.acropolis.source.SourceDataWithdrawal;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeChunker;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeChunkingPolicy;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceDefinition;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpTransport;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ParityConfluence;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingPort;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.LocalFilesKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.exedra.document.ExportFormat;
import com.aresstack.enterpriseai.proasteion.exedra.document.ExportGraphics;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceDefinitionStore;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.spaceharvester.parity.ParitySpace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Exportiert den Paritäts-Space mit dem Enterprise AI Client, so wie dessen Navigation „Exportieren“ es tut:
 * {@link KnowledgeExportUseCase#subtree} ab dem Space und die Writer aus {@link ExportFormat}.
 *
 * <p>Nur für {@code generate.sh}; Java 8, übersetzt gegen den Klassenpfad des Enterprise AI Client. Schreibt
 * {@code parity.docx}, {@code parity.pdf}, {@code parity.md}, je Format die Liste der nicht eingebetteten Bilder
 * ({@code problems-<format>.txt}), die Leseansicht je Seite ({@code reading/}), die Navigation
 * ({@code navigation.properties}), Quellenverwaltung, Adressformen und Suche ({@code search.properties}).</p>
 */
public final class EacParityExport {

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("Ordner fehlt: " + out);
        }
        FakeConfluence confluence = ParityConfluence.install(new FakeConfluence());
        SecretProvider none = new SecretProvider() {
            @Override
            public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
                throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref, "keine");
            }
        };
        ConfluenceKnowledgeSource source = new ConfluenceKnowledgeSource(SourceAddress.of("parity"),
                ConfluenceConfig.builder(FakeConfluence.BASE).build(), confluence, none);
        NavigationNode space = source.position(KnowledgeResourceId.of("confluence",
                FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + ParityConfluence.HOME)).root();
        BooleanSupplier never = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return false;
            }
        };
        ExportDocumentNode tree = new KnowledgeExportUseCase(new PageNavigationUseCase())
                .subtree(source, space, new KnowledgeExportUseCase.Progress() {
                    @Override
                    public void pageLoaded(int loaded, int found, String title) {
                    }
                }, never);
        write(tree, ExportFormat.DOCX, new File(out, "parity.docx"), new File(out, "problems-docx.txt"));
        write(tree, ExportFormat.PDF, new File(out, "parity.pdf"), new File(out, "problems-pdf.txt"));
        write(tree, ExportFormat.MARKDOWN, new File(out, "parity.md"), new File(out, "problems-md.txt"));
        scopes(source, never, out);
        readingViews(source, new File(out, "reading"));
        navigation(source, new File(out, "navigation.properties"));
        management(confluence, none, new File(out, "management.properties"));
        addresses(source, new File(out, "addresses.properties"));
        search(source, new File(out, "search.properties"), new File(out, "search-runs.properties"));
        facts(new File(out, "facts.properties"));
    }

    /**
     * Akronym-Fakten (#182) wie {@code DocumentAcronymsUseCase} des Enterprise AI Client: je Datei aus
     * {@link ParitySpace#FILES} der Text der Dateiquelle, {@link AcronymExtractor} mit den Standardregeln und dem
     * regelbasierten Satzsplitter, daraus {@link AcronymFindings}. Je Datei die Kurzformen der
     * {@code ACRONYM_CANDIDATE} und die Ausschreibungen der {@code ACRONYM_DEFINITION} als {@code KURZ=lang},
     * Kurzform groß, Ausschreibung klein, sortiert; {@code -} ohne Fakten.
     */
    private static void facts(File target) throws Exception {
        Path files = Files.createDirectories(Files.createTempDirectory("parity-facts").resolve("files"));
        for (String[] file : ParitySpace.FILES) {
            Files.write(files.resolve(file[0]), file[1].getBytes(StandardCharsets.UTF_8));
        }
        LocalFilesKnowledgeSource local = new LocalFilesKnowledgeSource(SourceAddress.of("docs"), files,
                DocumentExtraction.detector(), DocumentExtraction.registry(), 20L * 1024 * 1024);
        AcronymExtractor extractor = new AcronymExtractor(SentenceSegmenter.splitter());
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            List<KnowledgeResource> resources = local.discover(
                    SourceScope.builder().startPoints(".").maxDepth(20).maxResources(5000).build());
            TreeSet<String> names = new TreeSet<String>();
            for (KnowledgeResource resource : resources) {
                String id = resource.id().value();
                String name = id.substring(id.lastIndexOf('/') + 1);
                if (!names.add(name)) {
                    continue;
                }
                String text = local.load(resource.id()).text();
                TreeSet<String> candidates = new TreeSet<String>();
                TreeSet<String> definitions = new TreeSet<String>();
                for (TextFinding finding : AcronymFindings.of(extractor.analyze(text,
                        AcronymSettings.defaults()), text)) {
                    String shortForm = String.valueOf(finding.fields().get("shortForm")).toUpperCase(Locale.ROOT);
                    if (AcronymFindings.ACRONYM_CANDIDATE.equals(finding.type())) {
                        candidates.add(shortForm);
                    } else if (AcronymFindings.ACRONYM_DEFINITION.equals(finding.type())) {
                        definitions.add(shortForm + "=" + String.valueOf(finding.fields().get("longForm"))
                                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
                    }
                }
                out.println("facts." + name + ".candidates="
                        + (candidates.isEmpty() ? "-" : join(new ArrayList<String>(candidates))));
                out.println("facts." + name + ".definitions="
                        + (definitions.isEmpty() ? "-" : join(new ArrayList<String>(definitions))));
            }
        } finally {
            out.close();
        }
    }

    /**
     * Suchlauf-Protokoll (#182) wie die Suchleiste des Enterprise AI Client: {@link SearchDrivenIndexingUseCase}
     * baut je Suche einen {@link KnowledgeSearchRun}; hier die Live-Suche in Confluence. Suchtext, gefragte Quellen,
     * ausgefallene Quellen und die Zahl der erhaltenen Kandidaten (jede Seite einmal).
     */
    private static void searchRun(ConfluenceKnowledgeSource confluence, KnowledgePrefetchService prefetch,
                                  File target) throws Exception {
        KnowledgeSearchRun run = new SearchDrivenIndexingUseCase(prefetch, SourceCalls.direct(), null)
                .search("parity", Collections.<KnowledgeSourcePort>singletonList(confluence),
                        ParityConfluence.LIVE_SEARCH, 10).run();
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            out.println("run.live.query=" + run.query());
            out.println("run.live.sources=" + run.sources().size());
            out.println("run.live.failures=" + run.failures().size());
            out.println("run.live.candidates=" + run.candidates().size());
        } finally {
            out.close();
        }
    }

    /**
     * Suche (#180) wie die Suchleiste des Enterprise AI Client: {@link SearchKnowledgeSourcesUseCase} mit
     * {@code withIndexedHits}. Eine Dateiquelle ohne eigene Suche wird erst indexiert und dann im Wissensindex gesucht
     * (Index-Rückfall); je Suche aus {@link ParitySpace#SEARCHES} die gefundenen Dateien und ob jeder Auszug den
     * Suchbegriff zeigt. Danach die Live-Suche in Confluence und das Vorladen ihrer Treffer über den
     * {@link KnowledgePrefetchService} in den Rohindex, wie nach jeder Suche in der Suchleiste. Zuletzt der Suchlauf
     * derselben Live-Suche ({@code runs}).
     */
    private static void search(ConfluenceKnowledgeSource confluence, File target, File runs) throws Exception {
        Path work = Files.createTempDirectory("parity-search");
        Path files = Files.createDirectories(work.resolve("files"));
        for (String[] file : ParitySpace.FILES) {
            Files.write(files.resolve(file[0]), file[1].getBytes(StandardCharsets.UTF_8));
        }
        final EmbeddingModelIdentity space = EmbeddingModelIdentity.of("parity-fixed", 4);
        EmbeddingPort embeddings = new EmbeddingPort() {
            @Override
            public EmbeddingModelIdentity modelIdentity() {
                return space;
            }

            @Override
            public EmbeddingBatch embed(List<String> texts) {
                List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
                for (int i = 0; i < texts.size(); i++) {
                    vectors.add(EmbeddingVector.of(space, new float[]{1f, 0f, 0f, 0f}));
                }
                return EmbeddingBatch.of(space, texts.size(), vectors);
            }
        };
        LuceneKnowledgeIndex index = new LuceneKnowledgeIndex(work.resolve("knowledge"));
        LuceneRawKnowledgeIndex raw = new LuceneRawKnowledgeIndex(work.resolve("raw"));
        KnowledgePrefetchService prefetch = null;
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            IndexKnowledgeUseCase indexing = new IndexKnowledgeUseCase(index, embeddings, space,
                    new KnowledgeChunker(KnowledgeChunkingPolicy.defaults()));
            LocalFilesKnowledgeSource local = new LocalFilesKnowledgeSource(SourceAddress.of("docs"), files,
                    DocumentExtraction.detector(), DocumentExtraction.registry(), 20L * 1024 * 1024);
            IndexingReport report = indexing.indexSource(local,
                    SourceScope.builder().startPoints(".").maxDepth(20).maxResources(5000).build(),
                    IndexingListener.none());
            if (!report.isComplete() || report.discoveryFailed()) {
                throw new IllegalStateException("Dateiquelle nicht indexiert: " + report);
            }
            Executor direct = new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
            SearchKnowledgeSourcesUseCase search = new SearchKnowledgeSourcesUseCase(index,
                    new Supplier<EmbeddingModelIdentity>() {
                        @Override
                        public EmbeddingModelIdentity get() {
                            return space;
                        }
                    }, direct).withIndexedHits();
            for (String[] query : ParitySpace.SEARCHES) {
                SourceSearchOutcome outcome = searchOne(search, local, query[1]);
                TreeSet<String> found = new TreeSet<String>();
                boolean snippets = true;
                for (FoundResource hit : outcome.hits()) {
                    String id = hit.resourceId().value();
                    found.add(id.substring(id.lastIndexOf('/') + 1));
                    snippets &= hit.snippet() != null
                            && hit.snippet().toLowerCase(Locale.ROOT).contains(query[1].toLowerCase(Locale.ROOT));
                }
                out.println("search." + query[0] + "=" + (found.isEmpty() ? "-" : join(new ArrayList<String>(found))));
                out.println("search." + query[0] + ".snippet=" + (found.isEmpty() ? "-" : snippets ? "ja" : "nein"));
            }

            prefetch = new KnowledgePrefetchService(indexing, raw, KnowledgeDocumentReader.direct(),
                    new Supplier<KnowledgePrefetchService.Settings>() {
                        @Override
                        public KnowledgePrefetchService.Settings get() {
                            return KnowledgePrefetchService.Settings.defaults();
                        }
                    });
            SourceSearchOutcome live = searchOne(search, confluence, ParityConfluence.LIVE_SEARCH);
            List<KnowledgePrefetchService.Candidate> candidates = new ArrayList<KnowledgePrefetchService.Candidate>();
            for (FoundResource hit : live.hits()) {
                candidates.add(new KnowledgePrefetchService.Candidate(confluence, hit.resourceId(), hit.revision()));
            }
            prefetch.prefetch("search", candidates, 0).awaitAvailable(candidates.size(), 30_000L,
                    new java.util.function.IntConsumer() {
                        @Override
                        public void accept(int value) {
                        }
                    });
            for (String page : ParityConfluence.LIVE_HITS) {
                KnowledgeResourceId id = KnowledgeResourceId.of("confluence",
                        FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + page);
                out.println("prefetch." + page + "=" + (raw.get(id).isPresent() ? "ja" : "nein"));
            }
            searchRun(confluence, prefetch, runs);
        } finally {
            out.close();
            if (prefetch != null) {
                prefetch.shutdown();
            }
            raw.close();
            index.close();
        }
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            text.append(text.length() == 0 ? "" : ",").append(value);
        }
        return text.toString();
    }

    /** Eine Suche in einer Quelle; scheitert sie, bricht die Erzeugung ab, statt „keine Treffer“ zu melden. */
    private static SourceSearchOutcome searchOne(SearchKnowledgeSourcesUseCase search, KnowledgeSourcePort source,
                                                 String text) {
        SourceSearchOutcome outcome = search.search(Collections.singletonList(source), text, 10).join().get(0);
        if (outcome.failed()) {
            throw new IllegalStateException("Suche nach " + text + " in " + source.sourceId() + ": "
                    + outcome.problem());
        }
        return outcome;
    }

    /**
     * Adressformen (#178): je Form aus {@link ParitySpace#ADDRESSES} die Seiten-ID, auf die {@code resolveAddress}
     * führt, {@code -} wenn die Adresse auf keine Seite führt.
     */
    private static void addresses(ConfluenceKnowledgeSource source, File target) throws Exception {
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            for (String[] form : ParitySpace.ADDRESSES) {
                String page;
                try {
                    String address = ParitySpace.address(form[1], FakeConfluence.BASE.toString());
                    // Bookmarks und Ressourcen-IDs nehmen die Lesewerkzeuge (KnowledgeBookmarkIds), Web-Adressen
                    // die Quelle selbst; beides prüft, ob die Seite existiert
                    boolean web = address.startsWith("http://") || address.startsWith("https://");
                    KnowledgeResourceId resource = !web && KnowledgeBookmarkIds.isBookmark(address)
                            ? KnowledgeBookmarkIds.resourceIdOf(address)
                            : !web && address.startsWith("confluence:")
                            ? KnowledgeResourceId.of("confluence", address.substring("confluence:".length()))
                            : source.resolveAddress(address);
                    if (resource != null) {
                        source.load(resource);
                    }
                    String resolved = id(resource);
                    page = resolved == null ? "-" : resolved;
                } catch (com.aresstack.enterpriseai.source.api.KnowledgeSourceException e) {
                    // Nur "führt auf keine Seite" zählt als "-"; Netz- oder Antwortfehler brechen die Erzeugung ab,
                    // damit eine gestörte Referenz keine Lücke des Core verdeckt
                    if (e.kind() == com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind.UNAVAILABLE
                            || e.kind() == com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind.INVALID_RESPONSE) {
                        throw e;
                    }
                    page = "-";
                }
                out.println(form[0] + "=" + page);
            }
        } finally {
            out.close();
        }
    }

    /** Basis-URL einer zweiten Confluence-Instanz (anderes Ziel) und einer, die nicht antwortet. */
    private static final String OTHER = "https://confluence2.example.org/wiki";
    private static final String UNREACHABLE = "https://unreachable.example.org/wiki";

    /**
     * Quellenverwaltung (#188) über {@code KnowledgeSourceManagement}, wie der Dialog „Wissensquellen“ sie benutzt:
     * Einstellungen prüfen, Verbindung testen, hinzufügen, ändern, abschalten, entfernen. Jede Zeile ist ein
     * Ergebnis ({@code ok}/{@code problems}, {@code success}/{@code failure}, {@code true}/{@code false}), das
     * {@code CoreParityTest} mit {@code Harvester.sourceManagement()} nachstellt. Der Typ ist der echte
     * Confluence-Provider (Felder, Prüfung, Adresse); nur sein Transport ist der Fake.
     */
    private static void management(final FakeConfluence confluence, final SecretProvider none, File target)
            throws Exception {
        final ConfluenceSourceProvider real = new ConfluenceSourceProvider(none, null, null, null,
                new ConfluenceSourceProvider.ClientCertificateSockets() {
                    @Override
                    public javax.net.ssl.SSLSocketFactory socketFactory(String alias, java.nio.file.Path file,
                                                                        SecretRef password) {
                        return null;
                    }
                });
        KnowledgeSourceProvider fake = new KnowledgeSourceProvider() {
            @Override
            public KnowledgeSourceType type() {
                return real.type();
            }

            @Override
            public List<String> validate(SourceSettings settings) {
                return real.validate(settings);
            }

            @Override
            public SourceScope scope(SourceSettings settings) {
                return real.scope(settings);
            }

            @Override
            public String locator(SourceSettings settings) {
                return real.locator(settings);
            }

            @Override
            public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
                URI base = URI.create(settings.get("baseUrl"));
                ConfluenceHttpTransport transport = base.toString().equals(UNREACHABLE)
                        ? new ConfluenceHttpTransport() {
                            @Override
                            public ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes)
                                    throws IOException {
                                throw new java.net.ConnectException("Connection refused");
                            }
                        }
                        : confluence;
                return new ConfluenceKnowledgeSource(sourceId, ConfluenceConfig.builder(base).build(), transport,
                        none);
            }
        };
        final List<String> withdrawn = new ArrayList<String>();
        KnowledgeSourceManagement management = new KnowledgeSourceManagement(
                java.util.Collections.singletonList(fake), new MemoryStore(), null)
                .withWithdrawal(new SourceDataWithdrawal() {
                    @Override
                    public void withdraw(SourceAddress sourceId) {
                        withdrawn.add(sourceId.toString());
                    }
                });
        SourceDefinition draft = management.draft(ConfluenceSourceProvider.TYPE_ID, null);
        SourceDefinition wiki = draft.withSettings(draft.settings().with("title", "Wiki")
                .with("baseUrl", FakeConfluence.BASE.toString()));
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            out.println("validate.complete=" + problems(management.validate(wiki, null)));
            out.println("validate.missing-url=" + problems(management.validate(draft, null)));
            out.println("test.reachable=" + outcome(management.checkConnection(wiki).successful()));
            out.println("test.unreachable=" + outcome(management.checkConnection(
                    wiki.withSettings(wiki.settings().with("baseUrl", UNREACHABLE))).successful()));
            out.println("test.incomplete=" + outcome(management.checkConnection(draft).successful()));

            management.save(wiki, null);
            String address = management.addressOf(wiki);
            out.println("add.listed=" + (management.find(address) != null));
            out.println("validate.same-target=" + problems(management.validate(
                    wiki.withSettings(wiki.settings().with("title", "Wiki (Kopie)")), null)));

            int before = withdrawn.size();
            SourceDefinition renamed = wiki.withSettings(wiki.settings().with("title", "Handbuch-Wiki"));
            management.save(renamed, address);
            out.println("change.same-target.withdrawn=" + (withdrawn.size() > before));

            before = withdrawn.size();
            SourceDefinition moved = renamed.withSettings(renamed.settings().with("baseUrl", OTHER));
            management.save(moved, address);
            address = management.addressOf(moved);
            out.println("change.other-target.withdrawn=" + (withdrawn.size() > before));

            management.setEnabled(address, false);
            out.println("disable=" + (management.find(address).enabled() ? "fehlt" : "ok"));

            before = withdrawn.size();
            management.remove(address);
            out.println("remove.listed=" + (management.find(address) != null));
            out.println("remove.withdrawn=" + (withdrawn.size() > before));
        } finally {
            out.close();
        }
    }

    private static String problems(List<String> problems) {
        return problems.isEmpty() ? "ok" : "problems";
    }

    private static String outcome(boolean successful) {
        return successful ? "success" : "failure";
    }

    /** Ablage im Speicher statt Konfigurationsdatei, Reihenfolge wie gespeichert. */
    private static final class MemoryStore implements SourceDefinitionStore {

        private final List<SourceDefinition> definitions = new ArrayList<SourceDefinition>();

        @Override
        public List<SourceDefinition> definitions() {
            return new ArrayList<SourceDefinition>(definitions);
        }

        @Override
        public void save(SourceDefinition definition, String originalAddress) {
            for (int i = 0; i < definitions.size(); i++) {
                if (originalAddress != null && definitions.get(i).address().equals(originalAddress)) {
                    definitions.set(i, definition);
                    return;
                }
            }
            definitions.add(definition);
        }

        @Override
        public void remove(String address) {
            for (int i = 0; i < definitions.size(); i++) {
                if (definitions.get(i).address().equals(address)) {
                    definitions.remove(i);
                    return;
                }
            }
        }

        @Override
        public void setEnabled(String address, boolean enabled) {
            for (int i = 0; i < definitions.size(); i++) {
                if (definitions.get(i).address().equals(address)) {
                    definitions.set(i, definitions.get(i).withEnabled(enabled));
                }
            }
        }
    }

    /**
     * Navigation je Seite als Seiten-IDs: {@code <seite>.children} (Kinder in Reihenfolge), {@code <seite>.ancestors}
     * (Breadcrumbs von oben, ohne Space), {@code <seite>.links} (ausgehende Seitenverweise), dazu {@code space.name}
     * und {@code space.children} (Wurzelseiten).
     */
    private static void navigation(ConfluenceKnowledgeSource source, File target) throws Exception {
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            PagePosition home = source.position(pageId(ParityConfluence.HOME));
            out.println("space.name=" + home.root().title());
            out.println("space.children=" + children(source, home.root()));
            for (String page : ParityConfluence.PAGES) {
                PagePosition position = source.position(pageId(page));
                StringBuilder ancestors = new StringBuilder();
                for (NavigationNode ancestor : position.ancestors()) {
                    ancestors.append(ancestors.length() == 0 ? "" : ",").append(id(ancestor.resource()));
                }
                StringBuilder links = new StringBuilder();
                for (SourceLink link : source.crossReferences(pageId(page)).outgoing()) {
                    String linked = id(link.target());
                    if (linked != null) {
                        links.append(links.length() == 0 ? "" : ",").append(linked);
                    }
                }
                out.println(page + ".children=" + children(source, position.page()));
                out.println(page + ".ancestors=" + ancestors);
                out.println(page + ".links=" + links);
            }
        } finally {
            out.close();
        }
    }

    private static String children(ConfluenceKnowledgeSource source, NavigationNode parent) throws Exception {
        StringBuilder ids = new StringBuilder();
        String cursor = null;
        NavigationChildren batch;
        do {
            batch = source.children(parent, cursor);
            for (NavigationNode child : batch.nodes()) {
                ids.append(ids.length() == 0 ? "" : ",").append(id(child.resource()));
            }
            cursor = batch.nextCursor();
        } while (!batch.complete());
        return ids.toString();
    }

    private static KnowledgeResourceId pageId(String page) {
        return KnowledgeResourceId.of("confluence",
                FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + page);
    }

    /** Seiten-ID aus der Ressource ({@code …/page/<id>}), sonst {@code null}. */
    private static String id(KnowledgeResourceId resource) {
        if (resource == null) {
            return null;
        }
        String value = resource.value();
        int at = value.lastIndexOf("/page/");
        return at < 0 ? null : value.substring(at + "/page/".length());
    }

    /**
     * Leseansicht je Seite, wie der Lesebereich und die KI sie bekommen: Lesetext aus {@code load} nach
     * {@code reading/<seite>.txt}, Zahl der Bilder aus {@code pageView} nach {@code reading/images.properties}.
     */
    private static void readingViews(ConfluenceKnowledgeSource source, File out) throws Exception {
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("Ordner fehlt: " + out);
        }
        PrintWriter images = new PrintWriter(new File(out, "images.properties"), "UTF-8");
        try {
            for (String page : ParityConfluence.PAGES) {
                KnowledgeResourceId id = pageId(page);
                PrintWriter text = new PrintWriter(new File(out, page + ".txt"), "UTF-8");
                try {
                    text.print(source.load(id).text());
                } finally {
                    text.close();
                }
                images.println(page + "=" + source.pageView(id).images().size());
            }
        } finally {
            images.close();
        }
    }

    /**
     * Exportumfang (#186) wie „Exportieren“ an einem Seitenknoten der Navigation: {@link KnowledgeExportUseCase#page}
     * für eine einzelne Seite ohne Unterseiten ({@code scope-page.md}) und {@link KnowledgeExportUseCase#subtree} ab
     * der Seite {@link ParityConfluence#SCOPE_ROOT} mit ihr als Kapitelwurzel ({@code scope-subtree.md}).
     */
    private static void scopes(ConfluenceKnowledgeSource source, BooleanSupplier never, File out) throws Exception {
        KnowledgeExportUseCase.Progress quiet = new KnowledgeExportUseCase.Progress() {
            @Override
            public void pageLoaded(int loaded, int found, String title) {
            }
        };
        KnowledgeExportUseCase export = new KnowledgeExportUseCase(new PageNavigationUseCase());
        NavigationNode root = source.position(pageId(ParityConfluence.SCOPE_ROOT)).page();
        ExportDocumentNode page = export.page(source, root.resource(), root.title(), quiet, never);
        write(page, ExportFormat.MARKDOWN, new File(out, "scope-page.md"), new File(out, "problems-scope-page.txt"));
        ExportDocumentNode subtree = export.subtree(source, root, quiet, never);
        write(subtree, ExportFormat.MARKDOWN, new File(out, "scope-subtree.md"),
                new File(out, "problems-scope-subtree.txt"));
    }

    private static void write(ExportDocumentNode tree, ExportFormat format, File target, File problems)
            throws Exception {
        ExportGraphics graphics = ExportGraphics.standard();
        OutputStream stream = new FileOutputStream(target);
        try {
            format.writer(graphics).write(tree, stream);
        } finally {
            stream.close();
        }
        PrintWriter list = new PrintWriter(problems, "UTF-8");
        try {
            for (String problem : graphics.problems()) {
                list.println(problem);
            }
        } finally {
            list.close();
        }
    }
}
