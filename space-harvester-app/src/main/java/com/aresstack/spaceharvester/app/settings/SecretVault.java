package com.aresstack.spaceharvester.app.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hält Token und Passwörter der Quellen außerhalb der Konfigurationsdatei: unter Windows je Quelle eine Datei, mit
 * DPAPI für das angemeldete Windows-Konto verschlüsselt ({@code ConvertFrom-SecureString} in PowerShell, ohne
 * native Bibliothek). Nur dieses Konto auf diesem Rechner kann sie wieder lesen; in Logs, Exporten und der Oberfläche
 * erscheint der Wert nie. Ohne Windows gibt es keinen Tresor: Eingaben gelten dann nur bis zum Schließen der App.
 *
 * <p>Der Klartext geht nur über die Standardeingabe an PowerShell (nie über die Befehlszeile, die andere Prozesse
 * sehen können) und als Base64 von UTF-8, damit Umlaute die Codepage nicht stören.
 */
public final class SecretVault {

    /** Führt ein PowerShell-Skript aus und gibt seine Ausgabe zurück. */
    public interface Shell {
        String run(String script, String input) throws IOException;
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final String SUFFIX = ".secret";
    private static final String PROTECT = """
            $ErrorActionPreference = 'Stop'
            $plain = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()))
            $secure = ConvertTo-SecureString -String $plain -AsPlainText -Force
            $plain = $null
            [Console]::Out.Write((ConvertFrom-SecureString -SecureString $secure))
            """;
    private static final String UNPROTECT = """
            $ErrorActionPreference = 'Stop'
            $secure = ConvertTo-SecureString -String ([Console]::In.ReadToEnd().Trim())
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try {
              $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
              [Console]::Out.Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($plain)))
            } finally {
              [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
            """;

    private final Path directory;
    private final Shell shell;

    /** @param shell {@code null}: kein Tresor (ohne Windows) */
    public SecretVault(Path directory, Shell shell) {
        this.directory = directory;
        this.shell = shell;
    }

    /** Der Tresor dieses Rechners: unter Windows mit PowerShell, sonst keiner. */
    public static SecretVault forPlatform(Path directory) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return new SecretVault(directory, windows ? SecretVault::powershell : null);
    }

    /** Ob Werte über das Schließen der App hinaus gespeichert werden können. */
    public boolean available() {
        return shell != null && directory != null;
    }

    public boolean has(String sourceId) {
        return available() && Files.isRegularFile(file(sourceId));
    }

    /** Verschlüsselt und speichert den Wert; {@code secret} wird danach überschrieben. */
    public void store(String sourceId, char[] secret) throws IOException {
        try {
            if (!available()) {
                throw new IOException("Unter diesem Betriebssystem gibt es keinen Tresor für Zugangsdaten.");
            }
            String encrypted = shell.run(PROTECT, base64(secret)).trim();
            if (encrypted.isEmpty()) {
                throw new IOException("PowerShell hat den Wert nicht verschlüsselt.");
            }
            Files.createDirectories(directory);
            Path target = file(sourceId);
            Path temp = Files.createTempFile(directory, sourceId, ".tmp");
            try {
                Files.writeString(temp, encrypted, StandardCharsets.US_ASCII);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** @return der entschlüsselte Wert (der Aufrufer überschreibt ihn nach Gebrauch) oder {@code null} */
    public char[] load(String sourceId) throws IOException {
        if (!has(sourceId)) {
            return null;
        }
        String encoded = shell.run(UNPROTECT, Files.readString(file(sourceId), StandardCharsets.US_ASCII)).trim();
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try {
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
            char[] result = Arrays.copyOf(chars.array(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return result;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public void remove(String sourceId) throws IOException {
        if (directory != null && ID.matcher(sourceId).matches()) {
            Files.deleteIfExists(file(sourceId));
        }
    }

    /** Für die Oberfläche: wo und wie gespeichert wird. */
    public String describe() {
        return available() ? "verschlüsselt für dieses Windows-Konto (DPAPI) in " + directory
                : "nur für diese Sitzung (ohne Windows kein Tresor)";
    }

    private Path file(String sourceId) {
        if (!ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("Ungültige Quellen-ID für den Tresor: " + sourceId);
        }
        return directory.resolve(sourceId + SUFFIX);
    }

    private static String base64(char[] secret) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] raw = Arrays.copyOf(bytes.array(), bytes.limit());
        Arrays.fill(bytes.array(), (byte) 0);
        try {
            return Base64.getEncoder().encodeToString(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static String powershell(String script, String input) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)).redirectErrorStream(false).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(input.getBytes(StandardCharsets.US_ASCII));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errors = new Thread(() -> copy(process.getErrorStream(), err), "secret-vault-stderr");
        errors.setDaemon(true);
        errors.start();
        copy(process.getInputStream(), out);
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("PowerShell hat für den Tresor nicht rechtzeitig geantwortet.");
            }
            errors.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Unterbrochen", e);
        }
        if (process.exitValue() != 0) {
            // Die Fehlerausgabe enthält nie den Wert (er kam nur über die Standardeingabe), aber kürzen.
            String detail = err.toString(StandardCharsets.UTF_8).strip();
            throw new IOException("PowerShell-Tresor fehlgeschlagen (Exit " + process.exitValue() + ")"
                    + (detail.isEmpty() ? "" : ": " + detail.lines().findFirst().orElse("")));
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static void copy(InputStream from, ByteArrayOutputStream to) {
        try (InputStream in = from) {
            in.transferTo(to);
        } catch (IOException ignored) {
            // Ende des Prozesses
        }
    }
}
