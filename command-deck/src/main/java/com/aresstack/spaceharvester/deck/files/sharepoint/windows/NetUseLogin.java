package com.aresstack.spaceharvester.deck.files.sharepoint.windows;

import com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin;

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
import java.util.concurrent.TimeUnit;

/**
 * Anmeldung an SharePoint über den Windows-Dienst „WebClient“ wie {@code SharePointAuthenticator} in MainframeMate:
 * erst {@code Files.exists} (das System hat schon eine Sitzung), dann {@code net use <Pfad>} ohne Kennwort (Windows
 * schickt die Anmeldung des Benutzers), mit Kennwort {@code net use <Pfad> /user:<Benutzer>} – das Kennwort geht über
 * die Standardeingabe, nie über die Befehlszeile.
 */
public final class NetUseLogin implements WebDavLogin {

    private static final long TIMEOUT_SECONDS = 60;

    private final boolean windows;

    public NetUseLogin() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    NetUseLogin(boolean windows) {
        this.windows = windows;
    }

    @Override
    public Outcome connect(String uncPath) {
        if (exists(uncPath)) {
            return Outcome.ok();
        }
        if (!windows) {
            return Outcome.failed("SharePoint über WebDAV geht nur unter Windows (Dienst „WebClient“).");
        }
        Run run = netUse(List.of("net", "use", root(uncPath)), null);
        if (run.ok() || exists(uncPath)) {
            return Outcome.ok();
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
        Run run = netUse(command, password);
        if (run.ok() || exists(uncPath)) {
            return Outcome.ok();
        }
        return Outcome.credentialsNeeded(run.reason("Anmeldung abgelehnt"));
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

    private static Run netUse(List<String> command, char[] password) {
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
            byte[] output;
            try (InputStream out = process.getInputStream()) {
                output = out.readAllBytes();
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "keine Antwort von „net use“");
            }
            return new Run(process.exitValue(), new String(output, Charset.defaultCharset()));
        } catch (IOException e) {
            return new Run(-1, "„net use“ ließ sich nicht starten (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "abgebrochen");
        }
    }
}
