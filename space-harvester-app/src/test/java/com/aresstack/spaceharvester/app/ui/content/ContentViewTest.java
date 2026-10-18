package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Ablauf der Inhalte-Ansicht wie ein Bediener ihn erlebt: Space einer neuen Quelle öffnen (Seiten live, ohne
 * Fehlermeldung), Seite wählen (lädt sofort, auch ohne Bestand), Quelle wechseln (nichts Altes bleibt stehen),
 * Seitenbaum ohne und mit Bestand.
 */
class ContentViewTest {

    @TempDir
    Path home;

    private AppContext context;
    private ContentView view;

    @BeforeEach
    void start() throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("handbuch", "notizen")) {
            Path files = Files.createDirectories(home.resolve(id));
            for (int i = 0; i < 3; i++) {
                Files.writeString(files.resolve("%s-%d.md".formatted(id, i)),
                        "# %s %d\n\nText der Seite %d.\n".formatted(id, i, i));
            }
            sources.append("""
                        - id: %s
                          type: files
                          title: %s
                          options:
                            directory: %s
                    """.formatted(id, id, files));
        }
        // Eine Quelle, die niemand annimmt: Spaces und Seitenliste scheitern am Netz.
        sources.append("""
                    - id: wiki
                      type: confluence
                      base-url: http://127.0.0.1:9
                      options:
                        connectTimeoutMillis: 500
                """);
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                %s""".formatted(home.resolve("data"), sources));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        view = onEdt(() -> new ContentView(context));
    }

    @AfterEach
    void close() throws Exception {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void aSpaceWithoutLocalCopyShowsItsPagesLiveWithoutAnError() throws Exception {
        open("handbuch");

        await(() -> view.pages().getRowCount() == 3);
        assertThat(onEdt(view::origin)).isEqualTo(ContentView.Origin.LIVE);
        assertThat(onEdt(() -> view.feedback().isVisible())).as("keine Meldung bei leerem Bestand").isFalse();
        assertThat(onEdt(view::treeAvailable)).as("Seitenbaum auch ohne Bestand").isTrue();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
    }

    @Test
    void choosingAPageLoadsItRightAwayEvenWithoutLocalCopy() throws Exception {
        open("handbuch");
        await(() -> view.pages().getRowCount() == 3);

        onEdt(() -> {
            view.pages().setRowSelectionInterval(1, 1);
            return null;
        });

        await(() -> view.detail().shownPage() != null);
        assertThat(onEdt(() -> view.detail().selectedTab())).isEqualTo("Leseansicht");
        // Nachgeladen und sofort abgelegt (ADR 0010): die Anzeige kommt bereits aus dem Cache.
        assertThat(onEdt(() -> view.detail().shownPage().live())).isFalse();
        assertThat(new String(onEdt(() -> view.detail().shownPage().snapshot().payload().bytes()),
                StandardCharsets.UTF_8)).contains("Text der Seite");
        assertThat(onEdt(() -> view.detail().feedback().isVisible())).as("keine Cache-Meldung").isFalse();
    }

    @Test
    void switchingTheSourceLeavesNothingOfTheOldOne() throws Exception {
        open("handbuch");
        await(() -> view.pages().getRowCount() == 3);
        onEdt(() -> {
            view.pages().setRowSelectionInterval(0, 0);
            return null;
        });
        await(() -> view.detail().shownPage() != null);

        onEdt(() -> {
            view.chooseSource("notizen");
            return null;
        });

        assertThat(onEdt(() -> view.pages().getRowCount())).isZero();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(view::origin)).isNull();
    }

    @Test
    void aPageStillLoadingFromTheOldSpaceNeverShowsUp() throws Exception {
        open("handbuch");
        await(() -> view.pages().getRowCount() == 3);
        onEdt(() -> {
            view.pages().setRowSelectionInterval(0, 0);
            view.chooseSource("notizen");
            return null;
        });

        Thread.sleep(1500);
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
    }

    @Test
    void thePageTreeWorksWithoutLocalCopy() throws Exception {
        open("handbuch");
        await(() -> view.pages().getRowCount() == 3);
        assertThat(onEdt(view::origin)).isEqualTo(ContentView.Origin.LIVE);
        assertThat(onEdt(view::treeAvailable)).isTrue();

        onEdt(() -> {
            view.showPageTree();
            return null;
        });

        await(() -> view.treePageCount() == 3);
        assertThat(onEdt(() -> view.feedback().isVisible())).isFalse();
        onEdt(() -> {
            view.tree().setSelectionRow(0);
            return null;
        });
        await(() -> view.detail().shownPage() != null);
    }

    @Test
    void aSpaceInTheLocalCopyShowsItsTreeFromThere() throws Exception {
        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        String job = context.runtime().workspace().startSync("handbuch", space).id();
        for (int i = 0; i < 600 && !context.runtime().workspace().job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        open("handbuch");
        await(() -> view.origin() == ContentView.Origin.STOCK && view.pages().getRowCount() == 3);

        onEdt(() -> {
            view.showPageTree();
            return null;
        });

        await(() -> view.treePageCount() == 3);
        assertThat(onEdt(() -> view.feedback().isVisible())).isFalse();
    }

    @Test
    void openingASpaceKeepsItChosenWhileTheConnectionIsRechecked() throws Exception {
        String space = context.runtime().workspace().spaces("notizen").get(0).key();
        onEdt(() -> {
            context.checkConnection("notizen", () -> { });
            view.openSpace("notizen", space);
            return null;
        });
        await(() -> context.connection("notizen").state() != AppContext.Connection.State.CHECKING
                && view.pages().getRowCount() == 3);
        Thread.sleep(300);

        assertThat(onEdt(view::chosenSource)).isEqualTo("notizen");
        assertThat(onEdt(view::chosenSpace)).isEqualTo(space);
        assertThat(onEdt(() -> view.pages().getRowCount())).isEqualTo(3);
    }

    @Test
    void anUnreachableSpaceStaysChosenAndTheMessageNamesIt() throws Exception {
        onEdt(() -> {
            view.openSpace("wiki", "DOC");
            return null;
        });

        await(() -> view.feedback().isVisible());
        Thread.sleep(300);
        assertThat(onEdt(view::chosenSource)).isEqualTo("wiki");
        assertThat(onEdt(view::chosenSpace)).isEqualTo("DOC");
        assertThat(onEdt(() -> view.feedback().text())).contains("Seitenliste nicht abrufbar (Space DOC)");
        assertThat(onEdt(() -> view.pages().getRowCount())).isZero();
    }

    private void open(String sourceId) throws Exception {
        String space = context.runtime().workspace().spaces(sourceId).get(0).key();
        onEdt(() -> {
            view.openSpace(sourceId, space);
            return null;
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
