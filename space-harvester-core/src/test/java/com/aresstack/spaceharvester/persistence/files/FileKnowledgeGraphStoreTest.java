package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileKnowledgeGraphStoreTest {

    @TempDir
    Path directory;

    @Test
    void anAnalysisSurvivesARestartAndTheSyntaxDetailsAreReadOnDemand() {
        PageStatementAnalysis analysis = analysis("wiki", "1", "Beleg");
        new FileKnowledgeGraphStore(directory).replace(analysis);

        FileKnowledgeGraphStore restarted = new FileKnowledgeGraphStore(directory);

        assertThat(restarted.find(analysis.key()))
                .contains(analysis.withResult(analysis.result().withoutConllu()));
        assertThat(restarted.all()).hasSize(1);
        assertThat(restarted.conllu(analysis.key())).contains("1\tBeleg\t_\t_\t_\t_\t0\troot\t_\t_\n");
    }

    @Test
    void replacingIsIdempotentAndLeavesNoTemporaryFiles() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.replace(analysis("wiki", "1", "Antrag"));
        store.replace(analysis("wiki", "1", "Antrag"));
        store.replace(analysis("handbuch", "1", "Beleg"));

        assertThat(store.all()).extracting(PageStatementAnalysis::key)
                .containsExactlyInAnyOrder(new PageKey("wiki", "1"), new PageKey("handbuch", "1"));
        assertThat(new FileKnowledgeGraphStore(directory).find(new PageKey("wiki", "1")).orElseThrow().result()
                .mentions()).extracting(ParsedStatements.Mention::text).containsExactly("Sachbearbeiter", "Antrag");
        try (Stream<Path> files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .hasSize(2).allSatisfy(name -> assertThat(name).endsWith(".json").doesNotContain("wiki"));
        }
    }

    @Test
    void anUnreadableFileIsSkippedAndTheOthersStay() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        Files.writeString(directory.resolve(FileKnowledgeGraphStore.PAGES).resolve("kaputt.json"), "{\"schema\":",
                StandardCharsets.UTF_8);

        assertThat(new FileKnowledgeGraphStore(directory).all()).extracting(PageStatementAnalysis::key)
                .containsExactly(new PageKey("wiki", "1"));
    }

    @Test
    void aFileWithAnInvalidTimestampIsSkippedAndDoesNotBlockTheStore() throws IOException {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.replace(analysis("wiki", "2", "Quittung"));
        Path broken;
        try (var files = Files.list(directory.resolve(FileKnowledgeGraphStore.PAGES))) {
            broken = files.filter(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains("Quittung");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).findFirst().orElseThrow();
        }
        String json = Files.readString(broken, StandardCharsets.UTF_8);
        Files.writeString(broken, json.replaceFirst("\"analyzedAt\"\\s*:\\s*\"[^\"]*\"",
                "\"analyzedAt\":\"gestern\""), StandardCharsets.UTF_8);

        FileKnowledgeGraphStore reopened = new FileKnowledgeGraphStore(directory);
        assertThat(reopened.all()).extracting(PageStatementAnalysis::key).containsExactly(new PageKey("wiki", "1"));
        reopened.replace(analysis("wiki", "2", "Quittung"));
        assertThat(new FileKnowledgeGraphStore(directory).all()).hasSize(2);
    }

    @Test
    void removingDeletesTheFile() {
        FileKnowledgeGraphStore store = new FileKnowledgeGraphStore(directory);
        store.replace(analysis("wiki", "1", "Beleg"));
        store.remove(new PageKey("wiki", "1"));
        store.remove(new PageKey("wiki", "2"));

        assertThat(new FileKnowledgeGraphStore(directory).all()).isEmpty();
        assertThat(store.conllu(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void anEmptyDirectoryIsNotCreatedJustByReading() {
        Path missing = directory.resolve("knowledge-graph");

        assertThat(new FileKnowledgeGraphStore(missing).all()).isEmpty();
        assertThat(missing).doesNotExist();
    }

    private static PageStatementAnalysis analysis(String sourceId, String pageId, String noun) {
        String doc = sourceId + "/" + pageId + "@0123456789ab";
        ParsedStatements.Mention subject = new ParsedStatements.Mention(doc + "#s0.t1", 0, 0, 14, "Sachbearbeiter",
                "Sachbearbeiter", "Sachbearbeiter", ParsedStatements.MentionKind.NOUN, false, Set.of("Nom"));
        ParsedStatements.Mention object = new ParsedStatements.Mention(doc + "#s0.t3", 0, 27, 27 + noun.length(),
                noun, noun, null, ParsedStatements.MentionKind.NOUN, false, Set.of());
        ParsedStatements.Statement statement = new ParsedStatements.Statement(doc + "#s0.v2", 0, 15, 26,
                "übermittelt", "übermitteln", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, subject.id(), "nsubj", null,
                                ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.TIME, object.id(), "nmod", "am",
                                ParsedStatements.Priority.CONTEXT, ParsedStatements.Basis.LEXICON, false)),
                Set.of("TIME_FROM_NOMINAL"));
        ParsedStatements result = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, 40)),
                List.of(subject, object), List.of(statement),
                List.of(new ParsedStatements.Attachment(subject.id() + "→t3", subject.id(), object.id(), "nmod", null,
                        ParsedStatements.AttachmentKind.GENITIVE, ParsedStatements.Basis.CASE_HEURISTIC)),
                List.of("parser=maltparser-1.9.2"), "1\t" + noun + "\t_\t_\t_\t_\t0\troot\t_\t_\n");
        return new PageStatementAnalysis(new PageKey(sourceId, pageId), "DEV", "Seite " + pageId, "3",
                "sha256:0123456789abcdef", "maltparser-de", "1|maltparser-de-gsd-v1.0.0|237885c2",
                Instant.parse("2026-11-09T15:00:00Z"), result);
    }
}
