package com.aresstack.spaceharvester.app;

import com.aresstack.spaceharvester.app.logging.ConsoleLogging;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.WindowsLogin;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.MainWindow;
import com.aresstack.spaceharvester.app.ui.Screenshots;
import com.aresstack.spaceharvester.source.network.ProxyAuthenticator;

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
        // Vor jeder Meldung: java.util.logging und System.Logger durch Logback, das Terminal still, bis die Einstellung
        // „Terminal-Ausgabe“ gelesen ist; die Protokolldatei bekommt alles.
        ConsoleLogging.install();
        // Das JDK liest die Freigabe von BASIC im HTTPS-Tunnel nur beim ersten Verbindungsaufbau; eine später (nach
        // geänderten Einstellungen) eingerichtete Proxy-Anmeldung braucht sie deshalb schon jetzt (#189).
        ProxyAuthenticator.allowBasicTunneling();
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
        // Rechtsklick-Menü (Ausschneiden, Kopieren, Einfügen, Alles markieren) für jedes Textfeld der App.
        com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.install();
        Path home = AppSettingsLoader.defaultHome();
        if (config == null) {
            String fromEnv = System.getenv("SPACE_HARVESTER_APP_CONFIG");
            config = fromEnv == null || fromEnv.isBlank() ? AppSettingsLoader.defaultFile(home) : Path.of(fromEnv);
        }
        AppSettingsLoader loader = new AppSettingsLoader(Placeholders.system(), home);
        if (WindowsLogin.available()) {
            WindowsLogin.configureAtStartup(loader.load(config));
        }
        AppContext context = new AppContext(loader, config, version());
        // Auch beim Beenden von außen (Abmelden, Strg+C) mit Frist; nach AppExit#exit kehrt der Hook sofort zurück.
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> AppExit.cleanUp(java.util.List.of(context::shutdown), AppExit.timeout()),
                "space-harvester-shutdown"));
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
            javax.swing.JFrame frame = new MainWindow(context).open();
            context.reload(() -> MainWindow.offerSetup(frame, context));
        });
    }

    /** Version aus dem Manifest des JAR; beim Start aus der IDE {@code dev}. */
    public static String version() {
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
