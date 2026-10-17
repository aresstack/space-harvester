package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Anwendungsfälle der App gegen eine Fake-Confluence über HTTPS mit Client-Zertifikat (mTLS, PKCS#12) und eigenem
 * Truststore ({@code spring.ssl.bundle.jks}) und gegen lokale Dateien, ohne Oberfläche: Konfiguration laden, Quellen
 * und Spaces, Live-Metadaten, Abgleich, Bestand, Baum, Seite, Analyse, Suche, Export und Auftragsliste. Token und
 * Zertifikatspasswort kommen aus Umgebungsvariablen, gehen nur an die Quelle und tauchen in keiner Ablage der App auf.
 */
class WorkspaceTest {

    private static final String TOKEN = "pat-geheim-7f3a";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    @TempDir
    static Path tls;
    private static TestCertificates certificates;
    private static HttpsFakeConfluenceServer server;

    @BeforeAll
    static void startServer() throws Exception {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<h1>Start</h1><p>Willkommen</p>");
        CONFLUENCE.page("102", "Betrieb und Wartung", "DEV", "<p>Inhalt</p>").storage("<p>Der Dienst läuft.</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        certificates = new TestCertificates(tls);
        server = new HttpsFakeConfluenceServer(CONFLUENCE, certificates.serverContext(), true);
    }

    @TempDir
    Path home;

    private AppRuntime runtime;

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void harvestsBrowsesSearchesAndExportsAConfluenceSpace() throws Exception {
        Workspace workspace = start().workspace();

        assertThat(workspace.sources()).extracting(HarvestSource::id).containsExactlyInAnyOrder("wiki", "handbuch");
        assertThat(workspace.spaces("wiki")).extracting(Partition::key).containsExactly("DEV");
        assertThat(workspace.liveMetadata("wiki", "DEV", null, 100).entries()).hasSize(2);

        JobStatus sync = await(workspace, workspace.startSync("wiki", "DEV"));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        SpaceSyncJobs.SyncJob job = workspace.syncStatus(sync.id());
        assertThat(job.summary().complete()).isTrue();
        assertThat(job.summary().added()).isEqualTo(2);

        assertThat(workspace.cachedPages("wiki", "DEV", null, 100).pages())
                .extracting(page -> page.header().key().pageId()).containsExactlyInAnyOrder("100", "102");
        assertThat(workspace.tree("wiki", "DEV").tree()).isNotNull();
        DeliveredPage page = workspace.page("wiki", "102", false);
        assertThat(new String(page.snapshot().payload().bytes(), StandardCharsets.UTF_8)).contains("Der Dienst läuft.");
        assertThat(workspace.enrichments(page)).isNotEmpty();

        PageSearchQuery query = PageSearchQuery.of("wiki", List.of("DEV"), "wartung", null, null, null, null, null,
                SearchSort.TITLE);
        assertThat(workspace.search(query, null, 10).hits()).extracting(hit -> hit.page().header().key().pageId())
                .containsExactly("102");
        assertThat(workspace.semanticSearch()).isFalse();
        assertThat(workspace.relevance(query, "wartung", 10).hits()).isNotEmpty();

        JobStatus export = await(workspace, workspace.startExport("wiki", "DEV", List.of("markdown")));
        assertThat(export.state()).isEqualTo(JobState.COMPLETED);
        ExportArtifact artifact = workspace.exportArtifact(export.id()).orElseThrow();
        assertThat(artifact.complete()).isTrue();
        ExportArtifact.File first = artifact.files().get(0);
        try (InputStream in = workspace.exportFile(export.id(), first.name()).orElseThrow()) {
            assertThat(in.readAllBytes()).hasSize((int) first.bytes());
        }

        assertThat(workspace.jobs()).extracting(JobStatus::type).containsExactly(JobType.EXPORT, JobType.SYNC);
        assertThat(CONFLUENCE.requestHeaders()).anySatisfy(headers ->
                assertThat(headers.values()).contains("Bearer " + TOKEN));
        assertNoFileContains(home, TOKEN);
        assertNoFileContains(home, TestCertificates.CLIENT_PASSWORD);
    }

    @Test
    void readsLocalFilesWithoutNetwork() throws Exception {
        Workspace workspace = start().workspace();

        String space = workspace.spaces("handbuch").get(0).key();
        assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.cachedPages("handbuch", space, null, 100).pages()).hasSize(2);
    }

    @Test
    void theActivityListSurvivesARestart() throws Exception {
        Workspace first = start().workspace();
        String id = await(first, first.startSync("wiki", "DEV")).id();
        runtime.close();
        runtime = null;

        assertThat(start().workspace().jobs()).extracting(JobStatus::id).containsExactly(id);
    }

    @Test
    void onlyConfiguredSourcesAreReachable() throws Exception {
        Workspace workspace = start().workspace();

        assertThatThrownBy(() -> workspace.spaces("fremd")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> workspace.startSync("fremd", "DEV")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSourceWithoutTokenWaitsForTheSessionSecret() throws Exception {
        AppSettings settings = settings(Map.of("CLIENT_PASSWORD", TestCertificates.CLIENT_PASSWORD));
        runtime = AppRuntime.start(settings, new AppRuntime.SessionSecrets(), "test");
        assertThat(runtime.waitingForSecret()).containsExactly("wiki");
        assertThat(runtime.workspace().sources()).extracting(HarvestSource::id).containsExactly("handbuch");
        runtime.close();

        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        secrets.put("wiki", TOKEN.toCharArray());
        assertThat(secrets.toString()).doesNotContain(TOKEN);
        runtime = AppRuntime.start(settings, secrets, "test");
        assertThat(runtime.waitingForSecret()).isEmpty();
        assertThat(runtime.workspace().spaces("wiki")).extracting(Partition::key).containsExactly("DEV");
    }

    private AppRuntime start() throws Exception {
        runtime = AppRuntime.start(settings(Map.of("WIKI_TOKEN", TOKEN,
                "CLIENT_PASSWORD", TestCertificates.CLIENT_PASSWORD)), new AppRuntime.SessionSecrets(), "test");
        return runtime;
    }

    private AppSettings settings(Map<String, String> env) throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nNeustart mit systemctl.\n");
        Files.writeString(files.resolve("notizen.txt"), "Notizen\n");
        Path config = home.resolve("space-harvester-app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: %s
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                      client-certificate:
                        enabled: true
                        type: pkcs12
                        path: %s
                        alias: %s
                        password: ${CLIENT_PASSWORD}
                      trust-bundle: test
                      options:
                        connectTimeoutMillis: 2000
                    - id: handbuch
                      type: files
                      options:
                        directory: %s
                spring:
                  ssl:
                    bundle:
                      jks:
                        test:
                          truststore:
                            location: file:%s
                            password: %s
                """.formatted(home.resolve("data"), server.baseUrl(), certificates.client, TestCertificates.CLIENT_ALIAS,
                files, certificates.harvesterTrust, TestCertificates.STORE_PASSWORD));
        AppSettings settings = new AppSettingsLoader(new Placeholders(env::get), home).load(config);
        assertThat(settings.problems()).isEmpty();
        return settings;
    }

    private static JobStatus await(Workspace workspace, JobStatus started) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JobStatus status = started;
        while (!status.state().terminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            status = workspace.job(started.id());
        }
        return status;
    }

    private static void assertNoFileContains(Path directory, String secret) throws Exception {
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertThat(content).as(file.toString()).doesNotContain(new String(needle, StandardCharsets.ISO_8859_1));
            }
        }
    }
}
