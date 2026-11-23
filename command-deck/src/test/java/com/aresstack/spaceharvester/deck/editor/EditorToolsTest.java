package com.aresstack.spaceharvester.deck.editor;

import com.aresstack.spaceharvester.deck.editor.local.LocalVersionHistory;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Zeilenvergleich, Suche, lokaler Verlauf und „Speichern unter“ des gemeinsamen Editors. */
class EditorToolsTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path root;

    @Test
    void diffFindsAddedDeletedAndChangedLines() {
        List<LineDiff.Change> changes = LineDiff.of("a\nb\nc\nd", "a\nB\nc\nd\ne");

        assertThat(changes).containsExactly(
                new LineDiff.Change(LineDiff.Kind.CHANGED, 1, 1),
                new LineDiff.Change(LineDiff.Kind.ADDED, -1, 4));
        assertThat(LineDiff.of("x\ny", "y")).containsExactly(new LineDiff.Change(LineDiff.Kind.DELETED, 0, -1));
        assertThat(LineDiff.of("gleich", "gleich")).isEmpty();
    }

    @Test
    void searchIsCaseInsensitiveRegexAndReportsLines() {
        String text = "//SCHRITT1 EXEC PGM=A\n//dd1 DD DSN=X\n//SCHRITT2 exec PGM=B";

        assertThat(TextSearch.matches(text, "exec")).extracting(TextSearch.Match::start).containsExactly(11, 48);
        assertThat(TextSearch.matchingLines(text, "^//schritt")).containsExactly(0, 2);
        assertThat(TextSearch.problem("(")).isPresent();
        assertThat(TextSearch.matches(text, "(")).isEmpty();
        assertThat(TextSearch.matches(text, "")).isEmpty();
    }

    @Test
    void historyKeepsOpenedAndBeforeSaveVersions() throws IOException {
        Path file = Files.writeString(root.resolve("job.jcl"), "//JOB1 JOB\n");
        LocalVersionHistory history = new LocalVersionHistory(root.resolve("verlauf"));
        EditorSessions sessions = new EditorSessions(NOW, history);
        EditorSession session = sessions.open(new LocalDocumentStore(), file.toString(), "job.jcl");

        session.edit("//JOB2 JOB\n");
        session.save();

        List<VersionHistory.Version> versions = session.versions();
        assertThat(versions).extracting(VersionHistory.Version::label).containsExactly("vor Speichern", "geöffnet");
        assertThat(session.version(versions.get(1).id())).contains("//JOB1 JOB\n");
        assertThat(session.version("../job.jcl")).isEmpty();
        // Unveränderter Text legt keine neue Version an.
        session.load();
        assertThat(session.versions()).hasSize(2);
    }

    @Test
    void historyDropsOldAndSurplusVersions() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        LocalVersionHistory history = new LocalVersionHistory(root, 2, Duration.ofDays(10), clock);
        history.record("s", "a", "eins", "geöffnet");
        clock.now = start.plusSeconds(60);
        history.record("s", "a", "zwei", "vor Speichern");
        clock.now = start.plusSeconds(120);
        history.record("s", "a", "drei", "vor Speichern");

        assertThat(history.versions("s", "a")).hasSize(2);
        clock.now = start.plus(Duration.ofDays(30));
        history.record("s", "a", "vier", "geöffnet");
        assertThat(history.versions("s", "a")).hasSize(1);
        assertThat(history.versions("s", "b")).isEmpty();
    }

    @Test
    void saveAsWritesTheNewFileAndFollowsIt() throws IOException {
        Path file = Files.writeString(root.resolve("alt.txt"), "Zeile\r\n");
        EditorSession session = new EditorSessions(NOW).open(new LocalDocumentStore(), file.toString(), "alt.txt");
        session.edit("Zeile\nneu\n");

        Path target = root.resolve("neu.txt");
        session.saveAs(target.toString());

        assertThat(Files.readString(target)).isEqualTo("Zeile\r\nneu\r\n");
        assertThat(Files.readString(file)).isEqualTo("Zeile\r\n");
        assertThat(session.location()).isEqualTo(target.toString());
        assertThat(session.name()).isEqualTo("neu.txt");
        assertThat(session.dirty()).isFalse();
        assertThat(session.note()).contains("Gespeichert unter neu.txt");
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
