package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bildschirmfotos der Auftragsverwaltung mit dem Reiter „Hintergrund“ zur Sichtprüfung (erfundene Titel und Spaces);
 * läuft nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}.
 */
class ActivitiesBackgroundScreenshotTest {

    @Test
    void renderBackgroundTab() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        Path home = Files.createTempDirectory("bgshot");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            for (String tab : new String[] {ActivitiesView.BACKGROUND_TAB, ActivitiesView.JOBS_TAB}) {
                for (int width : new int[] {1280, 960}) {
                    SwingUtilities.invokeAndWait(() -> {
                        ActivitiesView view = new ActivitiesView(context, null);
                        view.tabs.select(tab);
                        view.showJobsForTest(ActivitiesBackgroundTabTest.sample());
                        view.component().setSize(width, 520);
                        Screenshots.write(view.component(), out.resolve("auftraege-"
                                + (tab.equals(ActivitiesView.JOBS_TAB) ? "benutzer" : "hintergrund") + "-" + width
                                + ".png"));
                    });
                }
            }
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }
}
