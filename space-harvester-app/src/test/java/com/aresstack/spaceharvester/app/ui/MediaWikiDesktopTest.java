package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiServer;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiTransport;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the real desktop window against an isolated wiki without creating a sync job. */
class MediaWikiDesktopTest {

    @TempDir
    Path home;

    @Test
    void navigateAndReadWithoutSyncInTheDesktopWindow() throws Exception {
        String screenshotDir = System.getenv("WIKI_DESKTOP_SCREENSHOTS");
        Assumptions.assumeTrue(screenshotDir != null && !screenshotDir.isBlank());
        System.setProperty("java.awt.headless", "false");
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeMediaWikiTransport wiki = new FakeMediaWikiTransport();
        wiki.mainNamespaceSubpages = true;
        wiki.page("Hauptseite", "<p>Startseite des Testwikis</p>", 1, 1);
        wiki.page("Hauptseite/Unterseite", "<p>Unterseite</p>", 2, 2);
        wiki.page("Verwaist", "<h2>Praxisprüfung</h2><p>Unverlinkter Artikel im echten Lesefenster.</p>", 3, 3);
        wiki.categories.put("A", List.of("Category:B"));
        wiki.categories.put("B", List.of("Category:A", "Verwaist"));
        try (FakeMediaWikiServer server = new FakeMediaWikiServer(wiki)) {
            Path config = home.resolve("app.yml");
            Files.writeString(config, """
                    space-harvester-app:
                      data-directory: '%s'
                    space-harvester:
                      sources:
                        - id: wiki
                          type: mediawiki
                          title: Testwiki
                          options:
                            apiUrl: '%s/index.php?title=Hauptseite'
                    """.formatted(home.resolve("data").toString().replace('\\', '/'), server.apiUrl()));
            AppContext context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home),
                    config, "wiki-desktop-test");
            JFrame[] frame = new JFrame[1];
            try {
                CountDownLatch started = new CountDownLatch(1);
                edt(() -> { context.reload(started::countDown); return null; });
                assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
                MainWindow window = edt(() -> new MainWindow(context));
                JPanel surface = edt(() -> {
                    JPanel panel = window.content(() -> { }, () -> { });
                    frame[0] = new JFrame("Space Harvester – isolierte Wiki-Abnahme");
                    frame[0].setContentPane(panel);
                    frame[0].setSize(1280, 820);
                    frame[0].setVisible(true);
                    window.show(AppContext.Section.CONTENT);
                    return panel;
                });
                JTree tree = window.explorer().tree();
                Explorer.Node root = edt(() -> (Explorer.Node) tree.getModel().getRoot());
                Explorer.Node source = waitChild(root, "Testwiki");
                expand(tree, source);
                Explorer.Node space = waitChild(source, "Testwiki");
                expand(tree, space);
                Explorer.Node homePage = waitChild(space, "Hauptseite");
                expand(tree, homePage);
                waitChild(homePage, "Hauptseite/Unterseite");
                Explorer.Node all = waitChild(space, "Alle Seiten");
                expand(tree, all);
                waitChild(all, "Verwaist");
                Explorer.Node categories = waitChild(space, "Kategorien");
                expand(tree, categories);
                Explorer.Node a = waitChild(categories, "A");
                expand(tree, a);
                Explorer.Node b = waitChild(a, "B");
                expand(tree, b);
                Explorer.Node article = waitChild(b, "Verwaist");
                edt(() -> {
                    assertThat(child(b, "A")).isNull();
                    TreePath path = new TreePath(article.getPath());
                    tree.scrollPathToVisible(path);
                    Rectangle bounds = tree.getPathBounds(path);
                    assertThat(bounds).isNotNull();
                    tree.setSelectionPath(path);
                    window.explorer().treeMouse().mouseClicked(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0, bounds.x + 15, bounds.y + bounds.height / 2,
                            1, false, MouseEvent.BUTTON1));
                    return null;
                });
                await(() -> edt(() -> hasReadingText(surface, "Unverlinkter Artikel im echten Lesefenster.")));
                Path folder = Path.of(screenshotDir);
                Files.createDirectories(folder);
                Path png = folder.resolve("mediawiki-desktop.png");
                edt(() -> { Screenshots.write(surface, png); return null; });
                assertThat(png).isNotEmptyFile();
            } finally {
                edt(() -> { if (frame[0] != null) frame[0].dispose(); return null; });
                context.shutdown();
            }
        }
    }

    private static void expand(JTree tree, Explorer.Node node) throws Exception {
        edt(() -> { tree.expandPath(new TreePath(node.getPath())); return null; });
    }

    private static Explorer.Node waitChild(Explorer.Node parent, String title) throws Exception {
        await(() -> edt(() -> child(parent, title) != null));
        return edt(() -> child(parent, title));
    }

    private static Explorer.Node child(Explorer.Node parent, String title) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Explorer.Node node = (Explorer.Node) parent.getChildAt(i);
            String name = switch (node.item()) {
                case Explorer.SourceItem source -> source.title();
                case Explorer.SpaceItem space -> space.name();
                case Explorer.PageItem page -> page.title();
                default -> "";
            };
            if (title.equals(name)) return node;
        }
        return null;
    }

    private static boolean hasReadingText(Component component, String text) {
        if (component instanceof JEditorPane pane && pane.isShowing()
                && pane.getText().contains(text)) return true;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasReadingText(child, text)) return true;
            }
        }
        return false;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Desktop condition not reached");
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
