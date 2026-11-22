package com.aresstack.spaceharvester.deck.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der gemeinsame Editor gegen echte Dateien: laden, ungespeichert, speichern, Konflikt, überschreiben. */
class EditorSessionTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path root;

    private final LocalDocumentStore store = new LocalDocumentStore();
    private final EditorSessions sessions = new EditorSessions(NOW);

    @Test
    void loadsEditsAndSaves() throws IOException {
        Path file = Files.writeString(root.resolve("notiz.txt"), "Grüße an Bord\n");
        EditorSession session = sessions.open(store, file.toString(), "notiz.txt");

        assertThat(session.state()).isEqualTo(EditorSession.State.READY);
        assertThat(session.text()).contains("Grüße an Bord\n");
        assertThat(session.document()).map(EditorDocument::charset).contains("UTF-8");
        assertThat(session.dirty()).isFalse();

        session.edit("Grüße an Deck\n");
        assertThat(session.dirty()).isTrue();
        session.save();

        assertThat(Files.readString(file)).isEqualTo("Grüße an Deck\n");
        assertThat(session.dirty()).isFalse();
        assertThat(session.note()).contains("Gespeichert");
    }

    @Test
    void refusesToOverwriteForeignChangesUntilAskedTo() throws IOException {
        Path file = Files.writeString(root.resolve("job.jcl"), "//JOB1 JOB\n");
        EditorSession session = sessions.open(store, file.toString(), "job.jcl");
        Files.writeString(file, "//JOB2 JOB\n");

        session.edit("//JOB3 JOB\n");
        session.save();

        assertThat(session.state()).isEqualTo(EditorSession.State.CONFLICT);
        assertThat(session.problem()).contains("Die Datei wurde seit dem Öffnen geändert.");
        assertThat(Files.readString(file)).isEqualTo("//JOB2 JOB\n");
        assertThat(session.dirty()).isTrue();

        session.overwrite();
        assertThat(session.state()).isEqualTo(EditorSession.State.READY);
        assertThat(Files.readString(file)).isEqualTo("//JOB3 JOB\n");

        // Danach gilt der neue Stand als Grundlage: erneutes Speichern ohne fremde Änderung klappt.
        session.edit("//JOB4 JOB\n");
        session.save();
        assertThat(Files.readString(file)).isEqualTo("//JOB4 JOB\n");
    }

    @Test
    void keepsWindowsCharsetAndBomWhenSaving() throws IOException {
        Charset cp1252 = Charset.forName("windows-1252");
        Path legacy = Files.write(root.resolve("alt.txt"), "Größe".getBytes(cp1252));
        EditorSession old = sessions.open(store, legacy.toString(), "alt.txt");
        assertThat(old.text()).contains("Größe");
        old.edit("Größer");
        old.save();
        assertThat(Files.readAllBytes(legacy)).isEqualTo("Größer".getBytes(cp1252));

        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        Path withBom = Files.write(root.resolve("bom.txt"), bom);
        EditorSession marked = sessions.open(store, withBom.toString(), "bom.txt");
        assertThat(marked.text()).contains("a");
        marked.edit("b");
        marked.save();
        assertThat(Files.readAllBytes(withBom)).containsExactly(0xEF, 0xBB, 0xBF, 'b');
    }

    @Test
    void showsLinesWithNewlineAndKeepsWindowsLineEndingsWhenSaving() throws IOException {
        Path file = Files.writeString(root.resolve("job.jcl"), "//JOB1 JOB\r\n//STEP EXEC\r\n");
        EditorSession session = sessions.open(store, file.toString(), "job.jcl");

        assertThat(session.text()).contains("//JOB1 JOB\n//STEP EXEC\n");
        session.edit("//JOB1 JOB\n//STEP1 EXEC\n");
        session.save();

        assertThat(Files.readString(file)).isEqualTo("//JOB1 JOB\r\n//STEP1 EXEC\r\n");
    }

    @Test
    void saysWhyACharacterCannotBeSavedAndKeepsTheText() throws IOException {
        Path legacy = Files.write(root.resolve("alt.txt"), "Größe".getBytes(Charset.forName("windows-1252")));
        EditorSession session = sessions.open(store, legacy.toString(), "alt.txt");

        session.edit("Größe ✓");
        session.save();

        assertThat(session.state()).isEqualTo(EditorSession.State.READY);
        assertThat(session.note()).hasValueSatisfying(note -> assertThat(note).startsWith("Nicht gespeichert: "));
        assertThat(session.dirty()).isTrue();
        assertThat(Files.readAllBytes(legacy)).isEqualTo("Größe".getBytes(Charset.forName("windows-1252")));
    }

    @Test
    void refusesBinaryFiles() throws IOException {
        Path binary = Files.write(root.resolve("bild.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        EditorSession session = sessions.open(store, binary.toString(), "bild.png");

        assertThat(session.state()).isEqualTo(EditorSession.State.FAILED);
        assertThat(session.problem()).contains("Das ist keine Textdatei.");
    }

    @Test
    void opensEachDocumentOnceAndNotifiesObservers() throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        sessions.onOpened(s -> opened.incrementAndGet());
        sessions.onClosed(s -> closed.incrementAndGet());

        EditorSession first = sessions.open(store, file.toString(), "a.txt");
        EditorSession second = sessions.open(store, file.toString(), "a.txt");

        assertThat(second).isSameAs(first);
        assertThat(opened).hasValue(1);
        sessions.close(first);
        assertThat(sessions.all()).isEmpty();
        assertThat(closed).hasValue(1);
    }

    @Test
    void savesThroughSymbolicLinksAndKeepsPermissions() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                root.getFileSystem().supportedFileAttributeViews().contains("posix"), "nur POSIX");
        Path script = Files.writeString(root.resolve("lauf.sh"), "echo a\n");
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Path link = Files.createSymbolicLink(root.resolve("verknuepfung.sh"), script);
        EditorSession session = sessions.open(store, link.toString(), "verknuepfung.sh");

        session.edit("echo b\n");
        session.save();

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(script)).isEqualTo("echo b\n");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
                .isEqualTo("rwxr-xr-x");
    }

    @Test
    void countsEveryLoadSoTheEditorSeesReloadedContent() throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "eins");
        EditorSession session = sessions.open(store, file.toString(), "a.txt");
        long first = session.revision();
        Files.writeString(file, "zwei");

        session.load();

        assertThat(session.revision()).isEqualTo(first + 1);
        assertThat(session.text()).contains("zwei");
    }

    @Test
    void reportsADeletedFileAsConflict() throws IOException {
        Path file = Files.writeString(root.resolve("weg.txt"), "a");
        EditorSession session = sessions.open(store, file.toString(), "weg.txt");
        Files.delete(file);

        session.edit("b");
        session.save();

        assertThat(session.state()).isEqualTo(EditorSession.State.CONFLICT);
        assertThat(session.problem()).contains("Die Datei wurde inzwischen gelöscht oder umbenannt.");
        assertThat(Files.exists(file)).isFalse();
    }
}
