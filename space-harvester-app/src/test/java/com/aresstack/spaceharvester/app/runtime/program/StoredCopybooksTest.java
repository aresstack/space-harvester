package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class StoredCopybooksTest {
    @TempDir Path home;
    private static final String CODE = "       IDENTIFICATION DIVISION.\n"
            + "       PROGRAM-ID. DEMO.\n"
            + "       DATA DIVISION.\n"
            + "       WORKING-STORAGE SECTION.\n"
            + "           COPY CUSTOMER.\n"
            + "       PROCEDURE DIVISION.\n"
            + "           GOBACK.\n";

    @Test
    void resolvesOrderedLibrariesAndInvalidatesOnlyChangedDependencyProbes() throws Exception {
        var program = snapshot("ftp", "APP.COBOL(DEMO)", CODE);
        var lower = snapshot("ftp", "APP.CPY2(CUSTOMER)", "       01 LOWER-FIELD PIC X(10).\n");
        var higher = snapshot("ftp", "APP.CPY1(CUSTOMER)", "       01 HIGHER-FIELD PIC X(10).\n");
        var unrelated = snapshot("ftp", "APP.CPY1(OTHER)", "       01 OTHER-FIELD PIC X(10).\n");
        var foreign = snapshot("other", "APP.CPY1(CUSTOMER)", "       01 FOREIGN-FIELD PIC X(10).\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var processor = new CobolProgramEnrichment(Map.of("ftp", List.of("APP.CPY1", "APP.CPY2")), cache::find);
            var enrichments = new Enrichments(new KnowledgeDocumentTextProjector(), List.of(processor), store, Clock.systemUTC());
            cache.save(program);
            cache.save(foreign);
            cache.save(lower);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var first = artifact(enrichments, program);
            assertThat(first.analysis().isPartial()).isTrue();
            assertThat(first.analysis().elements()).anyMatch(element -> element.name().equals("LOWER-FIELD"));
            assertThat(first.probes()).hasSize(2).anyMatch(probe -> probe.rawSha256() == null);
            assertThat(enrichments.refresh(program).current()).isEqualTo(1);
            cache.save(unrelated);
            assertThat(enrichments.refresh(program).current()).isEqualTo(1);
            cache.save(higher);
            assertThat(enrichments.view(program).get(0).freshness()).isEqualTo(Enrichment.Freshness.STALE);
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            var second = artifact(enrichments, program);
            assertThat(second.analysis().elements()).anySatisfy(element -> {
                assertThat(element.name()).isEqualTo("HIGHER-FIELD");
                assertThat(element.span().start().source()).isEqualTo("APP.CPY1(CUSTOMER)");
                assertThat(element.span().start().line()).isEqualTo(1);
            });
            assertThat(second.analysis().elements()).noneMatch(element -> element.name().equals("LOWER-FIELD"));
            assertThat(second.probes()).hasSize(1);
            cache.save(snapshot("ftp", "APP.CPY2(CUSTOMER)", "       01 CHANGED PIC 9.\n"));
            assertThat(enrichments.view(program).get(0).freshness()).isEqualTo(Enrichment.Freshness.CURRENT);
            cache.save(snapshot("ftp", "APP.CPY1(CUSTOMER)", "       01 REVISED PIC 9.\n"));
            assertThat(enrichments.refresh(program).computed()).isEqualTo(1);
            assertThat(artifact(enrichments, program).analysis().elements())
                    .anyMatch(element -> element.name().equals("REVISED"));
            cache.remove(higher.key());
            assertThat(enrichments.view(program).get(0).freshness()).isEqualTo(Enrichment.Freshness.STALE);
        }
    }

    @Test
    void qualifiedCopybookNeverSearchesUnconfiguredOrAmbiguousLibraries() throws Exception {
        var program = snapshot("ftp", "APP.COBOL(DEMO)", CODE);
        var member = snapshot("ftp", "A.CPY(CUSTOMER)", "       01 FIELD-A PIC X.\n");
        var source = StoredCobolSource.read(program).orElseThrow();
        var resolver = new StoredCopybooks(program, List.of("A.CPY", "B.CPY"),
                key -> key.equals(member.key()) ? java.util.Optional.of(member) : java.util.Optional.empty());
        assertThat(resolver.resolve(source, "CUSTOMER", "CPY")).isEmpty();
        assertThat(resolver.resolve(source, "CUSTOMER", "X.CPY")).isEmpty();
        assertThat(resolver.probes()).isEmpty();
        assertThat(resolver.resolve(source, "CUSTOMER", "A.CPY")).hasValueSatisfying(copy ->
                assertThat(copy.name()).isEqualTo("A.CPY(CUSTOMER)"));
    }

    private static CobolProgramEnrichment.Artifact artifact(Enrichments enrichments, PageSnapshot snapshot) {
        return CobolProgramEnrichment.read(enrichments.view(snapshot).get(0).enrichment().payloadJson());
    }

    private static PageSnapshot snapshot(String source, String path, String code) throws Exception {
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8)));
        PageMetadata metadata = new PageMetadata(id, resource, path, source, null, revision,
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        String payload = "# " + path + "\n\n```\n" + code + "\n```";
        return PageSnapshot.of(source, metadata, new NativePayload(payload.getBytes(StandardCharsets.UTF_8),
                "text/markdown", NativePayload.DOCUMENT_TEXT), Instant.now());
    }
}
