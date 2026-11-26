package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Knotentypen des Explorers hinter einer Schnittstelle (Angelo 26.11.2026): Quelle, Space, Seite, Datei, Ordner und
 * Gruppierung sagen selbst, was sie können. Confluence verhält sich wie vorher; ein SharePoint-Ordner in beliebiger
 * Tiefe hat Kontextmenü, Favorit, Ausschließen und Bestandszeichen wie eine Seite.
 */
class ExplorerNodeTypesTest {

    private static final Explorer.SourceItem SOURCE = new Explorer.SourceItem("wiki", "Wiki");
    private static final Explorer.SpaceItem SPACE = new Explorer.SpaceItem("wiki", "OPS", "Betrieb");
    /** Eine Confluence-Seite mit Unterseiten: wie bisher ohne Art erzeugt. */
    private static final Explorer.PageItem PAGE = Explorer.PageItem.of("wiki", "OPS", "4711", "Handbuch", true);
    /** Ein SharePoint-Ordner unter „Freigegebene Dokumente“ (dritte Ebene unter der Site). */
    private static final Explorer.PageItem FOLDER = Explorer.PageItem.of("sp", "site", "sp-folder-hkr", "HKRZENTR",
            null, Kind.FOLDER);
    /** MediaWiki „Alle Seiten“: nur eine Gruppierung der Navigation. */
    private static final Explorer.PageItem GROUP = Explorer.PageItem.of("mw", "mw", "wiki-nav-all", "Alle Seiten",
            true, Kind.GROUP);

    private static final List<String> PAGE_MENU = List.of("Öffnen", Explorer.INDEX, Explorer.PAGE_ONLY,
            Explorer.EXCLUDE, Explorer.FAVORITE, Explorer.REFRESH, Explorer.ANALYZE, Explorer.ANALYZE_PAGE,
            Explorer.CHECK, Explorer.CHECK_PAGE);

    @TempDir
    Path home;

    private AppContext context;
    private Explorer explorer;

    @BeforeEach
    void setUp() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> explorer = new Explorer(context));
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theKindTheSourceReportsChoosesTheNodeType() {
        assertThat(Explorer.PageItem.of("s", "k", "p", "t", true)).isInstanceOf(Explorer.DocumentItem.class);
        assertThat(Explorer.PageItem.of("s", "k", "p", "t", false, Kind.FILE)).isInstanceOf(Explorer.DocumentItem.class)
                .extracting(Explorer.PageItem::kind).isEqualTo(Kind.FILE);
        assertThat(FOLDER).isInstanceOf(Explorer.FolderItem.class).extracting(Explorer.PageItem::kind)
                .isEqualTo(Kind.FOLDER);
        assertThat(GROUP).isInstanceOf(Explorer.GroupItem.class).extracting(Explorer.PageItem::kind)
                .isEqualTo(Kind.GROUP);
    }

    @Test
    void confluenceKeepsItsCapabilitiesAndScopes() {
        assertThat(List.of(SOURCE.starrable(), SOURCE.refreshable(), SOURCE.indexable(), SOURCE.excludable()))
                .containsExactly(true, true, true, false);
        assertThat(List.of(SPACE.starrable(), SPACE.refreshable(), SPACE.indexable(), SPACE.excludable()))
                .containsExactly(true, true, true, true);
        assertThat(List.of(PAGE.starrable(), PAGE.refreshable(), PAGE.indexable(), PAGE.excludable(),
                PAGE.findable())).containsOnly(true);
        assertThat(PAGE.scope()).isEqualTo(new Explorer.Scope("wiki", "OPS", "4711", "Handbuch"));
        assertThat(SPACE.scope()).isEqualTo(new Explorer.Scope("wiki", "OPS"));
        assertThat(SOURCE.scope()).isEqualTo(new Explorer.Scope("wiki", null));
        assertThat(PAGE.analysisScope(false)).isEqualTo(new StatementAnalysisJobs.Scope.Subtree("wiki", "OPS", "4711"));
        assertThat(PAGE.selection().pageId()).isEqualTo("4711");
    }

    @Test
    void aFolderAtAnyDepthCanDoWhatAPageCan() {
        assertThat(List.of(FOLDER.starrable(), FOLDER.refreshable(), FOLDER.indexable(), FOLDER.excludable(),
                FOLDER.findable())).containsOnly(true);
        assertThat(FOLDER.scope()).isEqualTo(new Explorer.Scope("sp", "site", "sp-folder-hkr", "HKRZENTR"));
        assertThat(FOLDER.analysisScope(false))
                .isEqualTo(new StatementAnalysisJobs.Scope.Subtree("sp", "site", "sp-folder-hkr"));
    }

    @Test
    void aNavigationGroupMeansItsSpaceAndOffersNoTreeActions() {
        assertThat(List.of(GROUP.starrable(), GROUP.refreshable(), GROUP.indexable(), GROUP.excludable(),
                GROUP.findable())).containsOnly(false);
        assertThat(GROUP.scope()).isEqualTo(new Explorer.Scope("mw", "mw"));
        assertThat(GROUP.selection()).isNull();
        assertThat(GROUP.analysisScope(false)).isNull();
    }

    @Test
    void theContextMenuIsTheSameForPagesAndFoldersInEveryDepth() throws Exception {
        assertThat(labels(PAGE)).containsExactlyElementsOf(PAGE_MENU);
        assertThat(labels(FOLDER)).containsExactlyElementsOf(PAGE_MENU);
        assertThat(labels(SPACE)).containsExactly(Explorer.INDEX, Explorer.EXCLUDE, Explorer.FAVORITE,
                Explorer.REFRESH, Explorer.ANALYZE, Explorer.CHECK);
        assertThat(labels(SOURCE)).containsExactly(Explorer.INDEX, Explorer.FAVORITE, Explorer.REFRESH,
                Explorer.RENAME, Explorer.ANALYZE, Explorer.CHECK);
        assertThat(labels(GROUP)).containsExactly("Öffnen");
        assertThat(labels(new Explorer.MoreItem("c"))).isEmpty();
    }

    @Test
    void everyKnowledgeNodeKeepsTheStateColumn() throws Exception {
        for (Explorer.Item item : List.of(SOURCE, SPACE, PAGE, FOLDER, GROUP)) {
            assertThat(columns(item)).as("Spalten rechts an %s", item).isPositive();
        }
        assertThat(columns(new Explorer.Note("Lädt …"))).isZero();
    }

    private List<String> labels(Explorer.Item item) throws Exception {
        List<String> labels = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> Arrays.stream(explorer.menu(item).getComponents())
                .map(c -> ((JMenuItem) c).getText()).forEach(labels::add));
        return labels;
    }

    private int columns(Explorer.Item item) throws Exception {
        int[] right = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            JTree tree = explorer.tree();
            JComponent cell = (JComponent) tree.getCellRenderer().getTreeCellRendererComponent(tree,
                    new Explorer.Node(item), false, false, false, 0, false);
            right[0] = cell.getBorder().getBorderInsets(cell).right;
        });
        return right[0];
    }
}
