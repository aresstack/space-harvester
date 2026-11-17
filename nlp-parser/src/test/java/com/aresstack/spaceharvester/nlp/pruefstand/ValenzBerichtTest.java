package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.VerbValence;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Valenz aus dem Bestand (ADR 0032) über die Originaltexte des Prüfstands: die Aussagen der Kette („heute“) gehen wie
 * gespeicherte Analysen in {@link VerbValence}; die Rahmen je Verb stehen in
 * {@code build/reports/pruefstand/valenz.md}. Nur Bericht, kein Pin: die Auswertung bewertet nichts.
 */
class ValenzBerichtTest {

    @Test
    void rahmenJeVerbAusDenTextsaetzen() throws IOException {
        StringBuilder bericht = new StringBuilder("# Valenz aus dem Prüfstand (heute)\n\n");
        for (Textsatz satz : Textsatz.alle()) {
            List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
            for (Text text : satz.texte()) {
                ObjectCentricAnalysis analysis = new ObjectCentricExtraction(ExtractionSettings.defaults())
                        .analyze(satz.analyse(satz.varianten().get(0), text));
                pages.add(page(satz.name(), text.name(), analysis));
            }
            VerbValence.Result result = VerbValence.of(pages, () -> false);
            assertTrue(result.statements() > 0);
            bericht.append("## ").append(satz.name()).append("\n\n").append(result.pages()).append(" Texte, ")
                    .append(result.statements()).append(" gezählte Aussagen; nicht gezählt: ")
                    .append(result.notPlausible()).append(" unter PLAUSIBLE, ").append(result.states())
                    .append(" Zustandsaussagen.\n\n| Verb | Aussagen | Rahmen (Aktiv/Passiv: sichere Rollen "
                            + "[heuristisch] × Anzahl) |\n|---|---|---|\n");
            for (VerbValence.Verb verb : result.verbs()) {
                bericht.append("| ").append(verb.lemma()).append(" | ").append(verb.count()).append(" | ")
                        .append(verb.frames().stream().map(ValenzBerichtTest::rahmen)
                                .collect(Collectors.joining("; "))).append(" |\n");
            }
            bericht.append('\n');
        }
        Path report = Path.of("build", "reports", "pruefstand", "valenz.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.toString(), StandardCharsets.UTF_8);
    }

    private static String rahmen(VerbValence.Frame frame) {
        String rollen = frame.roles().isEmpty() ? "–" : frame.roles().stream().map(ValenzBerichtTest::kurz)
                .collect(Collectors.joining("+"));
        String heuristisch = frame.heuristic().isEmpty() ? "" : " [" + frame.heuristic().stream()
                .map(ValenzBerichtTest::kurz).collect(Collectors.joining("+")) + "]";
        return (frame.voice() == ParsedStatements.Voice.PASSIVE ? "Passiv " : "Aktiv ") + rollen + heuristisch
                + " × " + frame.count();
    }

    private static String kurz(String role) {
        return switch (role.contains(":") ? role.substring(0, role.indexOf(':')) : role) {
            case "ACTOR" -> "H";
            case "AFFECTED_OBJECT" -> "B";
            case "RECIPIENT" -> "E";
            default -> "P" + (role.contains(":") ? "(" + role.substring(role.indexOf(':') + 1) + ")" : "");
        };
    }

    /** Die Aussagen einer Analyse wie gespeichert (nur was die Valenz liest). */
    private static KnowledgeGraph.AnalysedPage page(String satz, String text, ObjectCentricAnalysis analysis) {
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        for (Statement statement : analysis.statements()) {
            List<ParsedStatements.Argument> arguments = new ArrayList<>();
            for (Argument argument : statement.arguments()) {
                arguments.add(new ParsedStatements.Argument(ParsedStatements.Role.valueOf(argument.role().name()),
                        argument.mention(), argument.relation(), argument.marker(),
                        ParsedStatements.Priority.valueOf(argument.priority().name()),
                        ParsedStatements.Basis.valueOf(argument.basis().name()), argument.certain()));
            }
            String lemma = statement.predicate().lemma() == null ? statement.predicate().text()
                    : statement.predicate().lemma().toLowerCase(Locale.ROOT);
            statements.add(new ParsedStatements.Statement(statement.id(), statement.sentence(), 0, 1,
                    statement.predicate().text(), lemma, ParsedStatements.Voice.valueOf(statement.voice().name()),
                    ParsedStatements.Clause.valueOf(statement.clause().name()), true, arguments,
                    statement.findings().stream().map(Enum::name).collect(Collectors.toSet()), 0,
                    ParsedStatements.Quality.valueOf(StatementQuality.assess(analysis, statement).level().name()),
                    List.of(), ParsedStatements.Review.UNREVIEWED));
        }
        PageKey key = new PageKey(satz, text);
        PageStatementAnalysis stored = new PageStatementAnalysis(key, null, text, "1", "sha256:" + "0".repeat(64),
                "pruefstand", "1", Instant.EPOCH, new ParsedStatements(List.of(), List.of(), statements, List.of(),
                List.of(), null));
        return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, null, text,
                PageStatements.State.CURRENT, null), stored);
    }
}
