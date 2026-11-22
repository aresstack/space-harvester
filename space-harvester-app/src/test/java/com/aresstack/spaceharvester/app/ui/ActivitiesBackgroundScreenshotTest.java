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
                for (int width : new int[] {1280, 960, 420}) {
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
            // Das Hauptfenster mit der rechten Seitenleiste: erst die Aufträge, dann der Chat, zum Vergleich der
            // Kopf- und Fußzeilen mit der Mitte.
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = new MainWindow(context);
                javax.swing.JPanel surface = window.content(() -> { }, () -> { });
                surface.setSize(1400, 820);
                window.show(AppContext.Section.CONTENT);
                window.activities().tabs.select(ActivitiesView.BACKGROUND_TAB);
                window.jobsOpen(true);
                window.activities().showJobsForTest(ActivitiesBackgroundTabTest.sample());
                Screenshots.write(surface, out.resolve("fenster-auftraege-leiste.png"));
                window.chatOpen(true);
                Screenshots.write(surface, out.resolve("fenster-chat-leiste.png"));
                window.jobsOpen(true);
                window.chatLarge(true);
                window.activities().showJobsForTest(ActivitiesBackgroundTabTest.sample());
                Screenshots.write(surface, out.resolve("fenster-auftraege-gross.png"));
                window.chatLarge(false);
                window.chatOpen(false);
                surface.setSize(1700, 820);
                window.jobsOpen(true);
                Screenshots.write(surface, out.resolve("fenster-auftraege-leiste-420.png"));
                window.jobsOpen(false);
            });
        } finally {
            context.shutdown();
        }
    }
}
