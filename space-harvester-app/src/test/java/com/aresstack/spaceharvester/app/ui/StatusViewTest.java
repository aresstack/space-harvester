package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Status als Diagnose: Auftragsereignisse aus dem Core und die Verbindungsprüfungen der Quellen, gefiltert nach Art und
 * Text; der Sprung aus Aufträge zeigt genau einen Auftrag mit aufgeklappten Details.
 */
class StatusViewTest {

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
    void jobEventsAndConnectionChecksAreFilteredAndOneJobCanBeFocused() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n\nText.\n");
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
        String job = workspace.startSync("handbuch", workspace.spaces("handbuch").get(0).key()).id();
        await(() -> workspace.job(job).state().terminal());
        assertThat(workspace.events(job)).isNotEmpty();

        StatusView[] view = new StatusView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new StatusView(context));
        await(() -> view[0].entries().stream().anyMatch(e -> job.equals(e.jobId())));
        List<StatusView.Entry> all = view[0].entries();
        // Die Verbindungsprüfung erscheint neben den Auftragsereignissen.
        assertThat(all).anySatisfy(e -> {
            assertThat(e.jobId()).isNull();
            assertThat(e.component()).isEqualTo("Verbindungsprüfung");
            assertThat(e.category()).isEqualTo(JobEvent.Category.NETWORK);
        });
        // Neueste zuerst.
        assertThat(all).isSortedAccordingTo((a, b) -> b.at().compareTo(a.at()));

        SwingUtilities.invokeAndWait(() -> view[0].filter(StatusView.Filter.JOBS));
        assertThat(view[0].entries()).isNotEmpty().allSatisfy(e -> assertThat(e.jobId()).isEqualTo(job));
        SwingUtilities.invokeAndWait(() -> view[0].filter(StatusView.Filter.NETWORK));
        assertThat(view[0].entries()).isNotEmpty().allSatisfy(e -> assertThat(e.jobId()).isNull());
        SwingUtilities.invokeAndWait(() -> view[0].filter(StatusView.Filter.PROBLEMS));
        assertThat(view[0].entries()).isEmpty();

        SwingUtilities.invokeAndWait(() -> {
            view[0].filter(StatusView.Filter.ALL);
            view[0].filterText(job);
        });
        assertThat(view[0].entries()).isNotEmpty().allSatisfy(e -> assertThat(e.jobId()).isEqualTo(job));

        // Aus Aufträge: nur dieser Auftrag, das jüngste Ereignis ist offen.
        SwingUtilities.invokeAndWait(() -> {
            view[0].filterText("");
            view[0].showJob(job);
        });
        await(() -> view[0].entries().stream().allMatch(e -> job.equals(e.jobId()))
                && view[0].detailText().contains("Auftrag:"));
        assertThat(view[0].entries()).hasSize(workspace.events(job).size());
        assertThat(view[0].detailText()).contains(job).contains("Komponente:");
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
