package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

import java.util.List;

/**
 * Optionale Fähigkeit einer Quelle: die Lese-Ansicht einer Seite, so wie die Quelle sie selbst darstellt (HTML mit
 * Bildern an ihrer Stelle). Sie ist ein eigener Pfad neben {@link KnowledgeSourcePort#load}: das
 * {@link com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument} bleibt der kanonische Text für Index und
 * Modell, die Ansicht dient nur dem Lesen.
 */
public interface PageViewSource {

    /** Die Lese-Ansicht der Seite; wirft {@link KnowledgeSourceException}, wenn die Quelle sie nicht kennt. */
    PageView pageView(KnowledgeResourceId page) throws KnowledgeSourceException;

    /**
     * Die Bytes eines Bildes der Ansicht über die Anmeldung der Quelle (wie MainframeMates {@code ByteDownloader});
     * nur Adressen der Quelle selbst, alles andere {@code UNSUPPORTED}.
     *
     * @param src die Adresse aus {@link PageView.Image#src()}
     */
    byte[] loadViewImage(String src) throws KnowledgeSourceException;

    /**
     * Die Originaldatei eines Anhangs aus {@link PageView#attachments()} über die Anmeldung der Quelle (derselbe
     * Netzweg wie die Seite). Quellen ohne Anhänge: {@code UNSUPPORTED}.
     */
    default byte[] loadAttachment(PageView.Attachment attachment) throws KnowledgeSourceException {
        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Quelle ohne Anhänge");
    }

    /**
     * Eine von der Quelle erzeugte Vorschau des Anhangs (z. B. ein Office-Dokument als PDF oder Bild); nur Arten aus
     * {@link PageView.Attachment#previews()}. Gibt es sie nicht (noch nicht erzeugt, Typ nicht umwandelbar):
     * {@code NOT_FOUND}, {@code UNAVAILABLE} oder {@code UNSUPPORTED}.
     */
    default byte[] loadAttachmentPreview(PageView.Attachment attachment, PageView.Preview preview)
            throws KnowledgeSourceException {
        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Quelle ohne Vorschauen");
    }

    /**
     * Die Dateianhänge einer Seite als Ressourcen dieser Quelle, ohne Inhalt: Titel (Dateiname), Typ, Revision, Seite
     * als Eltern, Größe in den Metadaten. Für Werkzeuge, die dem Modell Anhänge zeigen. Quellen ohne Anhänge:
     * {@code UNSUPPORTED}.
     */
    default List<KnowledgeResource> attachmentsOf(KnowledgeResourceId page) throws KnowledgeSourceException {
        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Quelle ohne Anhänge");
    }

    /** Ob die ID einen Dateianhang dieser Quelle bezeichnet; den liest {@link #loadAttachmentFile}, nicht {@code load}. */
    default boolean isAttachment(KnowledgeResourceId id) {
        return false;
    }

    /**
     * Ein Dateianhang samt Ressource und Originalbytes über die Anmeldung der Quelle. Verlangt die Quelle dafür einen
     * Einmalcode, kommt {@link OneTimeCodeRequiredException}. Keine Anhang-ID dieser Quelle: {@code UNSUPPORTED}.
     */
    default SourceFile loadAttachmentFile(KnowledgeResourceId attachment) throws KnowledgeSourceException {
        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "Quelle ohne Anhänge");
    }

    /**
     * Wie {@link #loadAttachmentFile(KnowledgeResourceId)}, aber mit einer Grenze des Aufrufers: Der Download bricht
     * beim Lesen ab, sobald er sie überschreitet ({@link ResponseTooLargeException}), statt die Datei erst ganz zu
     * laden. Die Grenze der Quelle gilt zusätzlich. Quellen ohne eigene Umsetzung prüfen erst nach dem Laden.
     */
    default SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
            throws KnowledgeSourceException {
        SourceFile file = loadAttachmentFile(attachment);
        if (file.content().length > maxBytes) {
            throw new ResponseTooLargeException("Anhang größer als " + maxBytes + " Bytes", maxBytes);
        }
        return file;
    }

    /**
     * Ob Dateien (Bilder, Anhänge, Vorschauen) gerade ohne Einmalcode laden. Ein Aufrufer mit eigenem Ersatz (etwa ein
     * selbst gezeichnetes Diagramm) kann damit entscheiden, ob er das Original überhaupt anfragt.
     */
    default DownloadAccess downloadAccess() {
        return DownloadAccess.OPEN;
    }
}
