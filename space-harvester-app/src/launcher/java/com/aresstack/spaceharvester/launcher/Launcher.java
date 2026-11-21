package com.aresstack.spaceharvester.launcher;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.JOptionPane;

/**
 * Einstieg des ausführbaren App-JARs (Main-Class im Manifest).
 *
 * <p>Die App ist für Java 21 übersetzt. Diese Klasse und {@link JavaRuntimeFinder} sind <em>für Java 8</em>
 * übersetzt und hängen zur Übersetzungszeit von nichts in der App ab: Eine Klassendatei trägt ihre eigene
 * Version, und eine JVM prüft nur die Version der Klassen, die sie lädt. Mit Java 21 oder neuer gestartet,
 * startet der Launcher die App in derselben JVM über den Loader von Spring Boot (der liest {@code Start-Class}
 * aus dem Manifest und findet die verschachtelten JARs). Mit einem älteren Java gestartet, sucht er ein Java 21
 * auf diesem Rechner und startet damit dasselbe JAR mit denselben Argumenten (etwa {@code --config}). So läuft
 * das JAR mit einem schlichten {@code java -jar} oder per Doppelklick, egal mit welchem Java der Rechner JARs
 * startet. Der Code ist eine Kopie des Launchers von Audio Studio (aresstack/audio, dort aus 3d4j) mit eigenen
 * Einstellungen; zur Laufzeit teilen die Programme nichts.</p>
 *
 * <p>Das zu verwendende Java 21 lässt sich mit der Systemeigenschaft {@code space-harvester.java} oder der
 * Umgebungsvariable {@code SPACE_HARVESTER_JAVA} nennen (sein Ordner oder seine {@code java}-Datei).</p>
 *
 * <p>Die Spracherkennung rechnet mit der Vector API von Java ({@code jdk.incubator.vector}) etwa doppelt so schnell.
 * Fehlt das Modul in der laufenden JVM, startet der Launcher dasselbe Java einmal mit
 * {@code --add-modules jdk.incubator.vector} neu; {@code -Dspace-harvester.simd=false} unterdrückt das.</p>
 */
public final class Launcher {
    /** Die Java-Version, für die die App übersetzt ist. */
    private static final int REQUIRED_JAVA = 21;
    /** Der Loader von Spring Boot; er startet {@code Start-Class} aus dem Manifest. */
    private static final String MAIN = "org.springframework.boot.loader.launch.JarLauncher";
    private static final String RELAUNCHED_PROPERTY = "space-harvester.relaunched";
    /** Das Modul der Vector API; Whisper nutzt es für SIMD und rechnet ohne es skalar. */
    private static final String VECTOR_MODULE = "jdk.incubator.vector";

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            fail(t.toString(), true);
        }
    }

    private static void run(String[] args) throws Throwable {
        int java = JavaRuntimeFinder.majorOf(System.getProperty("java.specification.version"));
        if (java < REQUIRED_JAVA) {
            if (Boolean.getBoolean(RELAUNCHED_PROPERTY)) {
                throw new IllegalStateException("Das für den Neustart gefundene Java ist Java " + java);
            }
            File memory = new File(new File(System.getProperty("user.home"), ".space-harvester"),
                    "launcher.properties");
            File found = new JavaRuntimeFinder(REQUIRED_JAVA, memory).find();
            if (found == null) {
                fail("Space Harvester braucht Java " + REQUIRED_JAVA + ", gestartet wurde es mit Java " + java + ",\n"
                        + "und auf diesem Rechner wurde kein Java " + REQUIRED_JAVA + " gefunden.\n\n"
                        + "Gesucht wurde in JAVA_HOME, im PATH und in den üblichen Installationsordnern.\n"
                        + "Ein Java " + REQUIRED_JAVA + " an anderer Stelle nennt die Umgebungsvariable "
                        + "SPACE_HARVESTER_JAVA\n(oder -Dspace-harvester.java=...).", false);
            }
            // Fehler der zweiten JVM meldet diese selbst; nur ihr Exit-Code wird weitergegeben.
            System.exit(relaunch(sameKind(found), args));
        }
        if (!Boolean.getBoolean(RELAUNCHED_PROPERTY) && !"false".equalsIgnoreCase(System.getProperty("space-harvester.simd"))
                && !vectorModuleLoaded()) {
            String own = ownCommand();
            if (own != null) {
                System.exit(relaunch(own, args));
            }
        }
        Class<?> mainClass = Class.forName(MAIN, true, Launcher.class.getClassLoader());
        Method main = mainClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Meldet einen fatalen Fehler und beendet. Ein per Doppelklick gestartetes JAR läuft auf {@code javaw}
     * ohne Konsole, deshalb steht die Meldung dort zusätzlich in einem Dialog.
     */
    private static void fail(String message, boolean alreadyPrinted) {
        if (!alreadyPrinted) {
            System.err.println(message);
        }
        if (windowless() && !GraphicsEnvironment.isHeadless()) {
            try {
                JOptionPane.showMessageDialog(null, message, "Space Harvester", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // Kein Bildschirm: Die Meldung auf stderr muss reichen.
            }
        }
        System.exit(1);
    }

    /** Startet ein anderes Java mit diesem JAR und denselben Argumenten und wartet auf sein Ende. */
    private static int relaunch(String executable, String[] args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Ein anderes Java kennt die Optionen dieses vielleicht nicht, Systemeigenschaften nimmt es immer.
            if (jvmArg.startsWith("-D")) {
                command.add(jvmArg);
            }
        }
        command.add("--add-modules=" + VECTOR_MODULE);
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Launcher.class.getName());
        command.addAll(Arrays.asList(args));

        final Process child = new ProcessBuilder(command).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                child.destroy();
            }
        }, "space-harvester-launcher-cleanup"));
        return child.waitFor();
    }

    /** Ob die Vector API in dieser JVM geladen ist; reflektiv, weil der Launcher für Java 8 übersetzt ist. */
    private static boolean vectorModuleLoaded() {
        try {
            Class<?> layerClass = Class.forName("java.lang.ModuleLayer");
            Object boot = layerClass.getMethod("boot").invoke(null);
            Object module = layerClass.getMethod("findModule", String.class).invoke(boot, VECTOR_MODULE);
            return (Boolean) module.getClass().getMethod("isPresent").invoke(module);
        } catch (Throwable notAvailable) {
            return true; // Ohne Modulsystem gibt es nichts nachzuladen; nicht endlos neu starten.
        }
    }

    /** Für ein gefundenes Java: javaw.exe, wenn diese JVM ohne Konsole läuft, damit sich keine öffnet. */
    private static String sameKind(File java) {
        if (windowless()) {
            File javaw = new File(java.getParentFile(), "javaw.exe");
            if (javaw.isFile()) {
                return javaw.getPath();
            }
        }
        return java.getPath();
    }

    /** Ob diese JVM ohne Konsole gestartet wurde, wie es ein Doppelklick auf ein JAR tut. */
    private static boolean windowless() {
        String command = ownCommand();
        if (command != null) {
            return command.toLowerCase(Locale.ROOT).matches(".*javaw(\\.exe)?");
        }
        // Java 8 kann nicht sagen, womit es gestartet wurde: Unter Windows ist fehlende Konsole das Zeichen.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && System.console() == null;
    }

    /** Der Pfad der ausführbaren Datei dieser JVM; {@code null} unter Java 8, das kein ProcessHandle hat. */
    private static String ownCommand() {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object info = handleClass.getMethod("info").invoke(handleClass.getMethod("current").invoke(null));
            Object command = Class.forName("java.lang.ProcessHandle$Info").getMethod("command").invoke(info);
            Object path = command.getClass().getMethod("orElse", Object.class).invoke(command, (Object) null);
            return path == null ? null : path.toString();
        } catch (Throwable notAvailable) {
            return null;
        }
    }
}
