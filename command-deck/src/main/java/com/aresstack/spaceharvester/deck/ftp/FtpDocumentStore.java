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

    public FtpDocumentStore(FtpSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public FtpSession session() {
        return session;
    }

    @Override
    public EditorDocument load(String location) throws DocumentException {
        RemoteFile file = await(session.files(files -> files.read(location)), "Lesen von " + location);
        return document(file);
    }

    @Override
    public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
        String path = loaded.location();
        // Satzformat: Zeilen werden rechts gekürzt, der letzte Umbruch erzeugt keinen leeren Satz (wie beim Hochladen).
        String records = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        try {
            RemoteFile written = session.filesOnce(files -> files.writeIfUnchanged(path, records,
                    overwrite ? null : loaded.hash())).get();
            // Der neue Fingerabdruck muss zu dem passen, was der Host beim nächsten Lesen liefert.
            RemoteFile fresh = await(session.files(files -> files.read(written.path())), "Lesen nach dem Speichern");
            return new SaveResult.Saved(document(fresh));
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

    private EditorDocument document(RemoteFile file) {
        return new EditorDocument(file.path(), name(file.path()), file.text(), session.profile().encoding(), "\n",
                file.hash());
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
