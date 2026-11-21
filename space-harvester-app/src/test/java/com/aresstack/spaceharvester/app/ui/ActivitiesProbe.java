package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Manual probe, not a regression test: the Aufträge view with a copy of the user's configuration and data
 * ({@code probe-describe.yml}); selects every job in turn and prints what the detail shows. Runs only with the
 * environment variable {@code SPACEHARVESTER_PROBE_ACTIVITIES}.
 */
class ActivitiesProbe {

    @Test
    void probe() throws Exception {
        if (System.getenv("SPACEHARVESTER_PROBE_ACTIVITIES") == null) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.out.println("PROBE uncaught in " + thread.getName() + ": " + error);
            error.printStackTrace(System.out);
        });
        Path home = AppSettingsLoader.defaultHome();
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("probe-describe.yml"), "probe");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, TimeUnit.SECONDS);
        ActivitiesView[] view = new ActivitiesView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new ActivitiesView(context);
            view[0].component().setSize(1200, 800);
            view[0].shown();
        });
        Thread.sleep(3000);
        int[] rows = new int[1];
        SwingUtilities.invokeAndWait(() -> rows[0] = view[0].table().getRowCount());
        System.out.println("PROBE rows " + rows[0]);
        for (int row = 0; row < rows[0]; row++) {
            int at = row;
            SwingUtilities.invokeAndWait(() -> view[0].table().getSelectionModel().setSelectionInterval(at, at));
            Thread.sleep(1500);
            SwingUtilities.invokeAndWait(() -> System.out.println("PROBE row " + at + " "
                    + view[0].table().getValueAt(at, 0) + " -> " + view[0].detailTitleForTest()));
        }
        context.shutdown();
    }
}
