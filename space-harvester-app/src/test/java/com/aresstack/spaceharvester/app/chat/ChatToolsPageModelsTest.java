package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Der Chat nutzt die Auswertungsmodelle (Angelo 27.11.2026): Aussagen, Vereinfachen, Prüfen, Podcast. */
class ChatToolsPageModelsTest {

    private static final String TEXT = "Der Sachbearbeiter muss den Antrag prüfen. Das Amt kann ablehnen.";

    @Test
    void theStatementsComeAsSentencesWithModalityAndVerdict() {
        ParsedStatements.Statement first = new ParsedStatements.Statement("wiki/1#s0.v1", 0, 0, 42, "prüfen",
                "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of())
                .withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT, "gemma|1", false));
        ParsedStatements.Statement second = new ParsedStatements.Statement("wiki/1#s1.v1", 1, 43, TEXT.length(),
                "ablehnen", "ablehnen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of());
        PageStatements result = new PageStatements(PageStatements.State.CURRENT, null, new PageStatementAnalysis(
                new PageKey("wiki", "1"), "DEV", "Antrag", "3", "sha256:00", "model", "1",
                Instant.parse("2026-11-27T10:00:00Z"), new ParsedStatements(List.of(), List.of(),
                List.of(first, second), List.of(), List.of(), null)));

        String text = ChatTools.statementsText(new ChatTools.Page("Antrag", null, TEXT, "DEV", null), result, true);

        assertThat(text).startsWith("Aussagen der Seite „Antrag“: 2, geprüft\n")
                .contains("- Der Sachbearbeiter muss den Antrag prüfen. ")
                .contains("(Prüfung: stimmt)")
                .contains("- Das Amt kann ablehnen.");
    }

    @Test
    void withoutAnalysisTheReasonIsNamed() {
        assertThat(ChatTools.statementsText(new ChatTools.Page("A", null, TEXT, "DEV", null),
                PageStatements.missing(PageStatements.NO_TEXT), false)).isEqualTo("Keine Aussagen: no-text");
    }

    @Test
    void theToolsAreDefinedWithTheirArguments() {
        List<AiChat.Tool> tools = ChatTools.pageModelDefinitions();

        assertThat(tools).extracting(AiChat.Tool::name).containsExactly(ChatTools.STATEMENTS, ChatTools.PODCAST);
        assertThat(tools.get(0).parametersSchemaJson()).contains("\"kennung\"").contains("\"pruefen\"");
        assertThat(ChatTools.describe(new AiChat.ToolCall("c", ChatTools.PODCAST, "{}")))
                .isEqualTo("Startet den Erklär-Podcast");
    }
}
