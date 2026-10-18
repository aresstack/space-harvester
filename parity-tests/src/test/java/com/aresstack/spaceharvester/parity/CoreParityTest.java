package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
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
    private static final Map<String, String> CORE_READING = new LinkedHashMap<>();
    private static final Map<String, Integer> CORE_READING_IMAGES = new LinkedHashMap<>();
    private static final Map<String, String> CORE_NAVIGATION = new LinkedHashMap<>();
    private static final Map<String, String> CORE_MANAGEMENT = new LinkedHashMap<>();
    /** Nicht ausgeführte Makros je Format ({@code OMITTED_CONTENT} der Konvertierung). */
    private static final Map<String, List<String>> CORE_OMITTED = new LinkedHashMap<>();
    @TempDir
    static Path managed;

    @BeforeAll
    static void exportThroughTheCore() throws Exception {
        server = new FakeConfluenceServer(ParitySpace.install(new FakeConfluence()));
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", server.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build()) {
            // Wie in der App: herunterladen (JSON-Datenbestand), Bilder beschaffen lassen, dann aus dem Datenbestand
            // konvertieren; die Konverter fragen die Quelle nicht an
            JobStatus export = harvester.await(harvester.export("wiki", ParitySpace.SPACE, List.of("json")).id(),
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
            // Leseansicht je Seite (#176), aus derselben Quelle wie der Export
            AccessContext caller = AccessContext.technicalClient(Harvester.OWNER);
            for (String page : ParitySpace.PAGES) {
                ReadingView view = harvester.readingViews().view(caller, "wiki", page, false);
                if (view.status() == ReadingView.Status.AVAILABLE) {
                    CORE_READING.put(page, view.text());
                    CORE_READING_IMAGES.put(page, view.images().size());
                }
            }
            navigation(harvester, caller);
        }
        assertThat(CORE).containsOnlyKeys("docx", "pdf", "md");
        sourceManagement();
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
        JobStatus sync = harvester.await(harvester.sync("wiki", ParitySpace.SPACE).id(), Duration.ofSeconds(60));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        assertThat(cached(harvester)).as("Seiten nach dem Abgleich im Cache").isTrue();
    }

    private static boolean cached(Harvester harvester) {
        try {
            return !harvester.pages().pages(harvester.caller(), "wiki", ParitySpace.SPACE, null, 10).pages()
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
            findings.add(ParityComparison.macros(extension, eac, core, List.of(ParitySpace.MACRO_SENTENCES),
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
        List<Finding> management = ParityComparison.sourceManagement(eacManagement(), CORE_MANAGEMENT);
        report.append('\n').append(ParityReport.section("Quellenverwaltung", management, gaps));
        for (Finding finding : management) {
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

    @Test
    void everySentenceOfTheSpaceReachesEveryCoreExport() throws IOException {
        for (Path file : CORE.values()) {
            String text = ParityReport.read(file).text().replaceAll("\\s+", " ");
            for (String sentence : ParitySpace.SENTENCES) {
                assertThat(text).as(file.getFileName().toString()).contains(sentence);
            }
        }
    }

    /** Navigation live über den Core (#177), in denselben Schlüsseln wie {@code eac/navigation.properties}. */
    private static void navigation(Harvester harvester, AccessContext caller) {
        PageNavigation navigation = harvester.navigation();
        PageNavigation.Position home = navigation.position(caller, "wiki", ParitySpace.HOME);
        CORE_NAVIGATION.put("space.name", home.spaceName() == null ? "" : home.spaceName());
        CORE_NAVIGATION.put("space.children", children(navigation, caller, null));
        for (String page : ParitySpace.PAGES) {
            CORE_NAVIGATION.put(page + ".children", children(navigation, caller, page));
            CORE_NAVIGATION.put(page + ".ancestors", ids(navigation.position(caller, "wiki", page).ancestors()));
            CORE_NAVIGATION.put(page + ".links", ids(navigation.references(caller, "wiki", page).outgoing()));
        }
    }

    private static String children(PageNavigation navigation, AccessContext caller, String parent) {
        List<HarvestSource.PageNode> pages = new ArrayList<>();
        String cursor = null;
        do {
            PageNavigation.Children batch = navigation.children(caller, "wiki", ParitySpace.SPACE, parent, cursor);
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
        Map<String, String> steps = new LinkedHashMap<>();
        for (String line : Files.readAllLines(reference("management.properties"), StandardCharsets.UTF_8)) {
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
        for (String page : ParitySpace.PAGES) {
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
        for (String page : ParitySpace.PAGES) {
            images.put(page, Integer.parseInt(properties.getProperty(page, "0").strip()));
        }
        return images;
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
