package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Freigegebene Ordner lokaler Dateien wie {@code LocalFileAccessPolicy} im Enterprise AI Client (#187). */
class LocalFileGrantsTest {

    @TempDir
    Path directory;
    @TempDir
    Path elsewhere;

    @BeforeEach
    void linkOutOfTheDirectory() throws IOException {
        Files.writeString(elsewhere.resolve("plan.md"), "Schichtplan", StandardCharsets.UTF_8);
        Files.createSymbolicLink(directory.resolve("plan.md"), elsewhere.resolve("plan.md"));
        Files.writeString(directory.resolve("start.md"), "Start", StandardCharsets.UTF_8);
    }

    @Test
    void aLinkOutOfTheDirectoryIsReadOnlyIntoAGrantedFolderAndGrantsApplyWithoutReopening() {
        AtomicReference<LocalFileGrants> grants = new AtomicReference<>(LocalFileGrants.none());
        KnowledgeSourceHarvestSource source = open(new LocalFilesSourceAdapter(grants::get));

        assertThat(new String(source.storedPage(id("start.md")).payload(), StandardCharsets.UTF_8))
                .isEqualTo("Start");
        assertThatThrownBy(() -> source.storedPage(id("plan.md"))).isInstanceOfSatisfying(
                SourceAccessException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.ACCESS_DENIED);
                    assertThat(e.getMessage()).doesNotContain(elsewhere.toString());
                });

        grants.set(new LocalFileGrants(false, List.of(elsewhere)));

        assertThat(new String(source.storedPage(id("plan.md")).payload(), StandardCharsets.UTF_8))
                .isEqualTo("Schichtplan");
    }

    @Test
    void withoutGrantsOnlyTheDirectoryItselfIsRead() {
        KnowledgeSourceHarvestSource source = open(new LocalFilesSourceAdapter());

        assertThatThrownBy(() -> source.storedPage(id("plan.md"))).isInstanceOf(SourceAccessException.class);
        assertThat(open(new LocalFilesSourceAdapter(() -> new LocalFileGrants(true, List.of())))
                .storedPage(id("plan.md")).payload()).isNotEmpty();
    }

    @Test
    void theConfiguredFoldersApplyAtTheNextAccessAndAFolderConnectedLaterNeedsNoRestart() throws IOException {
        Path later = elsewhere.resolveSibling(elsewhere.getFileName() + "-laufwerk");
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        KnowledgeSourceHarvestSource source = open(new LocalFilesSourceAdapter(
                () -> manager.current().configuration().localFiles().grants()));
        Files.createSymbolicLink(directory.resolve("later.md"), later.resolve("later.md"));

        assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withLocalFiles(
                new LocalFileSettings(false, List.of(later)))).outcome())
                .as("ein Ordner, den es noch nicht gibt, ist erlaubt")
                .isEqualTo(ConfigurationManager.Outcome.ACTIVATED);
        assertThatThrownBy(() -> source.storedPage(id("plan.md"))).isInstanceOf(SourceAccessException.class);

        Files.createDirectory(later);
        Files.writeString(later.resolve("later.md"), "Verbunden", StandardCharsets.UTF_8);
        try {
            assertThat(new String(source.storedPage(id("later.md")).payload(), StandardCharsets.UTF_8))
                    .isEqualTo("Verbunden");

            manager.apply(HarvesterConfiguration.DEFAULT.withLocalFiles(
                    new LocalFileSettings(false, List.of(elsewhere))));

            assertThat(new String(source.storedPage(id("plan.md")).payload(), StandardCharsets.UTF_8))
                    .isEqualTo("Schichtplan");
            assertThatThrownBy(() -> source.storedPage(id("later.md"))).isInstanceOf(SourceAccessException.class);
        } finally {
            Files.delete(later.resolve("later.md"));
            Files.delete(later);
        }
    }

    @Test
    void aFileAsGrantedFolderGrantsNothingAndIsNoErrorWhenSaved() {
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        KnowledgeSourceHarvestSource source = open(new LocalFilesSourceAdapter(
                () -> manager.current().configuration().localFiles().grants()));

        assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withLocalFiles(new LocalFileSettings(false,
                List.of(elsewhere.resolve("plan.md"))))).outcome()).isEqualTo(ConfigurationManager.Outcome.ACTIVATED);

        assertThatThrownBy(() -> source.storedPage(id("plan.md"))).isInstanceOf(SourceAccessException.class);
    }

    private KnowledgeSourceHarvestSource open(LocalFilesSourceAdapter adapter) {
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString()));
        assertThat(adapter.check(config)).isEmpty();
        return new KnowledgeSourceHarvestSource("docs", "files", "docs", adapter.open(config, null).port());
    }

    private static String id(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }
}
