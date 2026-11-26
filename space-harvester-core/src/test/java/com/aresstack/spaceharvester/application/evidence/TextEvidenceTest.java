package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextEvidenceTest {

    private static final PageKey PAGE = new PageKey("wiki", "4711");

    @Test
    void takesRevisionFromStatementIdAndPositionFromStatement() {
        var page = new KnowledgeGraph.PageRef(PAGE, "FOE", "Förderung", null, null);
        var evidence = TextEvidence.of(page, statement("wiki/4711@abc123#s3.v2", 3, 40, 95));

        assertThat(evidence.document()).isEqualTo(DocumentKey.page(PAGE));
        assertThat(evidence.revision()).isEqualTo("abc123");
        assertThat(evidence.sentence()).isEqualTo(3);
        assertThat(evidence.start()).isEqualTo(40);
        assertThat(evidence.end()).isEqualTo(95);
        assertThat(evidence.kind()).isEqualTo(Evidence.Kind.TEXT);
    }

    @Test
    void leavesIstOrSollToTheStatement() {
        var evidence = new TextEvidence(DocumentKey.page(PAGE), "abc", 0, 0, 10);

        assertThat(evidence.bearing()).isEqualTo(Optional.empty());
    }

    @Test
    void attachmentKeepsItsDocument() {
        var attachment = DocumentKey.attachment(PAGE, "att-9");
        var page = new KnowledgeGraph.PageRef(PAGE, "FOE", "Förderung", null, null, attachment);

        assertThat(TextEvidence.of(page, statement("wiki/att-9@ff00#s0.v1", 0, 0, 12)).document())
                .isEqualTo(attachment);
    }

    @Test
    void refusesIdWithoutRevision() {
        var page = new KnowledgeGraph.PageRef(PAGE, "FOE", "Förderung", null, null);

        assertThatThrownBy(() -> TextEvidence.of(page, statement("wiki/4711#s0.v1", 0, 0, 5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ohne Fassung");
    }

    @Test
    void evidenceOfAStatementIsTheSourceLocationForProgramStatementsAndTheSentenceOtherwise() {
        var page = new KnowledgeGraph.PageRef(PAGE, null, "ANTRAG01", null, null);
        var program = DocumentKey.page(PAGE);
        var source = new SourceLocationEvidence(program, "p1",
                List.of(new SourceLocationEvidence.CodeSite(program, "p1", 10, 12)), List.of(), List.of(), "v1");
        var text = statement("wiki/4711@abc#s0.v1", 0, 0, 20);
        var hypothesis = text.withDerivation(ParsedStatements.Derivation.programm("Der Antrag wird geprüft.", source));

        assertThat(Evidence.of(page, text)).isInstanceOf(TextEvidence.class);
        assertThat(Evidence.of(page, hypothesis)).isSameAs(source);
        assertThat(Evidence.of(page, hypothesis).bearing()).contains(Bearing.IST);
    }

    @Test
    void programDerivationNeedsSentenceAndSourceAndOnlyItCarriesOne() {
        var program = DocumentKey.page(PAGE);
        var source = new SourceLocationEvidence(program, "p1",
                List.of(new SourceLocationEvidence.CodeSite(program, "p1", 10, 12)), List.of(), List.of(), "v1");

        assertThatThrownBy(() -> ParsedStatements.Derivation.programm(" ", source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParsedStatements.Derivation(ParsedStatements.Origin.PROGRAMM,
                ParsedStatements.StageFlag.NONE, "Satz", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParsedStatements.Derivation(ParsedStatements.Origin.VORSTUFE,
                ParsedStatements.StageFlag.FACT, "Satz", null, null, null, source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ParsedStatements.Derivation.programm("Satz", source).withSection(null).source()).isSameAs(source);
    }

    private static ParsedStatements.Statement statement(String id, int sentence, int start, int end) {
        return new ParsedStatements.Statement(id, sentence, start, end, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of());
    }
}
