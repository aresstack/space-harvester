package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFolderIndexPlanTest {

    @TempDir
    Path home;

    private SourceSettingsConfig source(String id, Path root) {
        return new SourceSettingsConfig(id, "files", id, null, null, Map.of("directory", root.toString()));
    }

    @Test
    void aNewFolderIsItsOwnSource() throws Exception {
        Path folder = Files.createDirectories(home.resolve("new"));
        var target = LocalFolderIndexPlan.resolve(folder, List.of(), Set.of());
        assertThat(target.folder()).isEqualTo(folder.toRealPath());
        assertThat(target.newSource()).isTrue();
        assertThat(target.pageId()).isNull();
    }

    @Test
    void anExistingRootIsReused() throws Exception {
        Path folder = Files.createDirectories(home.resolve("existing"));
        var target = LocalFolderIndexPlan.resolve(folder, List.of(source("existing", folder)), Set.of("existing"));
        assertThat(target.sourceId()).isEqualTo("existing");
        assertThat(target.pageId()).isNull();
    }

    @Test
    void aSubfolderUsesTheExistingRelativePageIdentity() throws Exception {
        Path root = Files.createDirectories(home.resolve("existing"));
        Path folder = Files.createDirectories(root.resolve("one").resolve("two"));
        var target = LocalFolderIndexPlan.resolve(folder, List.of(source("existing", root)), Set.of("existing"));
        assertThat(target.sourceId()).isEqualTo("existing");
        assertThat(target.pageId()).isEqualTo(LocalFilesSourceAdapter.folderPageId(Path.of("one", "two")));
    }

    @Test
    void aDisabledSourceIsNeverActivatedOrDuplicated() throws Exception {
        Path root = Files.createDirectories(home.resolve("existing"));
        assertThatThrownBy(() -> LocalFolderIndexPlan.resolve(root, List.of(source("existing", root)), Set.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deaktiviert");
    }

    @Test
    void aParentDoesNotSilentlyDuplicateAnExistingSource() throws Exception {
        Path root = Files.createDirectories(home.resolve("parent").resolve("existing"));
        assertThatThrownBy(() -> LocalFolderIndexPlan.resolve(root.getParent(), List.of(source("existing", root)),
                Set.of("existing"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("enthält");
    }

    @Test
    void ambiguousOverlappingSourcesRequireAnExplicitDecision() throws Exception {
        Path root = Files.createDirectories(home.resolve("existing"));
        Path folder = Files.createDirectories(root.resolve("child"));
        assertThatThrownBy(() -> LocalFolderIndexPlan.resolve(folder,
                List.of(source("existing", root), source("child", folder)), Set.of("existing", "child")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Mehrere");
    }
}
