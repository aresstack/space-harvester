package com.aresstack.spaceharvester.parity;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.parity.ParityComparison.Finding;
import com.aresstack.spaceharvester.parity.ParityComparison.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                try (OutputStream out = Files.newOutputStream(target)) {
                    harvester.conversions().convert(export.id(), Harvester.OWNER, format, out);
                }
                CORE.put(extension, target);
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
            List<Finding> findings = ParityComparison.compare(eac, core);
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
