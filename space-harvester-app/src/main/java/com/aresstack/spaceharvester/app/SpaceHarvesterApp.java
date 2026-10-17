package com.aresstack.spaceharvester.app;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.MainWindow;
import com.aresstack.spaceharvester.app.ui.Screenshots;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Start der Desktop-App: {@code java -jar space-harvester-app-<version>.jar [--config <datei>]}. Ohne Angabe gilt
 * {@code <Benutzerordner>/.space-harvester/space-harvester-app.yml} (oder {@code SPACE_HARVESTER_APP_CONFIG}).
 * {@code --screenshot <ordner>} rendert jeden Bereich als PNG und beendet sich (Selbstprüfung, auch ohne Bildschirm).
 */
public final class SpaceHarvesterApp {

    private SpaceHarvesterApp() {
    }

    public static void main(String[] args) throws Exception {
        Path config = null;
        Path screenshots = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(argument(args, ++i, "--config"));
                case "--screenshot" -> screenshots = Path.of(argument(args, ++i, "--screenshot"));
                case "--version" -> {
                    System.out.println("Space Harvester Desktop " + version());
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("java -jar space-harvester-app.jar [--config <datei>] [--screenshot <ordner>]");
                    return;
                }
                default -> throw new IllegalArgumentException("Unbekannte Option: " + args[i]);
            }
        }
        Path home = AppSettingsLoader.defaultHome();
        if (config == null) {
            String fromEnv = System.getenv("SPACE_HARVESTER_APP_CONFIG");
            config = fromEnv == null || fromEnv.isBlank() ? AppSettingsLoader.defaultFile(home) : Path.of(fromEnv);
        }
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home), config, version());
        Runtime.getRuntime().addShutdownHook(new Thread(context::shutdown, "space-harvester-shutdown"));
        if (screenshots != null) {
            CountDownLatch started = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
            started.await();
            Screenshots.render(context, screenshots);
            context.shutdown();
            System.exit(0);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            new MainWindow(context).open();
            context.reload(() -> { });
        });
    }

    /** Version aus dem Manifest des JAR; beim Start aus der IDE {@code dev}. */
    static String version() {
        String version = SpaceHarvesterApp.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static String argument(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " braucht einen Wert");
        }
        return args[index];
    }
}
