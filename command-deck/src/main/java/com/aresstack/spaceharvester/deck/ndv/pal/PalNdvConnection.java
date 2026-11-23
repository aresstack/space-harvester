package com.aresstack.spaceharvester.deck.ndv.pal;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectType;
import com.aresstack.spaceharvester.deck.ndv.NdvConnection;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvObjectType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Eine Verbindung über {@link NdvClient}. Die Wahl der Systemdatei für Auflistungen (FUSER bevorzugt, sonst die erste
 * gültige, sonst die Vorgabe des Servers) und das Anmelden an der Bibliothek folgen MainframeMates
 * {@code NdvService}; das Hintereinanderreihen übernimmt die Sitzung.
 */
final class PalNdvConnection implements NdvConnection {

    private static final Logger LOG = Logger.getLogger(PalNdvConnection.class.getName());

    private final NdvClient client;
    private IPalTypeSystemFile listing;

    PalNdvConnection(NdvClient client) {
        this.client = client;
    }

    @Override
    public List<String> libraries(String filter) throws IOException {
        IPalTypeSystemFile sysFile = listingSysFile();
        return call(() -> client.listLibraries(sysFile, filter));
    }

    @Override
    public int objects(String library, String filter, Page page) throws IOException {
        // Wie MainframeMate (NdvConnectionTab): erst an der Bibliothek anmelden, dann auflisten.
        try {
            client.logon(library);
        } catch (NdvException e) {
            LOG.log(Level.FINE, "Anmelden an der Bibliothek gescheitert, Auflistung versucht es trotzdem", e);
        }
        IPalTypeSystemFile sysFile = listingSysFile();
        boolean[] stopped = {false};
        int[] count = {0};
        call(() -> client.listObjectsProgressive(sysFile, library, filter, ObjectKind.SOURCE, 0,
                (items, totalSoFar) -> {
                    count[0] = totalSoFar;
                    stopped[0] = !page.accept(toObjects(library, items), count[0]);
                    return !stopped[0];
                }));
        if (stopped[0]) {
            return count[0];
        }
        // DDMs liegen in der Systemdatei FDDM, nicht in der Bibliothek; wie in MainframeMate getrennt aufgelistet,
        // ein Fehler dabei ist nicht schlimm (NdvService.listDdmsProgressive, NdvConnectionTab).
        int before = count[0];
        try {
            IPalTypeSystemFile fddm = fddmSysFile();
            client.listObjectsProgressive(fddm, "", filter, ObjectKind.SOURCE, ObjectType.DDM,
                    (items, totalSoFar) -> {
                        count[0] = before + totalSoFar;
                        return page.accept(toObjects(library, items), count[0]);
                    });
        } catch (NdvException e) {
            LOG.log(Level.FINE, "DDMs nicht auflistbar (nicht schlimm)", e);
        }
        return count[0];
    }

    /** MainframeMate {@code NdvService.listDdmsProgressive}: FDDM, sonst die Systemdatei der Auflistung. */
    private IPalTypeSystemFile fddmSysFile() {
        try {
            IPalTypeSystemFile[] files = client.getSystemFiles();
            if (files != null) {
                for (IPalTypeSystemFile sf : files) {
                    if (sf.getKind() == IPalTypeSystemFile.FDDM) {
                        return sf;
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "FDDM nicht gefunden", e);
        }
        return listingSysFile();
    }

    @Override
    public String read(NdvObject object) throws IOException {
        return call(() -> client.readSource(object.library(), toInfo(object)));
    }

    @Override
    public void write(NdvObject object, String source) throws IOException {
        call(() -> {
            client.writeSource(object.library(), toInfo(object), source);
            return null;
        });
    }

    @Override
    public boolean open() {
        return client.isConnected();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Trennen vom NDV-Server", e);
        }
    }

    /** MainframeMate {@code NdvService.getListingSysFile}: FUSER, sonst erste gültige, sonst Vorgabe des Servers. */
    private IPalTypeSystemFile listingSysFile() {
        if (listing != null) {
            return listing;
        }
        try {
            listing = pickBest(client.getSystemFiles());
        } catch (Exception e) {
            LOG.log(Level.FINE, "Systemdateien nicht lesbar, nehme die Vorgabe des Servers", e);
        }
        return listing != null ? listing : client.getDefaultSystemFile();
    }

    static IPalTypeSystemFile pickBest(IPalTypeSystemFile[] sysFiles) {
        if (sysFiles == null || sysFiles.length == 0) {
            return null;
        }
        IPalTypeSystemFile fuser = null;
        IPalTypeSystemFile firstValid = null;
        for (IPalTypeSystemFile sf : sysFiles) {
            boolean valid = sf.getDatabaseId() > 0 && sf.getFileNumber() > 0;
            if (fuser == null && valid && sf.getKind() == IPalTypeSystemFile.FUSER) {
                fuser = sf;
            }
            if (firstValid == null && valid) {
                firstValid = sf;
            }
        }
        return fuser != null ? fuser : firstValid != null ? firstValid : sysFiles[0];
    }

    static List<NdvObject> toObjects(String library, List<NdvObjectInfo> items) {
        List<NdvObject> objects = new ArrayList<>(items.size());
        for (NdvObjectInfo info : items) {
            objects.add(new NdvObject(library, info.getName(),
                    new NdvObjectType(info.getType(), info.getTypeName(), info.getTypeExtension()), info.hasSource(),
                    info.getSourceSize(), info.getUser(), info.getSourceDate(),
                    new NdvObject.Location(info.getDatabaseId(), info.getFileNumber(), info.getLongName(),
                            info.getKind(), info.getType() == ObjectType.DDM),
                    new NdvObject.Details(info.getProgrammingMode(), info.getKindName(), info.getCodePage(),
                            info.getGpUser(), info.getGpDate(), info.getAccessDate())));
        }
        return objects;
    }

    static NdvObjectInfo toInfo(NdvObject object) {
        NdvObject.Location at = object.location();
        String name = object.name().isEmpty() ? at.longName() : object.name();
        return new NdvObjectInfo(name, at.longName(), at.kind(), object.type().id(), object.type().name(),
                object.type().extension(), object.sourceSize(), object.user(), object.changed(), at.databaseId(),
                at.fileNumber());
    }

    @FunctionalInterface
    private interface Call<T> {
        T run() throws IOException, NdvException;
    }

    private static <T> T call(Call<T> work) throws IOException {
        try {
            return work.run();
        } catch (NdvException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
