package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Reiter „Beziehungen“ unter Begriffe (#120) mit echtem Core: Vorschläge der Akronym-Regel stehen in der Liste,
 * Bestätigen und Ablehnen gehen mit dem gezeigten Stand an den Core, abgelehnte bleiben sichtbar.
 */
class TermRelationsViewTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void proposalsAreListedAndReviewedInTheCore() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("schnittstellen.md"), "# Schnittstellen\n\nDie Application Programming "
                + "Interface (API) nutzt REST. Jede API meldet sich per Single Sign-On (SSO) an.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();
        await(() -> context.connection("handbuch").state() == AppContext.Connection.State.CONNECTED);
        String space = workspace.spaces("handbuch").get(0).key();
        String job = workspace.startSync("handbuch", space).id();
        for (int i = 0; i < 600 && !workspace.job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        assertThat(workspace.job(job).state()).isEqualTo(JobState.COMPLETED);
        // Der Abgleich legt nur den Katalog an; das Lesen der Seite füllt den Begriffsindex.
        String pageId = workspace.liveMetadata("handbuch", space, null, 10).entries().get(0).page().pageId();
        workspace.page("handbuch", pageId, false);
        assertThat(workspace.proposeAcronymRelations("handbuch").added()).isEqualTo(2);

        TermsView[] view = new TermsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new TermsView(context);
            view[0].shown();
        });
        await(() -> view[0].relations().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].relations().table().getValueAt(0, 0)).isEqualTo("API");
            assertThat(view[0].relations().table().getValueAt(0, 1)).isEqualTo("Abkürzung für");
            assertThat(view[0].relations().table().getValueAt(0, 3)).isEqualTo("Vorgeschlagen");
            assertThat(view[0].relations().table().getValueAt(0, 4)).isEqualTo("Regel");
            view[0].relations().table().setRowSelectionInterval(0, 0);
            view[0].reviewButtons().get(0).doClick();
        });
        await(() -> "Bestätigt".equals(view[0].relations().table().getValueAt(0, 3)));

        // Abgelehnt bleibt die Beziehung sichtbar, am Ende der Liste.
        SwingUtilities.invokeAndWait(() -> {
            view[0].relations().table().setRowSelectionInterval(1, 1);
            view[0].reviewButtons().get(1).doClick();
        });
        await(() -> view[0].relations().table().getRowCount() == 2
                && "Abgelehnt".equals(view[0].relations().table().getValueAt(1, 3)));
        assertThat(workspace.termRelations(null, null, 10).relations()).hasSize(1);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }
}
