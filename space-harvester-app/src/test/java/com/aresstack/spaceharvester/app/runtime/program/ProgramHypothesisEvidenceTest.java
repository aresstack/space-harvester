package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Erfundenes Programm DEMO mit Copybook CUSTOMER; Quellstellenbelege aus den gespeicherten Analysen (ADR 0041). */
class ProgramHypothesisEvidenceTest {
    private static final Map<String, List<String>> LIBRARIES = Map.of("ftp", List.of("APP.CPY"));

    @Test
    void hypothesesGetProgramAndCopybookSitesProbesAndTheParserIdentity() throws Exception {
        var program = snapshot("APP.COBOL(DEMO)", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. DEMO.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY CUSTOMER.
                           COPY MISSING.
                       PROCEDURE DIVISION.
                           MOVE 'A' TO CUSTOMER-STATUS.
                           GOBACK.
                """);
        var copy = snapshot("APP.CPY(CUSTOMER)", "       01 CUSTOMER-STATUS PIC X.\n");
        Map<PageKey, PageSnapshot> pages = Map.of(program.key(), program, copy.key(), copy);
        var output = new CobolProgramEnrichment(LIBRARIES, key -> Optional.ofNullable(pages.get(key)))
                .process(program, null);
        var parser = new Enrichment(program.key(), CobolProgramEnrichment.ID, CobolProgramEnrichment.VERSION,
                "input", program.revision(), Enrichment.Status.OK, output.payloadJson(), List.of(), null,
                Instant.now());
        var inProgram = new CobolAnalysis.Span(new CobolAnalysis.Position("APP.COBOL(DEMO)", 8, 12),
                new CobolAnalysis.Position("APP.COBOL(DEMO)", 8, 40));
        var inCopybook = new CobolAnalysis.Span(new CobolAnalysis.Position("APP.CPY(CUSTOMER)", 1, 8),
                new CobolAnalysis.Position("APP.CPY(CUSTOMER)", 1, 30));
        var unknown = new CobolAnalysis.Span(new CobolAnalysis.Position("OTHER", 3, 1),
                new CobolAnalysis.Position("OTHER", 3, 9));
        var result = new CobolBusinessAnalysis.Result("m", 1, 3, false, List.of(
                new CobolBusinessAnalysis.Hypothesis("Der Kunde erhält den Status aktiv.", "MOVE",
                        List.of(new CobolBusinessAnalysis.Evidence("F1", "TRANSFER", "MOVE 'A' TO CUSTOMER-STATUS",
                                inProgram), new CobolBusinessAnalysis.Evidence("F2", "SYMBOL", "CUSTOMER-STATUS",
                                inCopybook))),
                new CobolBusinessAnalysis.Hypothesis("Etwas Unbelegtes.", "x",
                        List.of(new CobolBusinessAnalysis.Evidence("F3", "CALL", "X", unknown)))));
        var business = CobolBusinessEnrichment.stored(program, parser, result, Instant.now());

        var mapped = ProgramHypothesisEvidence.of(program, parser, business, key -> Optional.ofNullable(pages.get(key)));

        assertThat(mapped.unmapped()).isEqualTo(1);
        assertThat(mapped.hypotheses()).singleElement().satisfies(hypothesis -> {
            SourceLocationEvidence source = hypothesis.source();
            assertThat(source.program()).isEqualTo(DocumentKey.page(program.key()));
            assertThat(source.revision()).isEqualTo(program.payload().rawSha256());
            assertThat(source.analysisVersion()).isEqualTo(ProgramAnalysisAccess.identity(parser));
            assertThat(source.sites()).extracting(SourceLocationEvidence.CodeSite::document)
                    .containsExactly(DocumentKey.page(program.key()), DocumentKey.page(copy.key()));
            assertThat(source.sites().get(1).revision()).isEqualTo(copy.payload().rawSha256());
            assertThat(source.facts()).extracting(SourceLocationEvidence.ParserFact::kind)
                    .containsExactly("TRANSFER", "SYMBOL");
            assertThat(source.dependencies()).anySatisfy(dependency -> assertThat(dependency.negativeProbe()).isTrue());
            assertThat(source.dependencies()).anySatisfy(dependency ->
                    assertThat(dependency.document()).contains(DocumentKey.page(copy.key())));
        });
    }

    private static PageSnapshot snapshot(String path, String code) throws Exception {
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8)));
        var metadata = new PageMetadata(id, resource, path, "ftp", null, revision,
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        return PageSnapshot.of("ftp", metadata, new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT),
                Instant.now());
    }
}
