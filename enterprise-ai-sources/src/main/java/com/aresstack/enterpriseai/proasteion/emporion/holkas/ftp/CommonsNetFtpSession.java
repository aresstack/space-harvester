package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FTP-Sitzung über Apache Commons Net. Übernommen aus MainframeMate {@code CommonsNetFtpFileService} (Anmeldung,
 * MVS-Erkennung über {@code SYST}, doppelte MVS-Auflistung, Satzstruktur, Entfernen der Füllbytes {@code 00}), ohne
 * Schreibzugriff, Einstellungs-Singleton und Konsolenausgaben. Zugangsdaten werden nur beim Öffnen gebraucht und
 * nicht gehalten.
 */
final class CommonsNetFtpSession implements FtpClientSession {

    private static final byte PADDING = 0x00;

    private final FTPClient client = new FTPClient();
    private final FtpConnectionSettings settings;
    private final MvsPathDialect dialect = new MvsPathDialect();
    private boolean mvs;
    private boolean recordStructure;
    private boolean closed;

    private CommonsNetFtpSession(FtpConnectionSettings settings) {
        this.settings = settings;
    }

    /**
     * Verbindet und meldet an.
     *
     * @throws FtpLoginException wenn der Server die Anmeldung ablehnt
     */
    static CommonsNetFtpSession open(FtpConnectionSettings settings, String user, char[] password)
            throws IOException {
        CommonsNetFtpSession session = new CommonsNetFtpSession(settings);
        try {
            session.connect(user, password);
            return session;
        } catch (IOException | RuntimeException e) {
            session.close();
            throw e;
        }
    }

    private void connect(String user, char[] password) throws IOException {
        client.setControlEncoding(settings.encoding.name());
        client.setConnectTimeout(settings.connectTimeoutMillis);
        client.setDefaultTimeout(settings.connectTimeoutMillis);
        client.connect(settings.host, settings.port);
        client.setSoTimeout(settings.readTimeoutMillis);
        client.setDataTimeout(Duration.ofMillis(settings.readTimeoutMillis));
        if (!client.login(user, new String(password))) {
            throw new FtpLoginException("FTP-Anmeldung abgelehnt (" + client.getReplyCode() + ")");
        }
        client.enterLocalPassiveMode();
        String systemType = client.getSystemType();
        String upper = systemType == null ? "" : systemType.toUpperCase(Locale.ROOT);
        mvs = upper.contains("MVS");
        if (mvs) {
            client.configure(new FTPClientConfig(FTPClientConfig.SYST_MVS));
        } else if (upper.contains("WIN32NT")) {
            client.configure(new FTPClientConfig(FTPClientConfig.SYST_NT));
        }
        client.setFileType(FTP.ASCII_FILE_TYPE);
        recordStructure = settings.recordStructure == FtpConnectionSettings.RecordStructure.ON
                || settings.recordStructure == FtpConnectionSettings.RecordStructure.AUTO && mvs;
        client.setFileStructure(recordStructure ? FTP.RECORD_STRUCTURE : FTP.FILE_STRUCTURE);
        client.setFileTransferMode(FTP.STREAM_TRANSFER_MODE);
    }

    @Override
    public boolean mvs() {
        return mvs;
    }

    @Override
    public List<FtpEntry> list(String path) throws IOException {
        return mvs ? listMvs(dialect.toAbsolutePath(path)) : listUnix(unixPath(path));
    }

    @Override
    public String readText(String path) throws IOException {
        List<String> candidates = mvs ? dialect.resolveCandidates(path) : Collections.singletonList(unixPath(path));
        IOException last = null;
        for (String candidate : candidates) {
            try {
                return RecordStructureText.decode(retrieve(candidate), settings.encoding, recordStructure);
            } catch (FtpNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new FtpNotFoundException("nicht gefunden: " + path);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } finally {
                    client.disconnect();
                }
            }
        } catch (IOException e) {
            // Verbindung ist ohnehin weg.
        }
    }

    boolean connected() {
        return !closed && client.isConnected();
    }

    private byte[] retrieve(String resolvedPath) throws IOException {
        InputStream in = client.retrieveFileStream(resolvedPath);
        if (in == null) {
            throw new FtpNotFoundException("nicht gefunden: " + resolvedPath + " (" + client.getReplyCode() + ")");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                // Wie MainframeMate: alle Füllbytes aus dem Textstrom entfernen.
                for (int i = 0; i < read; i++) {
                    if (buffer[i] != PADDING) {
                        out.write(buffer[i]);
                    }
                }
            }
        } finally {
            // Der Datenstrom muss vor completePendingCommand geschlossen sein, sonst wartet der Aufruf auf "226".
            in.close();
        }
        if (!client.completePendingCommand()) {
            throw new IOException("Übertragung unvollständig: " + resolvedPath + " (" + client.getReplyCode() + ")");
        }
        return out.toByteArray();
    }

    private List<FtpEntry> listUnix(String resolved) throws IOException {
        FTPFile[] files = client.listFiles(resolved);
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<FtpEntry> entries = new ArrayList<FtpEntry>(files.length);
        for (FTPFile file : files) {
            String name = file == null ? null : file.getName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            // Eine Datei listet sich selbst: dann hat der Pfad keine Kinder.
            String childPath = resolved.endsWith("/") ? resolved + name : resolved + "/" + name;
            if (files.length == 1 && !file.isDirectory() && resolved.endsWith("/" + name)) {
                return Collections.emptyList();
            }
            Calendar timestamp = file.getTimestamp();
            entries.add(new FtpEntry(name, childPath, file.isDirectory(),
                    timestamp == null ? 0L : timestamp.getTimeInMillis()));
        }
        return entries;
    }

    /**
     * Doppelte Auflistung wie in MainframeMate: {@code NLST 'X'} liefert Member (bzw. passende Datasets),
     * {@code NLST 'X.*'} die Datasets darunter; danach {@code LIST} als Rückfall.
     */
    private List<FtpEntry> listMvs(String resolved) throws IOException {
        if (resolved == null || resolved.isEmpty() || MvsPathDialect.MVS_ROOT.equals(resolved)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<FtpEntry> entries = new ArrayList<FtpEntry>();
        String[] direct = client.listNames(resolved);
        if (direct != null) {
            for (FtpEntry entry : directEntries(resolved, direct)) {
                if (seen.add(entry.path().toUpperCase(Locale.ROOT))) {
                    entries.add(entry);
                }
            }
        }
        String unquoted = MvsQuoteNormalizer.unquote(resolved);
        if (!unquoted.endsWith("*") && unquoted.indexOf('(') < 0) {
            try {
                String[] sub = client.listNames("'" + unquoted + ".*'");
                if (sub != null) {
                    for (FtpEntry entry : subDatasetEntries(resolved, sub)) {
                        if (seen.add(entry.path().toUpperCase(Locale.ROOT))) {
                            entries.add(entry);
                        }
                    }
                }
            } catch (IOException e) {
                // Nicht jeder Pfad hat Datasets darunter.
            }
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        FTPFile[] files = client.listFiles(resolved);
        if (files == null) {
            return entries;
        }
        for (FTPFile file : files) {
            String name = file == null ? null : file.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            String childPath = MvsQuoteNormalizer.unquote(dialect.childOf(resolved, name));
            if (childPath.equalsIgnoreCase(unquoted)) {
                continue;
            }
            Calendar timestamp = file.getTimestamp();
            boolean directory = file.isDirectory() || !childPath.contains("(") && !isMemberName(name);
            if (seen.add(childPath.toUpperCase(Locale.ROOT))) {
                entries.add(new FtpEntry(name, childPath, directory,
                        timestamp == null ? 0L : timestamp.getTimeInMillis()));
            }
        }
        return entries;
    }

    private List<FtpEntry> directEntries(String parent, String[] names) {
        List<FtpEntry> entries = new ArrayList<FtpEntry>(names.length);
        String upperParent = MvsQuoteNormalizer.unquote(parent).toUpperCase(Locale.ROOT);
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            String unquoted = MvsQuoteNormalizer.unquote(name);
            String upper = unquoted.toUpperCase(Locale.ROOT);
            if (upper.equals(upperParent)) {
                continue;
            }
            if (upper.startsWith(upperParent + ".")) {
                entries.add(new FtpEntry(unquoted.substring(upperParent.length() + 1), unquoted, true, 0L));
            } else if (name.startsWith("'")) {
                entries.add(new FtpEntry(unquoted, unquoted, !unquoted.contains("(") && !isMemberName(unquoted), 0L));
            } else {
                String path = MvsQuoteNormalizer.unquote(dialect.childOf(parent, name));
                boolean directory = !name.contains("(") && !isMemberName(name);
                entries.add(new FtpEntry(name, path, directory, 0L));
            }
        }
        return entries;
    }

    private List<FtpEntry> subDatasetEntries(String parent, String[] names) {
        List<FtpEntry> entries = new ArrayList<FtpEntry>(names.length);
        String upperParent = MvsQuoteNormalizer.unquote(parent).toUpperCase(Locale.ROOT);
        for (String raw : names) {
            String unquoted = MvsQuoteNormalizer.unquote(raw == null ? "" : raw.trim());
            String upper = unquoted.toUpperCase(Locale.ROOT);
            if (unquoted.isEmpty() || upper.equals(upperParent)) {
                continue;
            }
            String display = upper.startsWith(upperParent + ".")
                    ? unquoted.substring(upperParent.length() + 1) : unquoted;
            String path = unquoted;
            // Nur die nächste Ebene: unter USR1.TMP wird USR1.TMP.A.B zu A (USR1.TMP.A).
            int dot = display.indexOf('.');
            if (dot > 0 && upper.startsWith(upperParent + ".")) {
                display = display.substring(0, dot);
                path = MvsQuoteNormalizer.unquote(parent) + "." + display;
            }
            entries.add(new FtpEntry(display, path, true, 0L));
        }
        return entries;
    }

    /** Membernamen: 1–8 Zeichen, ohne Punkt und Klammer. */
    private static boolean isMemberName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 8
                && name.indexOf('.') < 0 && name.indexOf('(') < 0 && name.indexOf(')') < 0;
    }

    private static String unixPath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /** Der Server hat die Anmeldung abgelehnt. */
    static final class FtpLoginException extends IOException {
        private static final long serialVersionUID = 1L;

        FtpLoginException(String message) {
            super(message);
        }
    }

    /** Die Datei existiert nicht (oder ist nicht lesbar). */
    static final class FtpNotFoundException extends IOException {
        private static final long serialVersionUID = 1L;

        FtpNotFoundException(String message) {
            super(message);
        }
    }
}
