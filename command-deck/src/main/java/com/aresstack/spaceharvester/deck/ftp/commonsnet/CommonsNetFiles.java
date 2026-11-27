package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import com.aresstack.spaceharvester.deck.ftp.BinarySample;
import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.MvsListing;
import com.aresstack.spaceharvester.deck.ftp.TransferOptions;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocation;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocationType;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsQuoteNormalizer;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsVirtualResource;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Art jedes gelisteten MVS-Orts (wie MainframeMate sie am {@code MvsVirtualResource} trägt). */
    private final java.util.Map<String, MvsLocation> locations = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LIST_PAGE = 200;
    /** Hinweis, wenn ein Dataset Binärdaten enthält (etwa ein Lademodul einer LOAD-Bibliothek). */
    static final String BINARY_NOTICE = "Binärdaten, kein Quelltext (etwa ein Lademodul aus einer LOAD-Bibliothek) – "
            + "als Text nicht lesbar, Bearbeiten gesperrt. Rechtsklick › Hex-Ansicht zeigt die Bytes samt EBCDIC-Spalte.";

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
        boolean typeAccepted;
        if (transfer.textFormat() == TransferOptions.TextFormat.DEFAULT) {
            typeAccepted = ftp.setFileType(type);
        } else {
            typeAccepted = ftp.setFileType(type, switch (transfer.textFormat()) {
                case TELNET -> FTP.TELNET_TEXT_FORMAT;
                case CARRIAGE_CONTROL -> FTP.CARRIAGE_CONTROL_TEXT_FORMAT;
                default -> FTP.NON_PRINT_TEXT_FORMAT;
            });
        }
        if (!typeAccepted) throw new IOException("FTP TYPE abgelehnt – " + Clients.reply(ftp));
        boolean structureAccepted = ftp.setFileStructure(switch (transfer.structure()) {
            case PAGE -> FTP.PAGE_STRUCTURE;
            case AUTO, RECORD, FILE -> recordStructure ? FTP.RECORD_STRUCTURE : FTP.FILE_STRUCTURE;
        });
        // Nur Stream: TransferOptions lässt Block und Komprimiert nicht zu (Commons Net kann sie nicht rahmen).
        if (!structureAccepted || !ftp.setFileTransferMode(FTP.STREAM_TRANSFER_MODE)) {
            throw new IOException("FTP-Übertragungsmodus abgelehnt – " + Clients.reply(ftp));
        }
    }

    private void binaryMode() throws IOException {
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE) || !ftp.setFileStructure(FTP.FILE_STRUCTURE)) {
            throw new FtpReplyException("Binärmodus abgelehnt – " + Clients.reply(ftp), ftp.getReplyCode());
        }
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
    public List<RemoteEntry> listCopybookMembers(String dataset) throws IOException {
        if (!mvs) {
            return list(dataset);
        }
        String base = MvsPath.unquote(dataset).toUpperCase(Locale.ROOT);
        String query = MvsPath.absolute(base + "(*)");
        String[] names = ftp.listNames(query);
        int reply = ftp.getReplyCode();
        if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(reply)) {
            String message = Clients.reply(ftp);
            // Accept only the explicit empty-PDS response, not ambiguous access or dataset errors.
            if (reply == 550 && message.toLowerCase(Locale.ROOT).contains("no members found")) {
                return List.of();
            }
            if (reply == 421 || org.apache.commons.net.ftp.FTPReply.isNegativeTransient(reply)) {
                throw new IOException("SYSLIB " + query + " – " + message);
            }
            throw new FtpReplyException("SYSLIB " + query + " – " + message, reply);
        }
        if (names == null) {
            throw new IOException("SYSLIB " + query + ": member listing not received");
        }
        List<RemoteEntry> members = new ArrayList<>();
        for (String raw : names) {
            String name = MvsPath.unquote(raw).trim().toUpperCase(Locale.ROOT);
            if (name.isBlank()) {
                continue;
            }
            if (MvsPath.isMember(name)) {
                String[] parts = MvsPath.splitMember(name);
                if (!base.equals(parts[0])) {
                    throw new IOException("Unexpected SYSLIB member: " + raw);
                }
                name = parts[1];
            }
            if (!MvsPath.isMemberName(name)) {
                throw new IOException("Unexpected SYSLIB listing entry: " + raw);
            }
            members.add(RemoteEntry.file(name, MvsPath.absolute(MvsPath.memberSpec(base, name))));
        }
        return List.copyOf(members);
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
     * Liste wie die MVS-Ansicht von MainframeMate ({@code MvsConnectionTab} → {@code MvsBrowserController} →
     * {@code MvsListingService}): Ort mit Art (HLQ, Qualifier, Dataset, Member), bei einem Dataset zuerst
     * {@code NLST 'DS(*)'} für die Members, dann {@code 'DS.*'} für Datasets darunter. Die Art jedes gelisteten
     * Orts merkt sich die Verbindung, damit ein Doppelklick ihn wieder mit derselben Art öffnet.
     */
    private List<RemoteEntry> listMvs(String resolved) throws IOException {
        if (resolved.isEmpty() || MvsPath.ROOT.equals(resolved)) {
            throw new FtpReplyException("Die MVS-Wurzel lässt sich nicht auflisten – bitte einen HLQ eingeben", 0);
        }
        MvsLocation location = location(resolved);
        List<MvsVirtualResource> resources = new ArrayList<>();
        new MvsListingService(ftp).listChildren(location, LIST_PAGE, new AtomicBoolean(),
                (items, last) -> resources.addAll(items));
        if (resources.isEmpty()) {
            // MvsListingService schluckt Fehler je Strategie (leer = „nichts gefunden“). Eine abgerissene Verbindung
            // darf aber nicht als leere Liste durchgehen, sonst greifen Wiederholung und Neuaufbau nicht.
            ftp.sendNoOp();
        }
        List<RemoteEntry> entries = new ArrayList<>(resources.size());
        for (MvsVirtualResource resource : resources) {
            MvsLocation child = resource.getLocation();
            locations.put(key(child.getLogicalPath()), child);
            long modified = resource.getLastModified();
            entries.add(new RemoteEntry(resource.getDisplayName(), child.getLogicalPath(), child.isDirectory(),
                    resource.getSize() > 0 ? resource.getSize() : -1, modified > 0 ? Optional.of(java.time.Instant.ofEpochMilli(modified))
                    : Optional.empty()));
        }
        return entries;
    }

    /** Wie {@code MvsBrowserController.openResource}: ein Dataset öffnet nur als Liste, wenn es partitioniert ist. */
    @Override
    public boolean opensAsFolder(RemoteEntry entry) throws IOException {
        if (!mvs) {
            return entry.directory();
        }
        String organization = entry.details().organization().toUpperCase(Locale.ROOT);
        if (organization.equals("PS")) return false;
        if (organization.startsWith("PO")) return true;
        MvsLocation location = location(entry.path());
        return switch (location.getType()) {
            case HLQ, QUALIFIER_CONTEXT -> true;
            case DATASET -> MvsDatasetProbe.isLikelyPartitionedDataset(ftp, location);
            default -> false;
        };
    }

    /**
     * Der gemerkte Ort mit seiner Art, sonst wie eine Eingabe in der Pfadzeile ({@code MvsLocation.parse}). Ein
     * getippter Name mit Punkt gilt als Dataset statt als bloße Qualifier-Stufe: Die Dataset-Liste von
     * {@code MvsListingService} enthält die Qualifier-Liste ({@code 'X.*'}) und zusätzlich die Members ({@code 'X(*)'}).
     */
    private MvsLocation location(String path) {
        MvsLocation known = locations.get(key(path));
        if (known != null) {
            return known;
        }
        MvsLocation parsed = MvsLocation.parse(path);
        return parsed.getType() == MvsLocationType.QUALIFIER_CONTEXT
                ? MvsLocation.dataset(parsed.getLogicalPath()) : parsed;
    }

    @Override
    public RemoteEntry.Details datasetDetails(String path) {
        if (!mvs) return RemoteEntry.Details.NONE;
        String dataset = MvsPath.isMember(path) ? MvsPath.splitMember(path)[0] : MvsPath.unquote(path);
        String name = dataset.toUpperCase(Locale.ROOT);
        for (String query : List.of(MvsPath.absolute(name), MvsPath.absolute(name.substring(0,
                Math.max(0, name.lastIndexOf('.') + 1)) + "*"))) {
            for (String line : rawList(query)) {
                var parsed = MvsListing.dataset(line);
                if (parsed.isPresent() && parsed.get().name().equals(name)) return parsed.get().details();
            }
        }
        return RemoteEntry.Details.NONE;
    }

    private static String key(String path) {
        return MvsQuoteNormalizer.unquote(path).toUpperCase(Locale.ROOT);
    }

    @Override
    public RemoteFile read(String path) throws IOException {
        FtpReplyException last = null;
        for (String candidate : candidates(path)) {
            try {
                byte[] raw = retrieve(candidate);
                String restriction = mvs && (transfer.fileType() == TransferOptions.FileType.BINARY
                        || RecordStructure.suspectBinary(raw, charset, transfer.transform(), recordStructure))
                        ? BINARY_NOTICE : "";
                byte[] bytes = RecordStructure.withoutPadding(raw, transfer.transform());
                String text = recordStructure ? RecordStructure.decode(bytes, charset, transfer.transform()) : new String(bytes, charset);
                return new RemoteFile(candidate, text, null, restriction);
            } catch (FtpReplyException refused) {
                last = refused;
            }
        }
        throw last != null ? last : new FtpReplyException("Nicht gefunden: " + path, 550);
    }

    @Override
    public byte[] readBinary(String path) throws IOException {
        return binaryTransfer(path, InputStream::readAllBytes);
    }

    @Override
    public BinarySample readBinarySample(String path, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("Diagnoselimit");
        return binaryTransfer(path, input -> BinarySample.capture(input, limit));
    }

    @Override
    public void downloadBinary(String path, java.io.OutputStream output) throws IOException {
        binaryTransfer(path, input -> input.transferTo(output));
    }

    @FunctionalInterface
    private interface BinaryReader<T> {
        T read(InputStream input) throws IOException;
    }

    private <T> T binaryTransfer(String path, BinaryReader<T> reader) throws IOException {
        try {
            binaryMode();
            FtpReplyException last = null;
            for (String candidate : candidates(path)) {
                InputStream input = ftp.retrieveFileStream(candidate);
                if (input == null) {
                    last = new FtpReplyException("Nicht lesbar: " + candidate + " – " + Clients.reply(ftp),
                            ftp.getReplyCode());
                    continue;
                }
                T result;
                try (input) {
                    result = reader.read(input);
                } catch (IOException | RuntimeException broken) {
                    // Discard a control connection with an incomplete data transfer.
                    Clients.close(ftp);
                    throw broken;
                }
                if (!ftp.completePendingCommand()) {
                    String reply = Clients.reply(ftp);
                    Clients.close(ftp);
                    throw new IOException("Binärübertragung unvollständig – " + reply);
                }
                return result;
            }
            throw last != null ? last : new FtpReplyException("Nicht gefunden: " + path, 550);
        } finally {
            restoreTextMode();
        }
    }

    private void restoreTextMode() throws IOException {
        if (!ftp.isConnected()) return;
        try {
            textMode();
        } catch (IOException | RuntimeException failure) {
            Clients.close(ftp);
            throw failure;
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
        byte[] bytes = recordStructure ? RecordStructure.encode(text, charset, transfer.transform()) : RecordStructure.text(text, charset);
        store(path, bytes);
    }

    @Override
    public void writeBinary(String path, byte[] content) throws IOException {
        try {
            binaryMode();
            store(path, content);
        } finally {
            restoreTextMode();
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
