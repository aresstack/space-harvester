package com.aresstack.spaceharvester.application.knowledge.export;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Teilsatz-Beteiligte zeigen im JSON-LD auf die Aussage ihres Teilsatzes, bei Parser und KI-Analysator. */
class KnowledgeGraphJsonLdClauseTest {

    @Test
    void aParserClauseMentionPointsToTheStatementOfItsVerbToken() {
        ParsedStatements result = result("quelle/seite@0123456789ab#s1.v4");

        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("quelle/seite@0123456789ab#s1.t4")))
                .isEqualTo("quelle/seite@0123456789ab#s1.v4");
    }

    @Test
    void aModelClauseMentionPointsToTheStatementWithTheSameNumber() {
        ParsedStatements result = result("quelle/seite@0123456789ab#s1.v0");

        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("quelle/seite@0123456789ab#s1.kv0")))
                .isEqualTo("quelle/seite@0123456789ab#s1.v0");
    }

    @Test
    void dotsInTheDocumentPartDoNotDisturbTheMatch() {
        ParsedStatements result = result("anhang.v2.txt/seite@0123456789ab#s2.v1");

        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("anhang.v2.txt/seite@0123456789ab#s2.kv1")))
                .isEqualTo("anhang.v2.txt/seite@0123456789ab#s2.v1");
        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("anhang.v2.txt/seite@0123456789ab#s2.t3")))
                .isNull();
    }

    @Test
    void unknownLocalIdsAndMissingStatementsGiveNoEdge() {
        ParsedStatements result = result("quelle/seite@0123456789ab#s1.v0");

        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("quelle/seite@0123456789ab#s1.c3"))).isNull();
        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("quelle/seite@0123456789ab#s1.kv7"))).isNull();
        assertThat(KnowledgeGraphJsonLd.clauseStatement(result, clause("ohne-raute.kv0"))).isNull();
    }

    private static ParsedStatements.Mention clause(String id) {
        return new ParsedStatements.Mention(id, 1, 0, 10, "dass er prüft", "prüft", "prüfen",
                ParsedStatements.MentionKind.CLAUSE, false, Set.of());
    }

    private static ParsedStatements result(String statementId) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement(statementId, 1, 0, 5, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of());
        return new ParsedStatements(List.of(new ParsedStatements.Sentence(1, 0, 20)), List.of(), List.of(statement),
                List.of(), List.of(), null);
    }
}
