package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LargeFoldersTest {

    @TempDir
    Path root;

    private void file(String relative, int bytes) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    @Test
    void namesOnlyTheNarrowestFoldersOverTheLimit() throws IOException {
        file("klein/a.txt", 10);
        file("archiv/alt/riesig.bin", 300);
        file("archiv/notiz.txt", 5);
        file("viele/a/x.txt", 60);
        file("viele/b/y.txt", 60);

        LargeFolders.Report report = LargeFolders.scan(root, root, 100, relative -> false);

        assertThat(report.totalBytes()).isEqualTo(435);
        // archiv liegt nur wegen archiv/alt über der Grenze: nur der engere Ordner fällt heraus.
        assertThat(report.large()).containsExactly(new LargeFolders.Folder("archiv/alt", 300),
                new LargeFolders.Folder("viele", 120));
    }

    @Test
    void skipsHiddenAndAlreadyExcludedFolders() throws IOException {
        file(".git/objects/pack.bin", 500);
        file("ausgeschlossen/gross.bin", 500);
        file("rest/a.txt", 10);

        LargeFolders.Report report = LargeFolders.scan(root, root, 100, "ausgeschlossen"::equals);

        assertThat(report.totalBytes()).isEqualTo(10);
        assertThat(report.large()).isEmpty();
    }

    @Test
    void reportsPathsRelativeToTheSourceWhenASubfolderIsIndexed() throws IOException {
        file("projekt/daten/gross.bin", 200);
        file("anderes/gross.bin", 200);

        LargeFolders.Report report = LargeFolders.scan(root, root.resolve("projekt"), 100, relative -> false);

        assertThat(report.totalBytes()).isEqualTo(200);
        assertThat(report.large()).containsExactly(new LargeFolders.Folder("projekt/daten", 200));
    }
}
