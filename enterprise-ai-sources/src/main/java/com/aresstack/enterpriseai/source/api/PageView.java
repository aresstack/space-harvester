package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Die Lese-Ansicht einer Seite ({@link PageViewSource}): bereinigter HTML-Körper, Basis für relative Adressen und
 * die Bilder in Dokumentreihenfolge. Jedes Bild nennt seine Adresse im HTML ({@code src}, gegen die Basis
 * aufgelöst; die Bytes liefert {@link PageViewSource#loadViewImage}) und, wenn die Quelle es als eigene Ressource
 * kennt (ein Anhang), diese Ressource mit Titel und Version.
 *
 * <p>Daneben die Dateianhänge der Seite ({@link #attachments()}): alle, gleich welchen Typs und unabhängig davon, ob
 * die Quelle sie indexiert. Bildliste und Anhangsliste dürfen sich überschneiden (ein Bild kann inline stehen und
 * zugleich Anhang sein). Die Bytes liefern {@link PageViewSource#loadAttachment} und
 * {@link PageViewSource#loadAttachmentPreview}.</p>
 */
public final class PageView {

    private final String html;
    private final String baseUri;
    private final List<Image> images;
    private final List<Attachment> attachments;

    public PageView(String html, String baseUri, List<Image> images) {
        this(html, baseUri, images, null);
    }

    public PageView(String html, String baseUri, List<Image> images, List<Attachment> attachments) {
        this.html = html == null ? "" : html;
        this.baseUri = baseUri == null ? "" : baseUri;
        this.images = images == null ? Collections.<Image>emptyList()
                : Collections.unmodifiableList(new ArrayList<Image>(images));
        this.attachments = attachments == null ? Collections.<Attachment>emptyList()
                : Collections.unmodifiableList(new ArrayList<Attachment>(attachments));
    }

    /** Der HTML-Körper (ohne {@code <html>}/{@code <body>}), ohne Skripte und eingebettete Objekte. */
    public String html() {
        return html;
    }

    public String baseUri() {
        return baseUri;
    }

    public List<Image> images() {
        return images;
    }

    /** Alle Dateianhänge der Seite in der Reihenfolge der Quelle; leer, wenn sie keine hat oder keine kennt. */
    public List<Attachment> attachments() {
        return attachments;
    }

    /** Ein Bild der Seite. */
    public static final class Image {
        private final String src;
        private final String title;
        private final KnowledgeResource resource;

        /** @param resource die Ressource des Bildes oder {@code null} (z. B. ein fremdes Bild) */
        public Image(String src, String title, KnowledgeResource resource) {
            if (src == null || src.isEmpty()) {
                throw new IllegalArgumentException("src is required");
            }
            this.title = title == null ? "" : title;
            this.src = src;
            this.resource = resource;
        }

        /** Die aufgelöste Adresse aus dem {@code <img>}. */
        public String src() {
            return src;
        }

        /** Alternativtext bzw. Titel; leer, wenn die Seite keinen nennt. */
        public String title() {
            return title;
        }

        /** Das Bild als Ressource der Quelle (ID, Titel, Medientyp, Version) oder {@code null}. */
        public KnowledgeResource resource() {
            return resource;
        }
    }

    /**
     * Vorschauarten, die eine Quelle für einen Anhang erzeugen kann (z. B. Confluences Dokumentkonvertierung):
     * {@code DOCUMENT} das umgewandelte Dokument (etwa PDF), {@code POSTER} ein großes Bild der ersten Seite,
     * {@code THUMBNAIL} ein kleines. Was tatsächlich kommt, erkennt der Aufrufer an den Bytes.
     */
    public enum Preview {
        DOCUMENT, POSTER, THUMBNAIL
    }

    /**
     * Ein Dateianhang der Seite als fachliche Projektion: Kennung der Quelle, Container (die Seite, an der er
     * hängt), Dateiname, Medientyp, Version, Größe, Download- und Web-Adresse (Pfade der Quelle, nur zur Anzeige
     * und Wiedererkennung; geladen wird über {@link PageViewSource#loadAttachment}) und die Vorschauarten, die die
     * Quelle anbieten kann.
     */
    public static final class Attachment {
        /** Medientyp, wenn die Quelle keinen nennt. */
        public static final String UNKNOWN_MEDIA_TYPE = "application/octet-stream";

        private final String id;
        private final String containerId;
        private final String fileName;
        private final String mediaType;
        private final String version;
        private final long size;
        private final String downloadPath;
        private final String webUiPath;
        private final Set<Preview> previews;

        /**
         * @param id      Kennung des Anhangs in der Quelle (Pflicht)
         * @param size    Größe in Bytes, negativ: unbekannt
         * @param previews die Vorschauarten, die die Quelle versuchen kann; {@code null}: keine
         */
        public Attachment(String id, String containerId, String fileName, String mediaType, String version,
                          long size, String downloadPath, String webUiPath, Set<Preview> previews) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("id is required");
            }
            this.id = id;
            this.containerId = containerId == null ? "" : containerId;
            this.fileName = fileName == null || fileName.trim().isEmpty() ? id : fileName;
            this.mediaType = mediaType == null || mediaType.trim().isEmpty() ? UNKNOWN_MEDIA_TYPE : mediaType.trim();
            this.version = version == null ? "" : version;
            this.size = size < 0 ? -1 : size;
            this.downloadPath = downloadPath == null ? "" : downloadPath;
            this.webUiPath = webUiPath == null ? "" : webUiPath;
            this.previews = previews == null || previews.isEmpty() ? Collections.<Preview>emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(previews));
        }

        public String id() {
            return id;
        }

        /** Die Seite (bzw. der Inhalt), an der der Anhang hängt; leer, wenn unbekannt. */
        public String containerId() {
            return containerId;
        }

        /** Dateiname; ohne Namen die Kennung. */
        public String fileName() {
            return fileName;
        }

        /** Medientyp der Quelle, ohne Angabe {@link #UNKNOWN_MEDIA_TYPE}. */
        public String mediaType() {
            return mediaType;
        }

        /** Version; leer, wenn unbekannt. */
        public String version() {
            return version;
        }

        /** Größe in Bytes, {@code -1}: unbekannt. */
        public long size() {
            return size;
        }

        /** Download-Pfad der Quelle; leer, wenn sie keinen nennt. */
        public String downloadPath() {
            return downloadPath;
        }

        /** Web-Adresse (Pfad) der Quelle; leer, wenn sie keine nennt. */
        public String webUiPath() {
            return webUiPath;
        }

        public Set<Preview> previews() {
            return previews;
        }
    }
}
