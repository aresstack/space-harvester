package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SourceSnapshots;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Native Snapshots (#8) über die konfigurierte Confluence-Quelle gegen eine Fake-Confluence über echtes HTTP: Payload
 * Byte für Byte, Kopfdaten und Hashes aus derselben Antwort wie in der Metadatenliste, Fehler nie als leere Seite.
 */
@SpringBootTest
class NativeSnapshotSourceTest {

    /** Leerraum, Schlüsselreihenfolge und ein Unicode-Escape, die ein erneutes Schreiben des JSON ändern würde. */
    private static final String RAW = "{ \"type\" : \"page\",\n  \"id\": \"102\",\n"
            + "  \"title\": \"Pr\\u00fcfung\",\n"
            + "  \"space\": {\"key\": \"DEV\", \"name\": \"Entwicklung\"},\n"
            + "  \"version\": {\"number\": 7, \"when\": \"2026-10-15T12:30:00.000+02:00\"},\n"
            + "  \"ancestors\": [{\"id\": \"100\", \"type\": \"page\", \"title\": \"Start\"}],\n"
            + "  \"body\": {\"storage\": {\"value\": \"<p>Inhalt</p><ac:structured-macro ac:name=\\\"info\\\"/>\","
            + " \"representation\": \"storage\"}},\n"
            + "  \"metadata\": {\"labels\": {\"results\": [{\"name\": \"b\"}, {\"name\": \"a\"}]}},\n"
            + "  \"history\": {\"createdDate\": \"2026-09-01T09:00:00.000+02:00\","
            + " \"createdBy\": {\"username\": \"autorin\"}}\n}\n";

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.page("102", "Prüfung", "DEV", "<p>Inhalt</p>").labels("b", "a").rawJson(RAW);
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.connectTimeoutMillis", () -> "2000");
        registry.add("space-harvester.sources[1].id", () -> "klein");
        registry.add("space-harvester.sources[1].type", () -> "confluence");
        registry.add("space-harvester.sources[1].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[1].options.maxResponseBytes", () -> "200");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private SourceSnapshots snapshots;

    @Autowired
    private SourceQueries queries;

    @Autowired
    private PageContentFetcher fetcher;

    @Test
    void theSynchronizationGetsSnapshotsThroughThePageContentFetcher() {
        assertThat(fetcher).isSameAs(snapshots);
        assertThat(fetcher.fetch("wiki", "102").payload().bytes()).isEqualTo(RAW.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void payloadIsTheResponseByteForByteWithItsHash() throws Exception {
        PageSnapshot snapshot = snapshots.fetch("wiki", "102");

        byte[] expected = RAW.getBytes(StandardCharsets.UTF_8);
        assertThat(snapshot.payload().bytes()).isEqualTo(expected);
        assertThat(snapshot.payload().rawSha256()).isEqualTo("sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        assertThat(snapshot.payload().mediaType()).isEqualTo("application/json");
        assertThat(snapshot.payload().nativeFormat()).isEqualTo("confluence-rest-content+json;expand=ancestors,"
                + "body.storage,history,history.lastUpdated,metadata.labels,space,version");
        assertThat(snapshot.toString()).doesNotContain("Inhalt");
    }

    @Test
    void headerIsReadFromThePayload() {
        PageSnapshot snapshot = snapshots.fetch("wiki", "102");

        assertThat(snapshot.key().sourceId()).isEqualTo("wiki");
        assertThat(snapshot.key().pageId()).isEqualTo("102");
        assertThat(snapshot.metadata().title()).isEqualTo("Prüfung");
        assertThat(snapshot.revision()).isEqualTo("7");
        assertThat(snapshot.spaceKey()).isEqualTo("DEV");
        assertThat(snapshot.parentPageId()).isEqualTo("100");
        assertThat(snapshot.metadata().attributes()).containsEntry("confluence.labels", "a,b")
                .containsEntry("confluence.createdBy", "autorin");
    }

    @Test
    void headerAndHashesMatchTheMetadataListing() {
        PageSnapshot snapshot = snapshots.fetch("wiki", "100");

        PageMetadataListing.Entry listed = queries.pageMetadata("wiki", "DEV", null, 100).entries().stream()
                .filter(entry -> entry.page().pageId().equals("100")).findFirst().orElseThrow();
        assertThat(snapshot.metadata()).isEqualTo(listed.page());
        assertThat(snapshot.metadataHash()).isEqualTo(listed.metadataHash());
        assertThat(snapshot.attributesHash()).isEqualTo(listed.attributesHash());
        assertThat(new String(snapshot.payload().bytes(), StandardCharsets.UTF_8)).contains("<p>Start</p>");
    }

    @Test
    void responseOverTheLimitIsAnInvalidResponseNeverATruncatedSnapshot() {
        assertThatThrownBy(() -> snapshots.fetch("klein", "102"))
                .isInstanceOfSatisfying(SourceAccessException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.INVALID_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain("Inhalt");
                });
    }

    @Test
    void unknownPageAndForeignIdAreErrorsNotEmptySnapshots() {
        assertThatThrownBy(() -> snapshots.fetch("wiki", "999")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> snapshots.fetch("wiki", "att123"))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> snapshots.fetch("nope", "102")).isInstanceOf(UnknownSourceException.class);
    }
}
