package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Manual probe, not a regression test: the Anhänge tab of one page with a copy of the user's configuration and data
 * ({@code probe-describe.yml}), printing the failure with its stack trace. Runs only with the environment variable
 * {@code SPACEHARVESTER_PROBE_ASSETS} set to {@code <sourceId> <pageId>}.
 */
class AssetsProbe {

    @Test
    void probe() throws Exception {
        String target = System.getenv("SPACEHARVESTER_PROBE_ASSETS");
        if (target == null) {
            return;
        }
        String[] parts = target.split(" ", 2);
        Path home = AppSettingsLoader.defaultHome();
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("probe-describe.yml"), "probe");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, TimeUnit.SECONDS);
        var workspace = context.runtime().workspace();
        try {
            var assets = workspace.assets(parts[0], parts[1]);
            System.out.println("PROBE assets " + assets.entries().size() + " complete " + assets.listComplete());
            assets.entries().forEach(entry -> System.out.println("PROBE   " + entry.kind() + " "
                    + entry.attachment().title()));
        } catch (RuntimeException failure) {
            System.out.println("PROBE failed: " + failure);
            failure.printStackTrace(System.out);
        }
        context.shutdown();
    }
}
