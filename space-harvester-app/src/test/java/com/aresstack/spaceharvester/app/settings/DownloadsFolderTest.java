package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Angelo 20.11.2026: Vorgabe jedes Ausgabeordners ist der Downloads-Ordner, unter Windows der Known Folder. */
class DownloadsFolderTest {

    @Test
    void windowsTakesTheRedirectedKnownFolderWithVariablesExpanded() {
        Path located = DownloadsFolder.locate("Windows 11", "C:\\Users\\angelo", Map.of("OneDrive", "D:\\OneDrive"),
                (key, name) -> key.contains("User Shell Folders") && name.equals(DownloadsFolder.KNOWN_FOLDER_ID)
                        ? Optional.of("%ONEDRIVE%\\Downloads") : Optional.empty());

        assertThat(located.toString()).isEqualTo("D:\\OneDrive\\Downloads");
    }

    @Test
    void windowsFallsBackToShellFoldersAndThenToTheProfile() {
        Path shell = DownloadsFolder.locate("Windows 10", "home", Map.of(),
                (key, name) -> key.endsWith("\\Shell Folders") ? Optional.of("E:\\Laden") : Optional.empty());
        Path none = DownloadsFolder.locate("Windows 10", "home", Map.of(), (key, name) -> Optional.empty());

        assertThat(shell.toString()).isEqualTo("E:\\Laden");
        assertThat(none).isEqualTo(Path.of("home", "Downloads"));
    }

    @Test
    void regQueryOutputYieldsTheValue() {
        assertThat(DownloadsFolder.parseRegQuery(List.of("",
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                "    {374DE290-123F-4565-9164-39C4925E467B}    REG_EXPAND_SZ    %USERPROFILE%\\Downloads", "")))
                .contains("%USERPROFILE%\\Downloads");
        assertThat(DownloadsFolder.expandWindows("%USERPROFILE%\\Downloads", Map.of(), "C:\\Users\\a"))
                .isEqualTo("C:\\Users\\a\\Downloads");
    }

    @Test
    void linuxReadsXdgUserDirs(@TempDir Path home) throws Exception {
        Path config = home.resolve(".config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("user-dirs.dirs"),
                "# Kommentar\nXDG_DESKTOP_DIR=\"$HOME/Desktop\"\nXDG_DOWNLOAD_DIR=\"$HOME/Herunterladen\"\n");

        assertThat(DownloadsFolder.locate("Linux", home.toString(), Map.of(), (k, n) -> Optional.empty()))
                .isEqualTo(home.resolve("Herunterladen"));
        assertThat(DownloadsFolder.locate("Mac OS X", home.toString(), Map.of(), (k, n) -> Optional.empty()))
                .isEqualTo(home.resolve("Downloads"));
    }
}
