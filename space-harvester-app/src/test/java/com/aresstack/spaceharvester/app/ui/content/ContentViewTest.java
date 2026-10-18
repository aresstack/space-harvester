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
 * Die Leseansicht wie ein Bediener sie erlebt: Eine Seite aus dem Explorer lädt sofort (auch ohne Bestand) und nennt,
 * wo sie liegt; ein anderer Space lässt nichts Altes stehen; „Seiten-ID oder Adresse“ öffnet in der aktuellen Quelle.
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
    void aPageLoadsRightAwayEvenWithoutLocalCopyAndNamesWhereItLies() throws Exception {
        String space = space("handbuch");
        onEdt(() -> {
            view.openPage("handbuch", firstPage("handbuch", space));
            return null;
        });

        await(() -> view.detail().shownPage() != null);
        // Nachgeladen und sofort abgelegt (ADR 0010): die Anzeige kommt bereits aus dem Cache.
        assertThat(onEdt(() -> view.detail().shownPage().live())).isFalse();
        assertThat(new String(onEdt(() -> view.detail().shownPage().snapshot().payload().bytes()),
                StandardCharsets.UTF_8)).contains("Text der Seite");
        assertThat(onEdt(() -> view.detail().feedback().isVisible())).as("keine Cache-Meldung").isFalse();
        assertThat(onEdt(view::crumbs)).isEqualTo("handbuch › " + space);
    }

    @Test
    void openingASpaceShowsNoPageOfTheOldOne() throws Exception {
        String space = space("handbuch");
        onEdt(() -> {
            view.openPage("handbuch", firstPage("handbuch", space));
            return null;
        });
        await(() -> view.detail().shownPage() != null);

        String other = space("notizen");
        onEdt(() -> {
            view.openSpace("notizen", other);
            return null;
        });

        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(view::chosenSource)).isEqualTo("notizen");
        assertThat(onEdt(view::crumbs)).isEqualTo("notizen › " + other);
    }

    @Test
    void aPageStillLoadingFromTheOldSpaceNeverShowsUp() throws Exception {
        String space = space("handbuch");
        String page = firstPage("handbuch", space);
        String other = space("notizen");
        onEdt(() -> {
            view.openPage("handbuch", page);
            view.openSpace("notizen", other);
            return null;
        });

        Thread.sleep(1500);
        assertThat(onEdt(() -> view.detail().shownPage())).isNull();
        assertThat(onEdt(() -> view.detail().isEmpty())).isTrue();
    }

    @Test
    void aTypedPageIdOpensInTheCurrentSource() throws Exception {
        String space = space("notizen");
        String page = firstPage("notizen", space);
        assertThat(onEdt(() -> view.openId().isEnabled())).as("ohne Wissensquelle kein Ziel").isFalse();
        onEdt(() -> {
            view.openSpace("notizen", space);
            view.openId().setText(page);
            view.openId().postActionEvent();
            return null;
        });

        await(() -> view.detail().shownPage() != null);
        assertThat(onEdt(() -> view.detail().shownPage().header().key().pageId())).isEqualTo(page);
    }

    private String space(String sourceId) {
        return context.runtime().workspace().spaces(sourceId).get(0).key();
    }

    private String firstPage(String sourceId, String spaceKey) {
        return context.runtime().workspace().liveMetadata(sourceId, spaceKey, null, 10).entries().get(0).page().pageId();
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
