package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Ablage des gemeinsamen Editors für Members und Dateien einer FTP-Sitzung (wie das Bearbeiten in MainframeMate):
 * Laden liest über die Datasets-Leitung, Speichern prüft vorher den Fingerabdruck ({@code writeIfUnchanged}) und
 * schreibt Satz für Satz zurück. Gelesen wird mit einer Wiederholung nach Verbindungsabbruch, geschrieben nie doppelt.
 * Eine Ablage gehört zu genau einer Sitzung; dieselbe Adresse auf zwei Sitzungen sind zwei Dokumente.
 */
public final class FtpDocumentStore implements DocumentStore {

    private final FtpSession session;
    private final CopybookMemberIndex copybookMembers;

    public FtpDocumentStore(FtpSession session) {
        this(session, System::nanoTime);
    }

    FtpDocumentStore(FtpSession session, java.util.function.LongSupplier clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.copybookMembers = new CopybookMemberIndex(clock,
                dataset -> await(session.files(files -> files.listCopybookMembers(MvsPath.absolute(dataset))),
                        "SYSLIB " + dataset));
    }

    /**
     * Kennung für den lokalen Verlauf: Host, Port und Benutzer, damit gleiche Pfade auf verschiedenen Servern oder
     * Konten nicht im selben Verlauf landen.
     */
    @Override
    public String id() {
        String user = session.user();
        return "ftp:" + session.profile().host().toLowerCase(java.util.Locale.ROOT) + ":" + session.profile().port()
                + ":" + (user == null ? "" : user.toUpperCase(java.util.Locale.ROOT));
    }

    public FtpSession session() {
        return session;
    }

    @Override
    public EditorDocument load(String location) throws DocumentException {
        RemoteFile file = await(session.files(files -> files.read(location)), "Lesen von " + location);
        return document(file);
    }

    /** Auf MVS dasselbe PDS mit anderem Member, sonst dieselbe Datei im selben Ordner. */
    @Override
    public java.util.List<String> siblings(String location, String name) {
        return siblingPaths(location, name);
    }

    @Override
    public java.util.Optional<EditorDocument> resolveCopybook(String location, String name, String library)
            throws DocumentException {
        if (!MvsPath.isMember(location)) {
            return DocumentStore.super.resolveCopybook(location, name, library);
        }
        if (!MvsPath.isMemberName(name)) {
            throw new DocumentException("Ungültiger COPY-Membername: " + name);
        }
        var datasets = new java.util.LinkedHashSet<String>();
        if (library.isBlank()) {
            datasets.addAll(session.profile().syslib());
            datasets.add(MvsPath.splitMember(location)[0]);
        } else {
            String qualifier = MvsPath.unquote(library);
            session.profile().syslib().stream()
                    .filter(ds -> ds.equalsIgnoreCase(qualifier))
                    .forEach(datasets::add);
            if (datasets.isEmpty()) {
                session.profile().syslib().stream()
                        .filter(ds -> ds.substring(ds.lastIndexOf('.') + 1).equalsIgnoreCase(qualifier))
                        .forEach(datasets::add);
            }
            if (datasets.size() > 1) {
                throw new DocumentException("Mehrdeutige SYSLIB-Zuordnung für " + library);
            }
            if (datasets.isEmpty()) {
                throw new DocumentException("Keine SYSLIB-Zuordnung für " + library);
            }
        }
        for (String dataset : datasets) {
            String memberPath = copybookMembers.find(dataset, name);
            if (memberPath != null) {
                return java.util.Optional.of(load(memberPath));
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public String copybookLookupScope(String location, String library) {
        if (!MvsPath.isMember(location)) {
            return DocumentStore.super.copybookLookupScope(location, library);
        }
        return library.isBlank() ? MvsPath.splitMember(location)[0].toUpperCase(java.util.Locale.ROOT) : "";
    }

    static java.util.List<String> siblingPaths(String location, String name) {
        if (location == null || name == null || name.isBlank()) {
            return java.util.List.of();
        }
        String member = name.trim();
        String unquoted = MvsPath.unquote(location);
        int open = unquoted.indexOf('(');
        if (open > 0 && unquoted.endsWith(")")) {
            String dataset = unquoted.substring(0, open);
            String sibling = dataset + "(" + member.toUpperCase(java.util.Locale.ROOT) + ")";
            return java.util.List.of(location.startsWith("'") ? "'" + sibling + "'" : sibling);
        }
        int slash = unquoted.lastIndexOf('/');
        if (slash >= 0) {
            return java.util.List.of(unquoted.substring(0, slash + 1) + member);
        }
        return java.util.List.of();
    }

    @Override
    public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
        if (!loaded.writeRestriction().isEmpty()) throw new DocumentException(loaded.writeRestriction());
        String path = loaded.location();
        boolean records = session.mvs();
        // Nur auf MVS gilt das Satzformat (letzter Umbruch kein leerer Satz); andere Dateien gehen unverändert hinaus.
        String outgoing = records && text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        try {
            RemoteFile written = session.filesOnce(files -> {
                if (overwrite && records) {
                    RemoteFile current = files.read(path);
                    if (!current.writeRestriction().isEmpty()) throw new java.io.IOException(current.writeRestriction());
                }
                return files.writeIfUnchanged(path, outgoing, overwrite ? null : loaded.hash());
            }).get();
            copybookMembers.invalidate(written.path());
            RemoteFile fresh = await(session.files(files -> files.read(written.path())), "Lesen nach dem Speichern");
            if (fresh.text().equals(expected(outgoing, records))) {
                // Der Editor behält seinen Text als Stand (sonst bliebe er nach dem Speichern „geändert“), der
                // Fingerabdruck ist der des Hosts für die nächste Prüfung.
                return new SaveResult.Saved(new EditorDocument(fresh.path(), loaded.name(), text, loaded.charset(),
                        loaded.lineEnding(), fresh.hash(), fresh.writeRestriction()));
            }
            if (overwrite) {
                // Ausdrücklich überschrieben, der Host hat aber etwas anderes abgelegt (etwa Satzlänge): sein Stand
                // wird die Grundlage, der Editor zeigt den Unterschied als ungespeicherte Änderung.
                return new SaveResult.Saved(document(fresh));
            }
            // Nicht still fremde Änderungen übernehmen: lieber nachfragen als beim nächsten Mal blind überschreiben.
            return new SaveResult.Conflict("Gespeichert, aber der Host liefert jetzt einen anderen Text "
                    + "(Satzlänge oder Änderung von außen) – bitte neu laden oder überschreiben.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WriteConflictException) {
                return new SaveResult.Conflict("Das Member wurde seit dem Öffnen auf dem Host geändert.");
            }
            throw failure("Speichern von " + path, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentException("Speichern abgebrochen", e);
        }
    }

    /** Was der Host nach dem Schreiben liefern muss: auf MVS jede Zeile rechts gekürzt, sonst genau der Text. */
    static String expected(String written, boolean records) {
        if (!records) {
            return written;
        }
        String[] lines = written.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(lines[i].stripTrailing());
        }
        return result.toString();
    }

    private EditorDocument document(RemoteFile file) {
        return new EditorDocument(file.path(), name(file.path()), file.text(), session.profile().encoding(), "\n",
                file.hash(), file.writeRestriction());
    }

    /** Anzeigename: auf MVS das Member bzw. der Dataset-Name, sonst der Dateiname. */
    public static String name(String path) {
        String unquoted = MvsPath.unquote(path);
        int open = unquoted.indexOf('(');
        if (open >= 0 && unquoted.endsWith(")")) {
            return unquoted.substring(open + 1, unquoted.length() - 1);
        }
        int slash = unquoted.lastIndexOf('/');
        return slash >= 0 && slash < unquoted.length() - 1 ? unquoted.substring(slash + 1) : unquoted;
    }

    private static <T> T await(CompletableFuture<T> future, String what) throws DocumentException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw failure(what, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentException(what + " abgebrochen", e);
        }
    }

    private static DocumentException failure(String what, Throwable cause) {
        return new DocumentException(what + " fehlgeschlagen – " + FtpSession.describe(cause), cause);
    }
}
