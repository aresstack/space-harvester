package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.MvsListing;
import com.aresstack.spaceharvester.deck.ftp.TransferOptions;
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
    private final TransferOptions transfer;
    private final String startHlq;

    CommonsNetFiles(FtpProfile profile, String user, char[] password) throws IOException {
        this.ftp = Clients.connect(profile, user, password);
        this.charset = profile.charset();
        this.user = user;
        this.transfer = profile.transfer();
        this.startHlq = profile.startHlq();
        try {
            String system = ftp.getSystemType();
            this.systemType = system == null ? "" : system.trim();
            this.mvs = systemType.toUpperCase(Locale.ROOT).contains("MVS");
            if (mvs) {
                ftp.configure(new FTPClientConfig(FTPClientConfig.SYST_MVS));
            } else if (systemType.toUpperCase(Locale.ROOT).contains("WIN32NT")) {
                ftp.configure(new FTPClientConfig(FTPClientConfig.SYST_NT));
            }
            this.recordStructure = transfer.recordStructure(mvs);
            textMode();
        } catch (IOException | RuntimeException e) {
            Clients.close(ftp);
            throw e;
        }
    }

    /** Übertragungsart laut Profil, wie {@code applyTransferSettings} in MainframeMate. */
    private void textMode() throws IOException {
        int type = switch (transfer.fileType()) {
            case ASCII -> FTP.ASCII_FILE_TYPE;
            case EBCDIC -> FTP.EBCDIC_FILE_TYPE;
            case BINARY -> FTP.BINARY_FILE_TYPE;
            case LOCAL -> FTP.LOCAL_FILE_TYPE;
        };
        if (transfer.textFormat() == TransferOptions.TextFormat.DEFAULT) {
            ftp.setFileType(type);
        } else {
            ftp.setFileType(type, switch (transfer.textFormat()) {
                case TELNET -> FTP.TELNET_TEXT_FORMAT;
                case CARRIAGE_CONTROL -> FTP.CARRIAGE_CONTROL_TEXT_FORMAT;
                default -> FTP.NON_PRINT_TEXT_FORMAT;
            });
        }
        ftp.setFileStructure(switch (transfer.structure()) {
            case PAGE -> FTP.PAGE_STRUCTURE;
            case AUTO, RECORD, FILE -> recordStructure ? FTP.RECORD_STRUCTURE : FTP.FILE_STRUCTURE;
        });
        ftp.setFileTransferMode(switch (transfer.transferMode()) {
            case BLOCK -> FTP.BLOCK_TRANSFER_MODE;
            case COMPRESSED -> FTP.COMPRESSED_TRANSFER_MODE;
            case STREAM -> FTP.STREAM_TRANSFER_MODE;
        });
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
            // Wie MainframeMate: „Login-Namen als HLQ verwenden“, sonst der eingestellte HLQ.
            return MvsPath.absolute(startHlq.isEmpty() ? user.toUpperCase(Locale.ROOT) : startHlq);
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

    @Override
    public List<RemoteEntry> list(String path, boolean details) throws IOException {
        List<RemoteEntry> entries = list(path);
        return details && mvs ? withDetails(MvsPath.absolute(path), entries) : entries;
    }

    /**
     * Ergänzt RECFM, LRECL, Dsorg und Datum der Datasets ({@code LIST 'X.*'}) und Zeilen, Datum und Benutzer der
     * Members ({@code LIST 'X'}). Was der Host nicht liefert, bleibt leer; die Liste selbst ändert sich nicht.
     */
    private List<RemoteEntry> withDetails(String resolved, List<RemoteEntry> entries) {
        String unquoted = MvsPath.unquote(resolved).toUpperCase(Locale.ROOT);
        java.util.Map<String, MvsListing.Line> datasets = new java.util.HashMap<>();
        java.util.Map<String, MvsListing.Line> members = new java.util.HashMap<>();
        if (entries.stream().anyMatch(entry -> !MvsPath.isMember(entry.path()))) {
            for (String raw : rawList("'" + unquoted + ".*'")) {
                MvsListing.dataset(raw).ifPresent(line -> datasets.put(line.name(), line));
            }
        }
        if (entries.stream().anyMatch(entry -> MvsPath.isMember(entry.path()))) {
            for (String raw : rawList("'" + unquoted + "'")) {
                MvsListing.member(raw).ifPresent(line -> members.put(line.name(), line));
            }
        }
        List<RemoteEntry> result = new ArrayList<>(entries.size());
        for (RemoteEntry entry : entries) {
            MvsListing.Line line;
            if (MvsPath.isMember(entry.path())) {
                line = members.get(MvsPath.splitMember(entry.path())[1].toUpperCase(Locale.ROOT));
            } else {
                // Eine bloße Qualifier-Stufe steht nicht in der Liste und bleibt ohne Angaben.
                line = datasets.get(MvsPath.unquote(entry.path()).toUpperCase(Locale.ROOT));
            }
            result.add(line == null ? entry : entry.withDetails(line.details(), -1, line.modified()));
        }
        return result;
    }

    /** Die rohen Zeilen von {@code LIST}; leer, wenn der Host ablehnt. */
    private List<String> rawList(String argument) {
        List<String> lines = new ArrayList<>();
        try {
            FTPFile[] files = ftp.listFiles(argument);
            if (files != null) {
                for (FTPFile file : files) {
                    if (file != null && file.getRawListing() != null) {
                        lines.add(file.getRawListing());
                    }
                }
            }
        } catch (IOException ignored) {
            // Details sind Beiwerk; die Liste steht auch ohne sie.
        }
        return lines;
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
                List<RemoteEntry> datasets = datasetsBelow(resolved, below);
                if (datasets.stream().anyMatch(entry -> isLeaf(below, entry))) {
                    datasets = withSequentialAsFiles(unquoted, below, datasets);
                }
                for (RemoteEntry entry : datasets) {
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

    /** Ob ein Eintrag aus {@code NLST 'X.*'} ein echtes Dataset ist (keine bloße Qualifier-Stufe). */
    private static boolean isLeaf(String[] names, RemoteEntry entry) {
        String path = MvsPath.unquote(entry.path());
        for (String raw : names) {
            if (raw != null && MvsPath.unquote(raw.trim()).equalsIgnoreCase(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * NLST sagt nicht, ob ein Dataset partitioniert (PO, hat Members) oder sequenziell (PS, eine Datei) ist. Das steht
     * nur in der Spalte Dsorg von {@code LIST 'X.*'}; sequenzielle Datasets werden damit zu Dateien, die man lesen kann.
     * Kann der Server das nicht liefern, bleibt es bei Ordnern wie bisher.
     */
    private List<RemoteEntry> withSequentialAsFiles(String parent, String[] names, List<RemoteEntry> datasets) {
        Set<String> sequential = new java.util.HashSet<>();
        try {
            FTPFile[] files = ftp.listFiles("'" + parent + ".*'");
            if (files != null) {
                for (FTPFile file : files) {
                    if (file != null && file.getName() != null && file.isFile()) {
                        sequential.add(MvsPath.unquote(file.getName()).toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException ignored) {
            return datasets;
        }
        if (sequential.isEmpty()) {
            return datasets;
        }
        String prefix = parent.toUpperCase(Locale.ROOT) + ".";
        List<RemoteEntry> result = new ArrayList<>();
        for (RemoteEntry entry : datasets) {
            String full = MvsPath.unquote(entry.path()).toUpperCase(Locale.ROOT);
            // z/OS zeigt Namen unter dem Arbeitsverzeichnis relativ, sonst voll qualifiziert.
            boolean file = isLeaf(names, entry) && (sequential.contains(full)
                    || sequential.stream().anyMatch(name -> full.endsWith("." + name) && full.startsWith(prefix)));
            result.add(file ? RemoteEntry.file(entry.name(), entry.path()) : entry);
        }
        return result;
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
        byte[] bytes = recordStructure ? RecordStructure.encode(text, charset) : RecordStructure.text(text, charset);
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
    public void createDataset(String path, DatasetAttributes attributes) throws IOException {
        if (!mvs) {
            createDirectory(path);
            return;
        }
        String resolved = MvsPath.absolute(path);
        if (!ftp.sendSiteCommand(attributes.siteCommand())) {
            throw new FtpReplyException("Anlageparameter abgelehnt (" + attributes.siteCommand() + "): "
                    + Clients.reply(ftp), ftp.getReplyCode());
        }
        if (attributes.organization() == DatasetAttributes.Organization.PO) {
            if (!ftp.makeDirectory(resolved)) {
                throw new FtpReplyException("Anlegen abgelehnt: " + resolved + " – " + Clients.reply(ftp),
                        ftp.getReplyCode());
            }
        } else if (!ftp.storeFile(resolved, new ByteArrayInputStream(new byte[0]))) {
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
        // Sofort trennen: ein hängender Server darf „Trennen“ nicht bis zum Zeitlimit aufhalten.
        Clients.close(ftp);
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
