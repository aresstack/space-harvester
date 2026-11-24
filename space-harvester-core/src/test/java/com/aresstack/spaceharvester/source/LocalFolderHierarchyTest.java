package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Eine Ordner-Quelle zeigt ihre Unterordner als Gliederung statt aller Dateien nebeneinander (Angelo, 24.11.2026). */
class LocalFolderHierarchyTest {

    @TempDir
    Path directory;

    @BeforeEach
    void folders() throws IOException {
        write("Handbuch/Einleitung.md", "Willkommen");
        write("Handbuch/Betrieb/Nachtlauf.md", "Ablauf in der Nacht");
        write("Handbuch/Betrieb/Stoerung.txt", "Was tun bei Störung");
        write("Handbuch-Anhang.md", "Steht zwischen den Ordnern, wenn man nach Pfad sortiert");
        write("Protokolle/2026/Januar.md", "Sitzung im Januar");
        write("notiz.txt", "Eine Datei an der Wurzel");
        write(".versteckt/geheim.md", "Nie lesen");
        Files.createDirectories(directory.resolve("Leer"));
    }

    @Test
    void theTreeShowsTheFoldersAsInTheExplorerInEveryBatchSize() {
        KnowledgeSourceHarvestSource source = open();
        for (int limit : List.of(1, 2, 500)) {
            Map<String, PageMetadata> pages = list(source, limit);

            assertThat(titles(pages)).as("Stapel " + limit).containsExactlyInAnyOrder("Handbuch", "Einleitung.md",
                    "Betrieb", "Nachtlauf.md", "Stoerung.txt", "Handbuch-Anhang.md", "Protokolle", "2026",
                    "Januar.md", "notiz.txt");
            PageTree tree = PageTreeBuilder.build(pages.values().stream()
                    .map(page -> new TreeEntry(page.pageId(), page.parentPageId())).toList());
            assertThat(tree.anomalies()).isEmpty();
            assertThat(tree.roots()).extracting(node -> pages.get(node.pageId()).title())
                    .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Protokolle", "notiz.txt");
            assertThat(tree.depth()).isEqualTo(3);
            assertThat(children(tree, pages, "Handbuch")).containsExactlyInAnyOrder("Betrieb", "Einleitung.md");
            assertThat(children(tree, pages, "Betrieb")).containsExactlyInAnyOrder("Nachtlauf.md", "Stoerung.txt");
            assertThat(children(tree, pages, "2026")).containsExactly("Januar.md");
        }
    }

    @Test
    void aFolderIsMarkedAsFolderAndHasNoContent() {
        KnowledgeSourceHarvestSource source = open();

        PageMetadata folder = source.pageMetadata(id("Handbuch/Betrieb"));
        assertThat(folder.title()).isEqualTo("Betrieb");
        assertThat(folder.attributes()).containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FOLDER_KIND);
        assertThat(folder.parentPageId()).isEqualTo(id("Handbuch"));
        assertThat(source.storedPage(id("Handbuch/Betrieb")).payload()).isEmpty();

        PageMetadata file = source.pageMetadata(id("Handbuch/Betrieb/Nachtlauf.md"));
        assertThat(file.parentPageId()).isEqualTo(id("Handbuch/Betrieb"));
        assertThat(file.attributes()).doesNotContainKey(PageMetadata.RESOURCE_KIND);
        assertThat(new String(source.storedPage(id("Handbuch/Betrieb/Nachtlauf.md")).payload(),
                StandardCharsets.UTF_8)).isEqualTo("Ablauf in der Nacht");
        assertThat(source.pageMetadata(id("notiz.txt")).parentPageId()).isNull();
    }

    @Test
    void browseOnlyDirectChildrenAndIncludeEmptyFoldersWithoutIndexing() throws IOException {
        KnowledgeSourceHarvestSource source = open();

        HarvestSource.ChildPages roots = source.children("docs", null, null);
        assertThat(roots.pages()).extracting(HarvestSource.PageNode::title)
                .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Leer", "Protokolle", "notiz.txt");
        assertThat(roots.pages()).filteredOn(node -> node.title().equals("Leer"))
                .allSatisfy(node -> assertThat(node.kind()).isEqualTo(HarvestSource.PageNode.Kind.FOLDER));
        assertThat(source.children("docs", id("Leer"), null).pages()).isEmpty();

        Files.createDirectories(directory.resolve("Handbuch/Neu"));
        assertThat(source.children("docs", id("Handbuch"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Betrieb", "Einleitung.md", "Neu");
        assertThat(source.children("docs", id("Handbuch/Betrieb"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Nachtlauf.md", "Stoerung.txt");
        assertThat(source.position(id("Handbuch/Betrieb/Nachtlauf.md")).ancestors())
                .extracting(HarvestSource.PageNode::title).containsExactly("Handbuch", "Betrieb");
    }

    @Test
    void paginateDirectChildrenWithoutLosingFolders() throws IOException {
        Files.createDirectories(directory.resolve("Viele"));
        for (int i = 0; i < 205; i++) {
            Files.createDirectory(directory.resolve("Viele/Ordner" + i));
        }
        KnowledgeSourceHarvestSource source = open();
        List<String> titles = new ArrayList<>();
        String cursor = null;
        do {
            HarvestSource.ChildPages batch = source.children("docs", id("Viele"), cursor);
            titles.addAll(batch.pages().stream().map(HarvestSource.PageNode::title).toList());
            cursor = batch.nextCursor();
        } while (cursor != null);
        assertThat(titles).hasSize(205).doesNotHaveDuplicates();
    }

    @Test
    void navigationRespectsExclusionsAndDocumentFilters() throws IOException {
        write("Handbuch/ignoriert.txt", "Excluded");
        Files.createDirectories(directory.resolve("Ausgeschlossen/Unterordner"));
        write("Ausgeschlossen/Unterordner/geheim.md", "Excluded");
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString(), "include", "*.md", "exclude", "Ausgeschlossen/**"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());

        assertThat(source.children("docs", null, null).pages())
                .extracting(HarvestSource.PageNode::title)
                .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Leer", "Protokolle");
        assertThat(source.children("docs", id("Handbuch"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Betrieb", "Einleitung.md");
    }

    @Test
    void completeInventoryDoesNotWrapAnEmptyCursor() {
        KnowledgeSourceHarvestSource source = open();
        PageMetadataBatch batch = source.pageMetadata("docs", null, 500);

        assertThat(batch.nextCursor()).isNull();
        assertThat(batch.pages()).isNotEmpty();
    }

    private Map<String, PageMetadata> list(KnowledgeSourceHarvestSource source, int limit) {
        Map<String, PageMetadata> pages = new LinkedHashMap<>();
        String cursor = null;
        do {
            PageMetadataBatch batch = source.pageMetadata("docs", cursor, limit);
            for (PageMetadata page : batch.pages()) {
                assertThat(pages.put(page.pageId(), page)).as("einmal: " + page.title()).isNull();
                if (page.parentPageId() != null) {
                    assertThat(pages).as("Ordner vor seinem Inhalt: " + page.title())
                            .containsKey(page.parentPageId());
                }
            }
            cursor = batch.nextCursor();
        } while (cursor != null);
        return pages;
    }

    private static List<String> titles(Map<String, PageMetadata> pages) {
        return pages.values().stream().map(PageMetadata::title).toList();
    }

    private static List<String> children(PageTree tree, Map<String, PageMetadata> pages, String title) {
        List<PageTree.Node> open = new ArrayList<>(tree.roots());
        while (!open.isEmpty()) {
            PageTree.Node node = open.removeFirst();
            if (pages.get(node.pageId()).title().equals(title)) {
                return node.children().stream().map(child -> pages.get(child.pageId()).title()).toList();
            }
            open.addAll(node.children());
        }
        throw new AssertionError("Kein Knoten " + title);
    }

    private KnowledgeSourceHarvestSource open() {
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString()));
        return new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());
    }

    private void write(String relative, String text) throws IOException {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String id(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }
}
