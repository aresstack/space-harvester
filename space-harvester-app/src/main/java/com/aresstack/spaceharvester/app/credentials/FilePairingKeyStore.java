package com.aresstack.spaceharvester.app.credentials;

import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingKeyStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kopie von EAC {@code FilePairingKeyStore} (a64d004b), unverändert bis auf das Paket und {@code SecretChars}
 * als private Methode. Dauerhafte Ablage des KeePassRPC-Pairing-Schlüssels in einer Datei im Benutzerverzeichnis, damit nach einem
 * Neustart kein neues Pairing nötig ist (MainframeMate hielt ihn in den Settings).
 *
 * <p>Schutz: Die Datei wird mit Rechten nur für den Besitzer angelegt ({@code rw-------} auf POSIX; auf Windows
 * die Lese-/Schreibrechte über {@code java.io.File}, soweit das Dateisystem sie umsetzt), atomar ersetzt und beim
 * Verwerfen vor dem Löschen überschrieben. Der Schlüssel erscheint nie in Logs; Fehler werden ohne Inhalt
 * geloggt und machen das Pairing für diese Sitzung nicht unbrauchbar (Speicherkopie bleibt beim Adapter).
 */
public final class FilePairingKeyStore implements KeePassPairingKeyStore {

    private static final Logger LOG = Logger.getLogger(FilePairingKeyStore.class.getName());
    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> OWNER_ONLY = java.util.Collections.unmodifiableSet(
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    private final Path file;

    public FilePairingKeyStore(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file.toAbsolutePath();
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized char[] load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Pairing-Schlüssel nicht lesbar: " + file + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        try {
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
            char[] all = new char[chars.remaining()];
            chars.get(all);
            char[] trimmed = trimmedCopy(all);
            Arrays.fill(all, '\0');
            clearBuffer(chars);
            return trimmed.length == 0 ? null : trimmed;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public synchronized void save(char[] sessionKey) {
        if (sessionKey == null || sessionKey.length == 0) {
            clear();
            return;
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(sessionKey));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        clearBuffer(encoded);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(temp);
            try (OutputStream out = createOwnerOnly(temp)) {
                out.write(bytes);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Pairing-Schlüssel konnte nicht gespeichert werden: " + file + " ("
                    + e.getClass().getSimpleName() + "); nach dem nächsten Start ist ein neues Pairing nötig");
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public synchronized void clear() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            long size = Files.size(file);
            if (size > 0 && size < 1024 * 1024) {
                Files.write(file, new byte[(int) size], StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ignored) {
            // Überschreiben ist best effort; gelöscht wird in jedem Fall
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Pairing-Schlüssel konnte nicht gelöscht werden: " + file + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Legt die Datei neu an: auf POSIX-Systemen bereits mit {@code rw-------} als Attribut der Erzeugung, sodass sie
     * zu keinem Zeitpunkt mit weiteren Rechten existiert (die umask kann nur Rechte entfernen); sonst werden die
     * Rechte unmittelbar nach dem Anlegen und vor dem ersten geschriebenen Byte eingeschränkt.
     */
    private static OutputStream createOwnerOnly(Path path) throws IOException {
        if (POSIX_SUPPORTED) {
            Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return Channels.newOutputStream(Files.newByteChannel(path, options,
                    PosixFilePermissions.asFileAttribute(EnumSet.copyOf(OWNER_ONLY))));
        }
        OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        restrictToOwner(path);
        return out;
    }

    /** Rechte nur für den Besitzer, soweit das Dateisystem es erlaubt; schlägt nie fehl. */
    static void restrictToOwner(Path path) {
        if (POSIX_SUPPORTED) {
            try {
                Files.setPosixFilePermissions(path, EnumSet.copyOf(OWNER_ONLY));
                return;
            } catch (IOException | UnsupportedOperationException e) {
                LOG.log(Level.FINE, "POSIX-Rechte nicht setzbar: " + e.getClass().getSimpleName());
            }
        }
        java.io.File file = path.toFile();
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    /** Aus EAC {@code SecretChars}. */
    private static char[] trimmedCopy(char[] chars) {
        int start = 0;
        int end = chars.length;
        while (start < end && Character.isWhitespace(chars[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(chars[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(chars, start, end);
    }

    private static void clearBuffer(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
    }

    private static void clearBuffer(CharBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
    }

    @Override
    public String toString() {
        return "FilePairingKeyStore[" + file + ", ***]";
    }
}
