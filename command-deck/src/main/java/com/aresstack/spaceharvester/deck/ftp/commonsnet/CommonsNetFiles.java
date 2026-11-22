package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.RemoteFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;

/**
 * Datasets und Dateien über Apache Commons Net, übernommen aus MainframeMate ({@code CommonsNetFtpFileService}): MVS
 * wird an der SYST-Antwort erkannt; dann Satzstruktur ({@code STRU R}), Füllbytes {@code 00} fallen beim Lesen weg,
 * und die Liste ist doppelt (Members des Datasets und Datasets darunter, wie ein Ordner unter Windows).
 */
final class CommonsNetFiles implements FileConnection {

    private final FTPClient ftp;
    private final Charset charset;
    private final String user;
    private final boolean mvs;
    private final String systemType;
    private final boolean recordStructure;

    CommonsNetFiles(FtpProfile profile, String user, char[] password) throws IOException {
        this.ftp = Clients.connect(profile, user, password);
        this.charset = profile.charset();
        this.user = user;
        try {
            String system = ftp.getSystemType();
            this.systemType = system == null ? "" : system.trim();
            this.mvs = systemType.toUpperCase(Locale.ROOT).contains("MVS");
            if (mvs) {
                ftp.configure(new FTPClientConfig(FTPClientConfig.SYST_MVS));
            } else if (systemType.toUpperCase(Locale.ROOT).contains("WIN32NT")) {
                ftp.configure(new FTPClientConfig(FTPClientConfig.SYST_NT));
            }
            this.recordStructure = mvs;
            textMode();
        } catch (IOException | RuntimeException e) {
            Clients.close(ftp);
            throw e;
        }
    }

    private void textMode() throws IOException {
        ftp.setFileType(FTP.ASCII_FILE_TYPE);
        ftp.setFileStructure(recordStructure ? FTP.RECORD_STRUCTURE : FTP.FILE_STRUCTURE);
        ftp.setFileTransferMode(FTP.STREAM_TRANSFER_MODE);
    }

    private void binaryMode() throws IOException {
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.setFileStructure(FTP.FILE_STRUCTURE);
    }

    @Override
    public boolean mvs() {
        return mvs;
    }

    @Override
    public String systemType() {
        return systemType;
    }

    @Override
    public String home() {
        if (mvs) {
            // Wie MainframeMate (ftpUseLoginAsHlq): der Anmeldename ist der HLQ.
            return MvsPath.absolute(user.toUpperCase(Locale.ROOT));
        }
        try {
            String pwd = ftp.printWorkingDirectory();
            return pwd == null || pwd.isBlank() ? "/" : pwd;
        } catch (IOException e) {
            return "/";
        }
    }

    @Override
    public List<RemoteEntry> list(String path) throws IOException {
        return mvs ? listMvs(MvsPath.absolute(path)) : listUnix(unixPath(path));
    }

    private List<RemoteEntry> listUnix(String path) throws IOException {
        FTPFile[] files = ftp.listFiles(path);
        List<RemoteEntry> entries = new ArrayList<>();
        if (files == null) {
            return entries;
        }
        for (FTPFile file : files) {
            if (file == null) {
                continue;
            }
            String name = file.getName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            Calendar time = file.getTimestamp();
            entries.add(new RemoteEntry(name, joinUnix(path, name), file.isDirectory(), file.getSize(),
                    time == null ? Optional.empty() : Optional.of(time.toInstant())));
        }
        return entries;
    }

    /**
     * Doppelte Liste wie MainframeMate: {@code NLST 'X'} liefert Members (oder passende Datasets), {@code NLST 'X.*'}
     * die Datasets darunter; zusammengeführt ohne Doppelte. Ist beides leer, {@code LIST 'X'}.
     */
    private List<RemoteEntry> listMvs(String resolved) throws IOException {
        if (resolved.isEmpty() || MvsPath.ROOT.equals(resolved)) {
            throw new FtpReplyException("Die MVS-Wurzel lässt sich nicht auflisten – bitte einen HLQ eingeben", 0);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RemoteEntry> entries = new ArrayList<>();
        String[] direct = ftp.listNames(resolved);
        if (direct != null) {
            for (RemoteEntry entry : directEntries(resolved, direct)) {
                if (seen.add(entry.path().toUpperCase(Locale.ROOT))) {
                    entries.add(entry);
                }
            }
        }
        String unquoted = MvsPath.unquote(resolved);
        if (!unquoted.endsWith("*") && !unquoted.contains("(")) {
            String[] below = ftp.listNames("'" + unquoted + ".*'");
            if (below != null) {
                for (RemoteEntry entry : datasetsBelow(resolved, below)) {
                    if (seen.add(entry.path().toUpperCase(Locale.ROOT))) {
                        entries.add(entry);
                    }
                }
            }
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        FTPFile[] files = ftp.listFiles(resolved);
        if (files != null) {
            for (FTPFile file : files) {
                if (file == null || file.getName() == null || file.getName().isEmpty()) {
                    continue;
                }
                String name = file.getName();
                Calendar time = file.getTimestamp();
                entries.add(new RemoteEntry(name, MvsPath.child(resolved, name),
                        file.isDirectory() || !name.contains("("), file.getSize(),
                        time == null ? Optional.empty() : Optional.of(time.toInstant())));
            }
        }
        return entries;
    }

    /** Einträge aus {@code NLST 'X'}: voll qualifizierte Namen sind Datasets, kurze Namen Members. */
    private static List<RemoteEntry> directEntries(String parent, String[] names) {
        List<RemoteEntry> entries = new ArrayList<>();
        String parentUpper = MvsPath.unquote(parent).toUpperCase(Locale.ROOT);
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            String unquoted = MvsPath.unquote(name);
            if (unquoted.equalsIgnoreCase(parentUpper)) {
                continue;
            }
            if (unquoted.toUpperCase(Locale.ROOT).startsWith(parentUpper + ".")) {
                entries.add(RemoteEntry.directory(unquoted.substring(parentUpper.length() + 1),
                        MvsPath.absolute(unquoted)));
            } else if (name.startsWith("'")) {
                entries.add(new RemoteEntry(unquoted, name, !unquoted.contains("("), -1, Optional.empty()));
            } else if (name.contains("(")) {
                entries.add(RemoteEntry.file(name, MvsPath.child(parent, name)));
            } else if (MvsPath.isMemberName(name)) {
                entries.add(RemoteEntry.file(name, MvsPath.child(parent, name)));
            } else {
                entries.add(RemoteEntry.directory(name, MvsPath.child(parent, name)));
            }
        }
        return entries;
    }

    /** Einträge aus {@code NLST 'X.*'}: immer Datasets, angezeigt wird nur die nächste Stufe. */
    private static List<RemoteEntry> datasetsBelow(String parent, String[] names) {
        List<RemoteEntry> entries = new ArrayList<>();
        String parentUpper = MvsPath.unquote(parent).toUpperCase(Locale.ROOT);
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String unquoted = MvsPath.unquote(raw.trim());
            if (unquoted.equalsIgnoreCase(parentUpper)) {
                continue;
            }
            String display = unquoted.toUpperCase(Locale.ROOT).startsWith(parentUpper + ".")
                    ? unquoted.substring(parentUpper.length() + 1) : unquoted;
            String path = MvsPath.absolute(unquoted);
            int dot = display.indexOf('.');
            if (dot > 0) {
                display = display.substring(0, dot);
                path = MvsPath.absolute(parentUpper + "." + display);
            }
            entries.add(RemoteEntry.directory(display, path));
        }
        return entries;
    }

    @Override
    public RemoteFile read(String path) throws IOException {
        FtpReplyException last = null;
        for (String candidate : candidates(path)) {
            try {
                byte[] bytes = RecordStructure.withoutPadding(retrieve(candidate));
                String text = recordStructure ? RecordStructure.decode(bytes, charset) : new String(bytes, charset);
                return RemoteFile.of(candidate, text);
            } catch (FtpReplyException refused) {
                last = refused;
            }
        }
        throw last != null ? last : new FtpReplyException("Nicht gefunden: " + path, 550);
    }

    @Override
    public byte[] readBinary(String path) throws IOException {
        binaryMode();
        try {
            FtpReplyException last = null;
            for (String candidate : candidates(path)) {
                try {
                    return retrieve(candidate);
                } catch (FtpReplyException refused) {
                    last = refused;
                }
            }
            throw last != null ? last : new FtpReplyException("Nicht gefunden: " + path, 550);
        } finally {
            textMode();
        }
    }

    /**
     * Holt eine Datei. Der Datenstrom wird vor {@code completePendingCommand} geschlossen, sonst wartet der Client
     * endlos auf „226 Transfer complete“ (MainframeMate).
     */
    private byte[] retrieve(String path) throws IOException {
        InputStream in = ftp.retrieveFileStream(path);
        if (in == null) {
            throw new FtpReplyException("Nicht lesbar: " + path + " – " + Clients.reply(ftp), ftp.getReplyCode());
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        }
        if (!ftp.completePendingCommand()) {
            throw new FtpReplyException("Übertragung unvollständig: " + path + " – " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
        return bytes;
    }

    @Override
    public void write(String path, String text) throws IOException {
        byte[] bytes = recordStructure ? RecordStructure.encode(text, charset)
                : (text == null ? "" : text).getBytes(charset);
        store(path, bytes);
    }

    @Override
    public void writeBinary(String path, byte[] content) throws IOException {
        binaryMode();
        try {
            store(path, content);
        } finally {
            textMode();
        }
    }

    private void store(String path, byte[] bytes) throws IOException {
        FtpReplyException last = null;
        for (String candidate : candidates(path)) {
            if (ftp.storeFile(candidate, new ByteArrayInputStream(bytes))) {
                return;
            }
            last = new FtpReplyException("Schreiben abgelehnt: " + candidate + " – " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
        throw last != null ? last : new FtpReplyException("Schreiben abgelehnt: " + path, 550);
    }

    @Override
    public void delete(String path) throws IOException {
        String resolved = mvs ? MvsPath.absolute(path) : unixPath(path);
        if (!ftp.deleteFile(resolved) && !ftp.removeDirectory(resolved)) {
            throw new FtpReplyException("Löschen abgelehnt: " + resolved + " – " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
    }

    @Override
    public void createDirectory(String path) throws IOException {
        String resolved = mvs ? MvsPath.absolute(path) : unixPath(path);
        if (!ftp.makeDirectory(resolved)) {
            throw new FtpReplyException("Anlegen abgelehnt: " + resolved + " – " + Clients.reply(ftp),
                    ftp.getReplyCode());
        }
    }

    @Override
    public boolean alive() {
        return ftp.isConnected();
    }

    @Override
    public void close() {
        Clients.logoutAndClose(ftp);
    }

    private List<String> candidates(String path) {
        return mvs ? MvsPath.candidates(path) : List.of(unixPath(path));
    }

    private static String unixPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String joinUnix(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }
}
