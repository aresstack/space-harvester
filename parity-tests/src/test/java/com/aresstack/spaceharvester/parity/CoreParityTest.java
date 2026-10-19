package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ParityConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.ParityFtp;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.ParityGit;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.ParityFiles;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.facts.FactTypes;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.facts.PageFacts;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.searchrun.SearchRun;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.core.SourceManagement;
import com.aresstack.spaceharvester.parity.ParityComparison.Finding;
import com.aresstack.spaceharvester.parity.ParityComparison.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derselbe Paritäts-Space durch den Core wie durch den Enterprise AI Client (Referenzen in
 * {@code src/test/resources/eac}, erzeugt von {@code eac/generate.sh}); ADR 0012, docs/PARITY.md, #193.
 *
 * <p>Jede Prüfung, bei der der Core weniger kann, muss in {@code known-gaps.properties} mit ihrem Issue stehen.
 * Eine neue Lücke (Rückschritt) lässt den Test scheitern. Eine geschlossene Lücke meldet der Bericht
 * ({@code build/reports/parity/parity-report.md}); dann wird ihre Zeile entfernt.</p>
 */
class CoreParityTest {

    private static final String[] FORMATS = {"docx", "pdf", "markdown"};

    private static FakeConfluenceServer server;
    @TempDir
    static Path data;
    private static final Map<String, Path> CORE = new LinkedHashMap<>();
    /** Markdown der Exportumfänge (#186) wie {@code eac/scope-*.md}: Einzelseite und Teilbaum ab der Seite. */
    private static final Map<String, Path> CORE_SCOPES = new LinkedHashMap<>();
    private static final Map<String, String> CORE_READING = new LinkedHashMap<>();
    private static final Map<String, Integer> CORE_READING_IMAGES = new LinkedHashMap<>();
    private static final Map<String, String> CORE_NAVIGATION = new LinkedHashMap<>();
    private static final Map<String, String> CORE_MANAGEMENT = new LinkedHashMap<>();
    private static final Map<String, String> CORE_ADDRESSES = new LinkedHashMap<>();
    /** Nicht ausgeführte Makros je Format ({@code OMITTED_CONTENT} der Konvertierung). */
    private static final Map<String, List<String>> CORE_OMITTED = new LinkedHashMap<>();
    @TempDir
    static Path managed;
    private static final Map<String, String> CORE_SEARCH = new LinkedHashMap<>();
    private static final Map<String, String> CORE_FACTS = new LinkedHashMap<>();
    private static final Map<String, String> CORE_RUNS = new LinkedHashMap<>();
    private static final Map<String, String> CORE_GIT = new LinkedHashMap<>();
    private static final Map<String, String> CORE_FILES = new LinkedHashMap<>();
    private static final Map<String, String> CORE_FTP = new LinkedHashMap<>();
    @TempDir
    static Path gitData;
    @TempDir
    static Path searched;

    @BeforeAll
    static void exportThroughTheCore() throws Exception {
        server = new FakeConfluenceServer(ParityConfluence.install(new FakeConfluence()));
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", server.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build()) {
            // Wie in der App: herunterladen (JSON-Datenbestand), Bilder beschaffen lassen, dann aus dem Datenbestand
            // konvertieren; die Konverter fragen die Quelle nicht an
            JobStatus export = harvester.await(harvester.export("wiki", ParityConfluence.SPACE, List.of("json")).id(),
                    Duration.ofSeconds(60));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            JobStatus assets = harvester.await(harvester.jobs().list(Harvester.OWNER, JobType.ASSETS).get(0).id(),
                    Duration.ofSeconds(60));
            assertThat(assets.state()).isEqualTo(JobState.COMPLETED);
            // Neben dem Bericht ablegen, damit man die Core-Exporte öffnen und mit den Referenzen vergleichen kann
            Path exports = reportFile().getParent().resolve("core");
            Files.createDirectories(exports);
            for (String format : FORMATS) {
                String extension = format.equals("markdown") ? "md" : format;
                Path target = exports.resolve("core." + extension);
                ExportConversions.ConvertedFile converted = harvester.conversions().convert(export.id(),
                        Harvester.OWNER, format, Files.createTempDirectory("parity-" + format));
                Files.move(converted.path(), target, StandardCopyOption.REPLACE_EXISTING);
                CORE.put(extension, target);
                CORE_OMITTED.put(extension, converted.notices().stream()
                        .filter(notice -> notice.kind() == ConversionNotice.Kind.OMITTED_CONTENT)
                        .map(notice -> notice.pageId() + " " + notice.reference() + " (" + notice.reason() + ")")
                        .toList());
            }
            // Exportumfänge (#186) wie „Exportieren“ an einem Seitenknoten: die Seite allein und ihr Teilbaum
            scope(harvester, "page", ExportRequest.of("wiki", ParityConfluence.SPACE, Harvester.OWNER, List.of("json"))
                    .withPage(ParityConfluence.SCOPE_ROOT), exports);
            scope(harvester, "subtree", ExportRequest.of("wiki", ParityConfluence.SPACE, Harvester.OWNER, List.of("json"))
                    .withRoot(ParityConfluence.SCOPE_ROOT), exports);
            // Leseansicht je Seite (#176), aus derselben Quelle wie der Export
            AccessContext caller = AccessContext.technicalClient(Harvester.OWNER);
            for (String page : ParityConfluence.PAGES) {
                ReadingView view = harvester.readingViews().view(caller, "wiki", page, false);
                if (view.status() == ReadingView.Status.AVAILABLE) {
                    CORE_READING.put(page, view.text());
                    CORE_READING_IMAGES.put(page, view.images().size());
                }
            }
            navigation(harvester, caller);
            addresses(harvester, caller);
        }
        assertThat(CORE).containsOnlyKeys("docx", "pdf", "md");
        sourceManagement();
        search();
        git();
        files();
        ftp();
    }

    /** Lädt einen Exportumfang herunter, wartet auf seine Bilder und konvertiert ihn wie den ganzen Space nach Markdown. */
    private static void scope(Harvester harvester, String name, ExportRequest request, Path exports) throws Exception {
        JobStatus export = harvester.await(harvester.exports().start(request).id(), Duration.ofSeconds(60));
        assertThat(export.state()).as(name).isEqualTo(JobState.COMPLETED);
        for (JobStatus assets : harvester.jobs().list(Harvester.OWNER, JobType.ASSETS)) {
            harvester.await(assets.id(), Duration.ofSeconds(60));
        }
        Path target = exports.resolve("scope-" + name + ".md");
        ExportConversions.ConvertedFile converted = harvester.conversions().convert(export.id(), Harvester.OWNER,
                "markdown", Files.createTempDirectory("parity-scope-" + name));
        Files.move(converted.path(), target, StandardCopyOption.REPLACE_EXISTING);
        CORE_SCOPES.put(name, target);
    }

    /**
     * Suche (#180) wie {@code EacParityExport.search}: Eine Dateiquelle ohne eigene Suche wird abgeglichen und dann im
     * Volltextindex des Caches gesucht (Status {@code INDEXED}); danach die Live-Suche in Confluence, deren sichtbare
     * Treffer der Core im Hintergrund in den Cache lädt ({@code prefetchSearchHits}, Standard an). Dieselben Schlüssel
     * wie {@code eac/search.properties}.
     */
    private static void search() throws Exception {
        Path files = Files.createDirectories(searched.resolve("files"));
        for (String[] file : ParitySpace.FILES) {
            Files.writeString(files.resolve(file[0]), file[1], StandardCharsets.UTF_8);
        }
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                        Map.of("directory", files.toString())))
                .source(config("wiki", "Wiki", server.baseUrl()))
                .dataDirectory(searched.resolve("data"))
                .build()) {
            JobStatus sync = harvester.await(harvester.sync("docs", "docs").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            awaitEnrichments(harvester);
            facts(harvester);
            for (String[] query : ParitySpace.SEARCHES) {
                KeywordSearch.Results results = harvester.keywordSearch().search(harvester.caller(),
                        new KeywordSearch.Query(query[1], "docs", Set.of(), Set.of()), null, 10);
                assertThat(results.sources()).extracting(KeywordSearch.SourceState::status)
                        .containsExactly(KeywordSearch.SourceState.Status.INDEXED);
                Set<String> found = new TreeSet<>();
                boolean snippets = true;
                for (KeywordSearch.Hit hit : results.hits()) {
                    found.add(new String(Base64.getUrlDecoder().decode(hit.pageId()), StandardCharsets.UTF_8));
                    snippets &= hit.snippet() != null && hit.snippet().toLowerCase(Locale.ROOT)
                            .contains(query[1].toLowerCase(Locale.ROOT));
                }
                CORE_SEARCH.put("search." + query[0], found.isEmpty() ? "-" : String.join(",", found));
                CORE_SEARCH.put("search." + query[0] + ".snippet", found.isEmpty() ? "-" : snippets ? "ja" : "nein");
            }

            harvester.keywordSearch().search(harvester.caller(),
                    new KeywordSearch.Query(ParityConfluence.LIVE_SEARCH, "wiki", Set.of(), Set.of()), null, 10);
            Instant deadline = Instant.now().plusSeconds(30);
            while (!cachedPages(harvester).containsAll(List.of(ParityConfluence.LIVE_HITS))
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            Set<String> cached = cachedPages(harvester);
            for (String page : ParityConfluence.LIVE_HITS) {
                CORE_SEARCH.put("prefetch." + page, cached.contains(page) ? "ja" : "nein");
            }
            searchRun(harvester);
        }
    }

    /**
     * Git-Quelle (#187) wie {@code EacParityExport.git}: das Paritäts-Repository {@link ParityGit} als Typ {@code git},
     * je Stand ({@code main}, {@code tag} = {@link ParityGit#TAG}) die Pfade der Seiten, ihr Titel und welche Sätze
     * der abgelegte Inhalt enthält. Dieselben Schlüssel wie {@code eac/git.properties}.
     */
    private static void git() throws Exception {
        Path repository = gitData.resolve("parity.git");
        ParityGit.create(repository);
        gitRef(repository, null, "main");
        gitRef(repository, ParityGit.TAG, "tag");
    }

    private static void gitRef(Path repository, String ref, String prefix) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("url", repository.toString());
        if (ref != null) {
            options.put("ref", ref);
        }
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("parity", "git", "Parität", null, null, options))
                .dataDirectory(gitData.resolve("data-" + prefix))
                .build()) {
            JobStatus sync = harvester.await(harvester.sync("parity", "parity").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            Map<String, ListedPage> byPath = new TreeMap<>();
            for (ListedPage page : harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages()) {
                byPath.put(gitPath(page.header().key().pageId()), page);
            }
            CORE_GIT.put("git." + prefix + ".documents", String.join(",", byPath.keySet()));
            byPath.forEach((path, page) -> {
                String text = new String(harvester.pages().page(harvester.caller(), "parity",
                        page.header().key().pageId(), false).snapshot().payload().bytes(), StandardCharsets.UTF_8);
                List<String> found = new ArrayList<>();
                for (int i = 0; i < ParityGit.SENTENCES.length; i++) {
                    if (text.contains(ParityGit.SENTENCES[i])) {
                        found.add(String.valueOf(i));
                    }
                }
                if (text.contains(ParityGit.OLD_SENTENCE)) {
                    found.add("old");
                }
                CORE_GIT.put("git." + prefix + ".title." + path, page.header().metadata().title());
                CORE_GIT.put("git." + prefix + ".sentences." + path, found.isEmpty() ? "-" : String.join(",", found));
            });
        }
    }

    /**
     * Dateiquelle (#193) wie {@code EacParityExport.files}: der Ordner {@link ParityFiles} als Typ {@code files} mit
     * {@code exclude} = {@link ParityFiles#EXCLUDE}; je Pfad Titel und Sätze im abgelegten Inhalt. Dieselben Schlüssel
     * wie {@code eac/files.properties}.
     */
    private static void files() throws Exception {
        Path directory = gitData.resolve("pfiles");
        ParityFiles.create(directory);
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("parity", "files", "Parität", null, null,
                        Map.of("directory", directory.toString(), "exclude", ParityFiles.EXCLUDE)))
                .dataDirectory(gitData.resolve("data-files"))
                .build()) {
            JobStatus sync = harvester.await(harvester.sync("parity", "parity").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            Map<String, ListedPage> byPath = new TreeMap<>();
            for (ListedPage page : harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages()) {
                // Ressourcen-ID der Ordner-Quelle: …/<Quelle>/<Pfad relativ zum Ordner, Base64url>
                String value = page.header().metadata().resourceId();
                byPath.put(new String(Base64.getUrlDecoder().decode(value.substring(value.lastIndexOf('/') + 1)),
                        StandardCharsets.UTF_8), page);
            }
            CORE_FILES.put("files.all.documents", String.join(",", byPath.keySet()));
            byPath.forEach((path, page) -> {
                String text = new String(harvester.pages().page(harvester.caller(), "parity",
                        page.header().key().pageId(), false).snapshot().payload().bytes(), StandardCharsets.UTF_8);
                List<String> found = new ArrayList<>();
                for (int i = 0; i < ParityFiles.SENTENCES.length; i++) {
                    if (text.contains(ParityFiles.SENTENCES[i])) {
                        found.add(String.valueOf(i));
                    }
                }
                if (text.contains(ParityFiles.EXCLUDED_SENTENCE)) {
                    found.add("excluded");
                }
                CORE_FILES.put("files.all.title." + path, page.header().metadata().title());
                CORE_FILES.put("files.all.sentences." + path, found.isEmpty() ? "-" : String.join(",", found));
            });
        }
    }

    /**
     * FTP-Quelle (#193) wie {@code EacParityExport.ftp}: der Server {@link ParityFtp} als Typ {@code ftp}, anonym ab
     * {@link ParityFtp#START_POINT}, mit {@link ParityFtp#DEPTH} ({@code deep}) und eine Ebene flacher ({@code shallow}).
     */
    private static void ftp() throws Exception {
        try (ParityFtp server = ParityFtp.start()) {
            ftpDepth(server, ParityFtp.DEPTH, "deep");
            ftpDepth(server, ParityFtp.DEPTH - 1, "shallow");
        }
    }

    private static void ftpDepth(ParityFtp server, int depth, String prefix) throws Exception {
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("parity", "ftp", "Parität", null, null,
                        Map.of("host", server.host(), "port", String.valueOf(server.port()),
                                "startPoints", ParityFtp.START_POINT, "maxDepth", String.valueOf(depth))))
                .dataDirectory(gitData.resolve("data-ftp-" + prefix))
                .build()) {
            JobStatus sync = harvester.await(harvester.sync("parity", "parity").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            Map<String, ListedPage> byPath = new TreeMap<>();
            for (ListedPage page : harvester.pages().pages(harvester.caller(), "parity", "parity", null, 100).pages()) {
                // Seiten-ID = Ressourcen-ID des EAC als Base64url; Host und Port wechseln je Lauf
                String value = new String(Base64.getUrlDecoder().decode(page.header().key().pageId()),
                        StandardCharsets.UTF_8);
                int start = value.indexOf(ParityFtp.START_POINT + "/");
                byPath.put(start < 0 ? value : value.substring(start), page);
            }
            CORE_FTP.put("ftp." + prefix + ".documents", String.join(",", byPath.keySet()));
            byPath.forEach((path, page) -> {
                String text = new String(harvester.pages().page(harvester.caller(), "parity",
                        page.header().key().pageId(), false).snapshot().payload().bytes(), StandardCharsets.UTF_8);
                List<String> found = new ArrayList<>();
                for (int i = 0; i < ParityFtp.SENTENCES.length; i++) {
                    if (text.contains(ParityFtp.SENTENCES[i])) {
                        found.add(String.valueOf(i));
                    }
                }
                CORE_FTP.put("ftp." + prefix + ".title." + path, page.header().metadata().title());
                CORE_FTP.put("ftp." + prefix + ".sentences." + path, found.isEmpty() ? "-" : String.join(",", found));
            });
        }
    }

    /** Pfad im Repository aus der Seiten-ID ({@code …/parity[@<ref>]/<pfad>}), wie {@code EacParityExport.gitPath}. */
    private static String gitPath(String pageId) {
        String value = new String(Base64.getUrlDecoder().decode(pageId), StandardCharsets.UTF_8);
        int repository = value.lastIndexOf("/parity/") >= 0 ? value.lastIndexOf("/parity/")
                : value.lastIndexOf("/parity@");
        return value.substring(value.indexOf('/', repository + 1) + 1);
    }

    /**
     * Akronym-Fakten (#182) wie {@code EacParityExport.facts}: je Datei die Kurzformen der {@code ACRONYM_CANDIDATE}
     * und die Ausschreibungen der {@code ACRONYM_DEFINITION} aus {@code Harvester.facts()}, Kurzform groß,
     * Ausschreibung klein (die Argumente sind normalisiert); dieselben Schlüssel wie {@code eac/facts.properties}.
     */
    private static void facts(Harvester harvester) {
        Map<String, Set<String>> candidates = new TreeMap<>();
        Map<String, Set<String>> definitions = new TreeMap<>();
        for (String[] file : ParitySpace.FILES) {
            candidates.put(file[0], new TreeSet<>());
            definitions.put(file[0], new TreeSet<>());
        }
        for (String type : List.of(FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION)) {
            PageFacts.Results results = harvester.facts().search(harvester.caller(),
                    new PageFacts.Search(type, List.of(), "docs", Set.of(), Set.of()), null, PageFacts.MAX_LIMIT);
            for (MetadataFact fact : results.facts()) {
                String file = new String(Base64.getUrlDecoder().decode(fact.page().pageId()), StandardCharsets.UTF_8);
                String shortForm = String.join(" ", fact.values(FactTypes.SHORT_FORM)).toUpperCase(Locale.ROOT);
                if (type.equals(FactTypes.ACRONYM_CANDIDATE)) {
                    candidates.computeIfAbsent(file, key -> new TreeSet<>()).add(shortForm);
                } else {
                    definitions.computeIfAbsent(file, key -> new TreeSet<>()).add(shortForm + "="
                            + String.join(" ", fact.values(FactTypes.LONG_FORM)).toLowerCase(Locale.ROOT));
                }
            }
        }
        candidates.forEach((file, values) -> CORE_FACTS.put("facts." + file + ".candidates",
                values.isEmpty() ? "-" : String.join(",", values)));
        definitions.forEach((file, values) -> CORE_FACTS.put("facts." + file + ".definitions",
                values.isEmpty() ? "-" : String.join(",", values)));
    }

    /**
     * Suchlauf-Protokoll (#182) wie {@code EacParityExport.searchRun}: der Lauf der Live-Suche in Confluence aus
     * {@code Harvester.searchRuns()}; Suchtext, gefragte und ausgefallene Quellen, geprüfte Funde der Quellen.
     */
    private static void searchRun(Harvester harvester) {
        SearchRun run = harvester.searchRuns().recent(harvester.caller(), 50).stream()
                .filter(candidate -> candidate.kind() == SearchRun.Kind.KEYWORD
                        && ParityConfluence.LIVE_SEARCH.equals(candidate.query())
                        && candidate.sources().stream().anyMatch(source -> source.sourceId().equals("wiki")))
                .findFirst().orElse(null);
        if (run == null) {
            return;
        }
        CORE_RUNS.put("run.live.query", run.query());
        CORE_RUNS.put("run.live.sources", String.valueOf(run.sources().size()));
        CORE_RUNS.put("run.live.failures", String.valueOf(run.sources().stream()
                .filter(source -> source.status() == SearchRun.SourceOutcome.Status.UNAVAILABLE).count()));
        run.count(SearchRun.Stage.DISCOVERED).ifPresent(count ->
                CORE_RUNS.put("run.live.candidates", String.valueOf(count)));
    }

    private static Set<String> cachedPages(Harvester harvester) {
        try {
            Set<String> pages = new TreeSet<>();
            harvester.pages().pages(harvester.caller(), "wiki", ParityConfluence.SPACE, null, 100).pages()
                    .forEach(page -> pages.add(page.pageId()));
            return pages;
        } catch (CacheMissException e) {
            return Set.of();
        }
    }

    private static void awaitEnrichments(Harvester harvester) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            var enrichments = harvester.jobs().list(Harvester.OWNER, JobType.ENRICHMENT);
            if (!enrichments.isEmpty() && enrichments.stream().allMatch(job -> job.finishedAt() != null)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Anreicherung läuft nach 60 s noch");
    }

    /**
     * Quellenverwaltung (#188) über {@code Harvester.sourceManagement()}, Schritt für Schritt wie
     * {@code EacParityExport.management} mit {@code KnowledgeSourceManagement}; dieselben Schlüssel wie
     * {@code eac/management.properties}. Ob Daten zurückgezogen sind, zeigt der Cache: Nach dem Abgleich liefert
     * die Seitenliste des Space Seiten, danach nur noch {@link CacheMissException}.
     */
    private static void sourceManagement() throws Exception {
        try (Harvester harvester = Harvester.builder().dataDirectory(managed).build()) {
            SourceManagement management = harvester.sourceManagement();
            SourceSettingsConfig wiki = config("wiki", "Wiki", server.baseUrl());
            SourceSettingsConfig incomplete = config("wiki", "Wiki", null);
            put("validate.complete", management.validate(wiki, false));
            put("validate.missing-url", management.validate(incomplete, false));
            CORE_MANAGEMENT.put("test.reachable", outcome(management.test(wiki, false)));
            CORE_MANAGEMENT.put("test.unreachable",
                    outcome(management.test(config("wiki", "Wiki", URI.create("http://127.0.0.1:1/wiki")), false)));
            CORE_MANAGEMENT.put("test.incomplete", outcome(management.test(incomplete, false)));

            management.add(wiki);
            CORE_MANAGEMENT.put("add.listed", String.valueOf(listed(management, "wiki")));
            put("validate.same-target", management.validate(config("wiki-kopie", "Wiki (Kopie)",
                    server.baseUrl()), false));

            synced(harvester);
            management.change(config("wiki", "Handbuch-Wiki", server.baseUrl()));
            CORE_MANAGEMENT.put("change.same-target.withdrawn", String.valueOf(!cached(harvester)));
            management.change(config("wiki", "Handbuch-Wiki", URI.create("http://127.0.0.1:1/wiki")));
            CORE_MANAGEMENT.put("change.other-target.withdrawn", String.valueOf(!cached(harvester)));

            management.setEnabled("wiki", false);
            CORE_MANAGEMENT.put("disable", !management.enabled("wiki") && listed(management, "wiki") ? "ok" : "fehlt");
            management.setEnabled("wiki", true);

            management.change(wiki);
            synced(harvester);
            management.remove("wiki");
            CORE_MANAGEMENT.put("remove.listed", String.valueOf(listed(management, "wiki")));
            management.add(wiki);
            CORE_MANAGEMENT.put("remove.withdrawn", String.valueOf(!cached(harvester)));
        }
    }

    private static SourceSettingsConfig config(String id, String title, URI baseUrl) {
        return new SourceSettingsConfig(id, "confluence", title, baseUrl, SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "2000"));
    }

    private static void put(String key, List<String> problems) {
        CORE_MANAGEMENT.put(key, problems.isEmpty() ? "ok" : "problems");
    }

    private static String outcome(SourceManagement.ConnectionCheck check) {
        return check.successful() ? "success" : "failure";
    }

    private static boolean listed(SourceManagement management, String sourceId) {
        return management.sources().stream().anyMatch(source -> source.id().equals(sourceId));
    }

    private static void synced(Harvester harvester) throws Exception {
        JobStatus sync = harvester.await(harvester.sync("wiki", ParityConfluence.SPACE).id(), Duration.ofSeconds(60));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        assertThat(cached(harvester)).as("Seiten nach dem Abgleich im Cache").isTrue();
    }

    private static boolean cached(Harvester harvester) {
        try {
            return !harvester.pages().pages(harvester.caller(), "wiki", ParityConfluence.SPACE, null, 10).pages()
                    .isEmpty();
        } catch (CacheMissException e) {
            return false;
        }
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theCoreFallsBehindOnlyWhereAGapIsKnown() throws Exception {
        Map<String, String> gaps = knownGaps();
        StringBuilder report = new StringBuilder("# Paritätsbericht Paritäts-Space (Fake)\n\nEnterprise AI Client ")
                .append(Files.readString(reference("COMMIT")).strip()).append(" gegen Core.\n");
        List<String> regressions = new ArrayList<>();
        for (String extension : List.of("docx", "pdf", "md")) {
            DocumentOutline eac = ParityReport.read(reference("parity." + extension));
            DocumentOutline core = ParityReport.read(CORE.get(extension));
            List<Finding> findings = new ArrayList<>(ParityComparison.compare(eac, core));
            findings.add(ParityComparison.macros(extension, eac, core, List.of(ParityConfluence.MACRO_SENTENCES),
                    CORE_OMITTED.get(extension)));
            report.append('\n').append(ParityReport.section(extension.toUpperCase(), findings, gaps));
            for (Finding finding : findings) {
                if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                    regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                            + finding.core() + "; " + finding.detail() + ")");
                }
            }
        }
        List<Finding> reading = ParityComparison.readingViews(eacReading(), CORE_READING, eacReadingImages(),
                CORE_READING_IMAGES);
        report.append('\n').append(ParityReport.section("Leseansicht", reading, gaps));
        for (Finding finding : reading) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        List<Finding> navigation = ParityComparison.navigation(eacNavigation(), CORE_NAVIGATION);
        report.append('\n').append(ParityReport.section("Navigation", navigation, gaps));
        for (Finding finding : navigation) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        List<Finding> addresses = ParityComparison.addresses(eacProperties("addresses.properties"), CORE_ADDRESSES);
        report.append('\n').append(ParityReport.section("Adressen", addresses, gaps));
        for (Finding finding : addresses) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        List<Finding> search = ParityComparison.search(eacProperties("search.properties"), CORE_SEARCH);
        report.append('\n').append(ParityReport.section("Suche", search, gaps));
        for (Finding finding : search) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        List<Finding> facts = ParityComparison.facts(eacProperties("facts.properties"), CORE_FACTS);
        report.append('\n').append(ParityReport.section("Fakten", facts, gaps));
        List<Finding> runs = ParityComparison.searchRuns(eacProperties("search-runs.properties"), CORE_RUNS);
        report.append('\n').append(ParityReport.section("Suchlauf", runs, gaps));
        List<Finding> git = ParityComparison.git(eacProperties("git.properties"), CORE_GIT);
        report.append('\n').append(ParityReport.section("Git-Quelle", git, gaps));
        List<Finding> factsAndRuns = new ArrayList<>(facts);
        factsAndRuns.addAll(runs);
        factsAndRuns.addAll(git);
        List<Finding> files = ParityComparison.files(eacProperties("files.properties"), CORE_FILES);
        report.append('\n').append(ParityReport.section("Dateiquelle", files, gaps));
        factsAndRuns.addAll(files);
        List<Finding> ftp = ParityComparison.ftp(eacProperties("ftp.properties"), CORE_FTP);
        report.append('\n').append(ParityReport.section("FTP-Quelle", ftp, gaps));
        factsAndRuns.addAll(ftp);
        for (Finding finding : factsAndRuns) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        List<Finding> management = ParityComparison.sourceManagement(eacManagement(), CORE_MANAGEMENT);
        report.append('\n').append(ParityReport.section("Quellenverwaltung", management, gaps));
        for (Finding finding : management) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        Map<String, String> eacTypes = eacProperties("documents/types.properties");
        Map<String, String> coreTypes = new LinkedHashMap<>();
        Map<String, String> coreTexts = new LinkedHashMap<>();
        Map<String, String> eacTexts = documentTexts(eacTypes, coreTypes, coreTexts);
        List<Finding> documents = ParityComparison.documents(eacTypes, coreTypes, eacTexts, coreTexts);
        report.append('\n').append(ParityReport.section("Dokumenttext", documents, gaps));
        for (Finding finding : documents) {
            if (finding.verdict() == Verdict.CORE_WENIGER && !gaps.containsKey(finding.key())) {
                regressions.add(finding.key() + " (" + finding.title() + ": EAC " + finding.eac() + ", Core "
                        + finding.core() + "; " + finding.detail() + ")");
            }
        }
        Path file = reportFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, report, StandardCharsets.UTF_8);
        assertThat(regressions).as("Neue Paritätslücken (ADR 0012); Bericht:%n%s", report).isEmpty();
    }

    /**
     * Exportumfang (#186) wie im Enterprise AI Client: Die Einzelseite enthält nur ihr eigenes Kapitel, der Teilbaum
     * dieselben Kapitel in derselben Reihenfolge mit der Seite als Wurzel. Darüber hinaus gelten die Prüfungen des
     * ganzen Space; der Core darf nirgends weniger haben und keine Seite außerhalb des Umfangs.
     */
    @Test
    void singlePageAndSubtreeExportsMatchTheEac() throws IOException {
        Map<String, String> gaps = knownGaps();
        for (String name : List.of("page", "subtree")) {
            DocumentOutline eac = ParityReport.read(reference("scope-" + name + ".md"));
            DocumentOutline core = ParityReport.read(CORE_SCOPES.get(name));
            for (Finding finding : ParityComparison.compare(eac, core)) {
                if (!gaps.containsKey(finding.key())) {
                    assertThat(finding.verdict()).as("%s %s: EAC %s, Core %s; %s", name, finding.title(),
                            finding.eac(), finding.core(), finding.detail()).isNotEqualTo(Verdict.CORE_WENIGER);
                }
            }
            assertThat(pageChapters(core)).as(name).isEqualTo(pageChapters(eac));
        }
        String single = ParityReport.read(CORE_SCOPES.get("page")).text();
        assertThat(single).contains("Satz Charlie zur Architektur.").doesNotContain("Satz Echo")
                .doesNotContain("Satz Alpha").doesNotContain("Satz Foxtrot");
        String subtree = ParityReport.read(CORE_SCOPES.get("subtree")).text().replaceAll("\\s+", " ");
        assertThat(subtree).contains("Satz Charlie", "Satz Echo", "Satz Ebene vier", "Satz Ebene sieben")
                .doesNotContain("Satz Alpha").doesNotContain("Satz Foxtrot").doesNotContain("Satz Golf");
    }

    /** Seitenkapitel in Reihenfolge mit ihrer Ebene relativ zum ersten, ohne direkte Wiederholung des Titels. */
    private static List<String> pageChapters(DocumentOutline outline) {
        List<String> chapters = new ArrayList<>();
        String previous = null;
        int base = -1;
        for (DocumentOutline.Heading heading : outline.headings()) {
            String title = heading.text().strip();
            if (title.equals(previous)) {
                continue;
            }
            base = base < 0 ? heading.level() : base;
            chapters.add((heading.level() - base) + " " + title);
            previous = title;
        }
        return chapters;
    }

    @Test
    void everySentenceOfTheSpaceReachesEveryCoreExport() throws IOException {
        for (Path file : CORE.values()) {
            String text = ParityReport.read(file).text().replaceAll("\\s+", " ");
            for (String sentence : ParityConfluence.SENTENCES) {
                assertThat(text).as(file.getFileName().toString()).contains(sentence);
            }
        }
    }

    /** Navigation live über den Core (#177), in denselben Schlüsseln wie {@code eac/navigation.properties}. */
    private static void navigation(Harvester harvester, AccessContext caller) {
        PageNavigation navigation = harvester.navigation();
        PageNavigation.Position home = navigation.position(caller, "wiki", ParityConfluence.HOME);
        CORE_NAVIGATION.put("space.name", home.spaceName() == null ? "" : home.spaceName());
        CORE_NAVIGATION.put("space.children", children(navigation, caller, null));
        for (String page : ParityConfluence.PAGES) {
            CORE_NAVIGATION.put(page + ".children", children(navigation, caller, page));
            CORE_NAVIGATION.put(page + ".ancestors", ids(navigation.position(caller, "wiki", page).ancestors()));
            CORE_NAVIGATION.put(page + ".links", ids(navigation.references(caller, "wiki", page).outgoing()));
        }
    }

    /** Adressformen (#178) über {@code Harvester.addresses()}, in denselben Schlüsseln wie {@code eac/addresses.properties}. */
    private static void addresses(Harvester harvester, AccessContext caller) {
        for (String[] form : ParitySpace.ADDRESSES) {
            String address = ParitySpace.address(form[1], server.baseUrl().toString());
            List<PageAddresses.Match> matches = harvester.addresses().resolve(caller, address).matches();
            CORE_ADDRESSES.put(form[0], matches.isEmpty() ? "-" : matches.get(0).pageId());
        }
    }

    private static String children(PageNavigation navigation, AccessContext caller, String parent) {
        List<HarvestSource.PageNode> pages = new ArrayList<>();
        String cursor = null;
        do {
            PageNavigation.Children batch = navigation.children(caller, "wiki", ParityConfluence.SPACE, parent, cursor);
            pages.addAll(batch.pages());
            cursor = batch.nextCursor();
        } while (cursor != null);
        return ids(pages);
    }

    private static String ids(List<HarvestSource.PageNode> pages) {
        return String.join(",", pages.stream().map(HarvestSource.PageNode::pageId).toList());
    }

    /** {@code eac/management.properties} in der Reihenfolge der Datei (die Schritte bauen aufeinander auf). */
    private static Map<String, String> eacManagement() throws IOException {
        return eacProperties("management.properties");
    }

    /** Eine Referenzdatei {@code schlüssel=wert} in der Reihenfolge der Datei. */
    private static Map<String, String> eacProperties(String name) throws IOException {
        Map<String, String> steps = new LinkedHashMap<>();
        for (String line : Files.readAllLines(reference(name), StandardCharsets.UTF_8)) {
            int at = line.indexOf('=');
            if (at > 0 && !line.startsWith("#")) {
                steps.put(line.substring(0, at).strip(), line.substring(at + 1).strip());
            }
        }
        return steps;
    }

    private static Map<String, String> eacNavigation() throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(reference("navigation.properties"), StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        Map<String, String> navigation = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            navigation.put(key, properties.getProperty(key));
        }
        return navigation;
    }

    private static Map<String, String> eacReading() throws IOException {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String page : ParityConfluence.PAGES) {
            texts.put(page, Files.readString(reference("reading/" + page + ".txt")));
        }
        return texts;
    }

    private static Map<String, Integer> eacReadingImages() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(reference("reading/images.properties"))) {
            properties.load(in);
        }
        Map<String, Integer> images = new LinkedHashMap<>();
        for (String page : ParityConfluence.PAGES) {
            images.put(page, Integer.parseInt(properties.getProperty(page, "0").strip()));
        }
        return images;
    }

    /**
     * Dokumenttext (#183): jede Datei aus {@code documents/} und die Referenzexporte {@code parity.docx} und
     * {@code parity.pdf} durch {@link TikaDocumentText}, mit demselben Typ, mit dem der Enterprise AI Client extrahiert
     * hat ({@code <datei>.type}). Füllt Typ nach dem Inhalt und Text des Core und liefert die Texte des Maßstabs.
     */
    private static Map<String, String> documentTexts(Map<String, String> eacTypes, Map<String, String> coreTypes,
                                                     Map<String, String> coreTexts) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var listing = Files.list(Path.of(CoreParityTest.class.getResource("/documents").toURI()))) {
            listing.forEach(files::add);
        }
        files.add(reference("parity.docx"));
        files.add(reference("parity.pdf"));
        files.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        DocumentText documents = TikaDocumentText.shared();
        Map<String, String> eacTexts = new LinkedHashMap<>();
        for (Path path : files) {
            String name = path.getFileName().toString();
            byte[] content = Files.readAllBytes(path);
            eacTexts.put(name, Files.readString(reference("documents/" + name + ".txt")));
            coreTypes.put(name + ".content-type", documents.detect(content).orElse("-"));
            coreTexts.put(name, documents.extract(name, eacTypes.get(name + ".type"), content).text());
        }
        return eacTexts;
    }

    /** Bericht unter {@code build/reports/parity/} (Gradle setzt {@code parity.report}), sonst im Arbeitsordner. */
    private static Path reportFile() {
        return Path.of(System.getProperty("parity.report", "build/reports/parity/parity-report.md"))
                .toAbsolutePath();
    }

    static Map<String, String> knownGaps() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = CoreParityTest.class.getResourceAsStream("/known-gaps.properties")) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, String> gaps = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            gaps.put(key, properties.getProperty(key).strip());
        }
        return gaps;
    }

    static Path reference(String name) {
        try {
            return Path.of(CoreParityTest.class.getResource("/eac/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (NullPointerException e) {
            throw new UncheckedIOException(new IOException("Referenz fehlt: eac/" + name + " (eac/generate.sh)"));
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
