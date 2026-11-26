package com.aresstack.spaceharvester.deck.files.sharepoint.windows;

import com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin;

import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Anmeldung an SharePoint über den Windows-Dienst „WebClient“ wie {@code SharePointAuthenticator} in MainframeMate:
 * erst {@code Files.exists} (das System hat schon eine Sitzung), dann {@code net use <Pfad>} ohne Kennwort (Windows
 * schickt die Anmeldung des Benutzers), mit Kennwort {@code net use <Pfad> /user:<Benutzer>} – das Kennwort geht über
 * die Standardeingabe, nie über die Befehlszeile.
 */
public final class NetUseLogin implements WebDavLogin {

    /** Vorgabe: so lange darf „net use“ dauern; die App stellt die Frist ein ({@code DeckTimeouts}). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final boolean windows;
    private final java.util.function.Supplier<Duration> timeout;

    public NetUseLogin() {
        this(() -> DEFAULT_TIMEOUT);
    }

    /** @param timeout Frist für „net use“, bei jedem Aufruf neu gelesen */
    public NetUseLogin(java.util.function.Supplier<Duration> timeout) {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), timeout);
    }

    NetUseLogin(boolean windows) {
        this(windows, () -> DEFAULT_TIMEOUT);
    }

    NetUseLogin(boolean windows, java.util.function.Supplier<Duration> timeout) {
        this.windows = windows;
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Outcome connect(String uncPath) {
        if (exists(uncPath)) {
            return Outcome.ok();
        }
        if (!windows) {
            return Outcome.failed("SharePoint über WebDAV geht nur unter Windows (Dienst „WebClient“).");
        }
        Run run = netUse(List.of("net", "use", root(uncPath)), null, timeout.get());
        if (run.ok() || exists(uncPath)) {
            return Outcome.ok();
        }
        if (transportFailure(run.exit(), run.output())) {
            return Outcome.failed(run.reason("SharePoint nicht erreichbar"));
        }
        return Outcome.credentialsNeeded(run.reason("Die Windows-Anmeldung reicht nicht"));
    }

    @Override
    public Outcome connect(String uncPath, String user, char[] password) {
        if (!windows) {
            return Outcome.failed("SharePoint über WebDAV geht nur unter Windows (Dienst „WebClient“).");
        }
        if (user == null || user.isBlank()) {
            return Outcome.credentialsNeeded("Bitte einen Benutzer angeben.");
        }
        List<String> command = new ArrayList<>(List.of("net", "use", root(uncPath), "*", "/user:" + user.strip()));
        Run run = netUse(command, password, timeout.get());
        if (run.ok() || exists(uncPath)) {
            return Outcome.ok();
        }
        // Nur eine echte Ablehnung zählt als falsche Zugangsdaten (Sperre gegen die Kontosperre); Zeitüberschreitung,
        // Startfehler und Netzfehler nicht.
        if (transportFailure(run.exit(), run.output())) {
            return Outcome.failed(run.reason("SharePoint nicht erreichbar"));
        }
        return Outcome.credentialsNeeded(run.reason("Anmeldung abgelehnt"));
    }

    /** Windows-Fehler, die das Netz meinen, nicht die Anmeldung (Pfad/Name nicht gefunden, Netz weg, WebClient aus). */
    private static final java.util.regex.Pattern TRANSPORT = java.util.regex.Pattern.compile(
            "\\b(51|53|64|67|121|1203|1222|1231|2250)\\b");

    /**
     * Ob ein gescheitertes {@code net use} am Transport lag statt an den Zugangsdaten: kein Prozess, keine Antwort
     * (Exit -1) oder ein Netzfehler laut Ausgabe ({@code Systemfehler 53} usw.).
     */
    static boolean transportFailure(int exit, String output) {
        return exit < 0 || (output != null && TRANSPORT.matcher(output).find());
    }

    /** {@code \\host@SSL\DavWWWRoot} – verbunden wird der Rechner, nicht der einzelne Ordner. */
    static String root(String uncPath) {
        int dav = uncPath.toLowerCase(Locale.ROOT).indexOf("\\davwwwroot");
        return dav < 0 ? uncPath : uncPath.substring(0, dav + "\\DavWWWRoot".length());
    }

    private static boolean exists(String uncPath) {
        try {
            return Files.exists(Path.of(uncPath));
        } catch (InvalidPathException | SecurityException e) {
            return false;
        }
    }

    private record Run(int exit, String output) {

        boolean ok() {
            return exit == 0;
        }

        String reason(String fallback) {
            String text = output == null ? "" : output.strip();
            if (text.isEmpty()) {
                return fallback + ".";
            }
            String first = text.lines().filter(l -> !l.isBlank()).findFirst().orElse(text);
            return fallback + ": " + first.strip();
        }
    }

    private static Run netUse(List<String> command, char[] password, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (OutputStream in = process.getOutputStream()) {
                if (password != null) {
                    byte[] bytes = new String(password).getBytes(Charset.defaultCharset());
                    in.write(bytes);
                    in.write(System.lineSeparator().getBytes(Charset.defaultCharset()));
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            }
            // Ausgabe nebenher lesen: Hängt „net use“ mit offener Ausgabe, greift trotzdem die Zeitgrenze.
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            Thread.ofVirtual().name("net-use-output").start(() -> {
                try (InputStream out = process.getInputStream()) {
                    output.complete(out.readAllBytes());
                } catch (IOException e) {
                    output.complete(new byte[0]);
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "keine Antwort von „net use“");
            }
            byte[] text;
            try {
                text = output.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                text = new byte[0];
            }
            return new Run(process.exitValue(), new String(text, Charset.defaultCharset()));
        } catch (IOException e) {
            return new Run(-1, "„net use“ ließ sich nicht starten (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "abgebrochen");
        }
    }
}
