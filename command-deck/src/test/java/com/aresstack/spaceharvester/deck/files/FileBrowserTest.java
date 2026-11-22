package com.aresstack.spaceharvester.deck.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.files.local.LocalFileSource;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Grundbedienung des Dateibrowsers wie in MainframeMate, gegen echte Dateien in einem Testordner. */
class FileBrowserTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path root;

    private FileBrowser browser;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectory(root.resolve("Berichte"));
        Files.createDirectory(root.resolve("archiv"));
        write("notiz.txt", "Grüße an Bord", Instant.parse("2026-01-01T10:00:00Z"));
        write("Programm.jcl", "//JOB1 JOB", Instant.parse("2026-03-01T10:00:00Z"));
        write("gross.log", "x".repeat(5000), Instant.parse("2026-02-01T10:00:00Z"));
        browser = new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW);
    }

    private void write(String name, String text, Instant modified) throws IOException {
        Path file = Files.writeString(root.resolve(name), text);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    @Test
    void opensAFolderWithFoldersFirstSortedByName() {
        browser.open(root.toString());

        assertThat(browser.location()).contains(root.toString());
        assertThat(browser.loading()).isFalse();
        assertThat(browser.failure()).isEmpty();
        assertThat(names()).containsExactly("archiv", "Berichte", "gross.log", "notiz.txt", "Programm.jcl");
    }

    @Test
    void sortsLikeMainframeMate() {
        browser.open(root.toString());

        browser.sort(SortMode.SIZE_DESC);
        assertThat(names()).containsExactly("archiv", "Berichte", "gross.log", "notiz.txt", "Programm.jcl");
        browser.sort(SortMode.DATE_DESC);
        assertThat(names().subList(2, 5)).containsExactly("Programm.jcl", "gross.log", "notiz.txt");
        browser.sort(SortMode.EXTENSION);
        assertThat(names().subList(2, 5)).containsExactly("Programm.jcl", "gross.log", "notiz.txt");
        browser.sort(SortMode.NAME_DESC);
        assertThat(names()).containsExactly("Berichte", "archiv", "Programm.jcl", "notiz.txt", "gross.log");
    }

    @Test
    void filtersByRegexIgnoringCaseAndToleratesBrokenExpressions() {
        browser.open(root.toString());

        browser.filter("\\.JCL$");
        assertThat(names()).containsExactly("Programm.jcl");
        assertThat(browser.filterValid()).isTrue();

        browser.filter("([");
        assertThat(browser.filterValid()).isFalse();
        assertThat(names()).hasSize(5);

        browser.filter("");
        assertThat(names()).hasSize(5);
    }

    @Test
    void navigatesIntoFoldersBackForwardAndUp() {
        browser.open(root.toString());
        browser.open(root.resolve("Berichte").toString());

        assertThat(browser.location()).contains(root.resolve("Berichte").toString());
        assertThat(browser.canGoBack()).isTrue();
        assertThat(browser.canGoForward()).isFalse();

        browser.back();
        assertThat(browser.location()).contains(root.toString());
        assertThat(browser.canGoForward()).isTrue();

        browser.forward();
        assertThat(browser.location()).contains(root.resolve("Berichte").toString());

        browser.up();
        assertThat(browser.location()).contains(root.toString());
        assertThat(browser.canGoForward()).isFalse();
    }

    @Test
    void createsFoldersAndFilesAndNeverOverwrites() throws IOException {
        browser.open(root.toString());

        browser.createFolder("Neu");
        assertThat(Files.isDirectory(root.resolve("Neu"))).isTrue();
        assertThat(names()).contains("Neu");
        assertThat(browser.message()).map(FileBrowser.Message::tone).contains(FileBrowser.Tone.SUCCESS);

        browser.createFile("leer.txt");
        assertThat(Files.size(root.resolve("leer.txt"))).isZero();

        browser.createFile("notiz.txt");
        assertThat(Files.readString(root.resolve("notiz.txt"))).isEqualTo("Grüße an Bord");
        assertThat(browser.message()).map(FileBrowser.Message::tone).contains(FileBrowser.Tone.ERROR);
        assertThat(browser.message()).map(FileBrowser.Message::text).contains("Den Namen gibt es hier schon.");
    }

    @Test
    void rejectsInvalidNamesBeforeAskingTheSource() {
        browser.open(root.toString());

        browser.createFolder("a/b");
        assertThat(browser.message()).map(FileBrowser.Message::tone).contains(FileBrowser.Tone.ERROR);
        assertThat(Files.exists(root.resolve("a"))).isFalse();

        browser.createFile("  ");
        assertThat(browser.message()).map(FileBrowser.Message::text).contains("Bitte einen Namen eingeben.");
    }

    @Test
    void deletesWhatItCanAndNamesWhatFailed() throws IOException {
        Files.writeString(root.resolve("Berichte").resolve("drin.txt"), "x");
        browser.open(root.toString());
        List<FileEntry> doomed = browser.visible().stream()
                .filter(e -> e.name().equals("Berichte") || e.name().equals("notiz.txt")).toList();

        browser.delete(doomed);

        assertThat(Files.exists(root.resolve("notiz.txt"))).isFalse();
        assertThat(Files.exists(root.resolve("Berichte"))).isTrue();
        assertThat(browser.message()).map(FileBrowser.Message::text)
                .contains("1 gelöscht, 1 nicht gelöscht: Berichte – Der Ordner ist nicht leer.");
        assertThat(names()).doesNotContain("notiz.txt");
    }

    @Test
    void reportsAMissingFolderWithReason() {
        browser.open(root.resolve("gibt-es-nicht").toString());

        assertThat(browser.failure()).contains("Ordner nicht gefunden.");
        assertThat(browser.visible()).isEmpty();
    }

    @Test
    void tellsObserversAboutEveryChangeWithoutPolling() {
        AtomicInteger calls = new AtomicInteger();
        browser.observe(b -> calls.incrementAndGet());

        browser.open(root.toString());
        int afterOpen = calls.get();
        browser.filter("txt");

        assertThat(afterOpen).isGreaterThanOrEqualTo(2);
        assertThat(calls.get()).isEqualTo(afterOpen + 1);
    }

    @Test
    void onlyTheNewestRequestCounts() throws IOException {
        Files.writeString(root.resolve("Berichte").resolve("bericht.txt"), "x");
        List<Runnable> queued = new ArrayList<>();
        FileBrowser deferred = new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()),
                queued::add);
        deferred.open(root.resolve("Berichte").toString());
        deferred.open(root.resolve("archiv").toString());
        assertThat(deferred.location()).contains(root.resolve("archiv").toString());
        assertThat(deferred.loading()).isTrue();

        // Die neuere Anfrage antwortet zuerst, die ältere kommt zu spät und ändert nichts mehr.
        queued.remove(1).run();
        assertThat(deferred.loading()).isFalse();
        queued.remove(0).run();

        assertThat(deferred.location()).contains(root.resolve("archiv").toString());
        assertThat(deferred.entries()).isEmpty();
        assertThat(deferred.canGoBack()).isTrue();
    }

    private List<String> names() {
        return browser.visible().stream().map(FileEntry::name).toList();
    }
}
