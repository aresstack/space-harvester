package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import com.pff.PSTAttachment;
import com.pff.PSTException;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Outlook-Postfachdateien (PST/OST) als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Wurzel: eine Postfachdatei oder ein Ordner mit {@code .pst}/{@code .ost}-Dateien (nicht rekursiv).</li>
 *   <li>Startpunkte: Ordner im Postfach ({@code .} = alles ab der Inhaltswurzel, {@code /Posteingang/Projekt});
 *       Unterordner bis {@code maxDepth}.</li>
 *   <li>IDs: {@code mail:<Quell-ID>/<Dateiname>/<Descriptor-Node-ID>}; Revision: Zustell- bzw. Änderungszeit und
 *       Größe.</li>
 *   <li>Inhalt: Betreff, Absender, Empfänger, Datum, Ordner und Text wie in MainframeMate.</li>
 *   <li>Anhänge ({@link AttachmentListSource}, {@link PageViewSource}): je Nachricht die Anhangstabelle der
 *       Postfachdatei, die Originalbytes als Strom aus der Datei (java-libpst), nie über den Nachrichtentext.
 *       Anhangs-ID: {@code mail:<Quell-ID>/<Dateiname>/<Node-ID>/<Kennung>}. Die Kennung ist Base64url (ohne
 *       Auffüllung) über {@code <Dateiname>/<Node-ID>/<Anhangsnummer>}, also allein schon in der ganzen Quelle
 *       eindeutig und dauerhaft (Postfachdatei, Nachricht und die in der Nachricht gespeicherte Anhangsnummer
 *       {@code PidTagAttachNumber}, nie die Position in der Liste) und als Dateiname und URL-Teil sicher. Kommt eine
 *       Anhangsnummer in einer Nachricht mehrfach vor, bekommen die weiteren Einträge einen Zähler in
 *       Listenreihenfolge ({@code …/<Anhangsnummer>/<n>}); eine unlesbare Tabellenzeile ihre Position
 *       ({@code …/@<Position>}). Beides steht zusätzlich in den Warnungen; nichts fehlt in der Liste. Angehängte
 *       Outlook-Elemente ohne Dateibytes kommen als RFC-822-Mail ({@link OutlookMessageWriter}); Verweise und
 *       OLE-Objekte stehen in der Liste, liefern aber keine Datei ({@code UNSUPPORTED}).</li>
 * </ul>
 */
final class OutlookKnowledgeSource implements KnowledgeSourcePort, AttachmentListSource, PageViewSource {

    static final String SCHEME = "mail";
    /** Medientyp der (leeren) Rohantworten einer Anhangsliste: Die Liste stammt aus der Postfachdatei selbst. */
    static final String ATTACHMENT_LIST_MEDIA_TYPE = "application/vnd.ms-outlook";
    /** Form der Anhangsliste: die Anhangstabelle einer Nachricht in der Postfachdatei, ohne Rohantwort. */
    static final String ATTACHMENT_LIST_FORMAT = "outlook-pst-attachment-table";
    static final String EMBEDDED_MEDIA_TYPE = "message/rfc822";
    static final String METADATA_ATTACHMENT_NUMBER = "mail.attachment.number";
    static final String METADATA_ATTACHMENT_METHOD = "mail.attachment.method";

    private static final Logger LOG = Logger.getLogger(OutlookKnowledgeSource.class.getName());
    /** Schutz gegen sehr tiefe Ordnerbäume (aus MainframeMate). */
    private static final int MAX_FOLDER_DEPTH = 15;

    private final SourceAddress sourceId;
    private final Path mailbox;
    /** Postfachpfad absolut mit {@code /}, Teil jeder Ressourcen-ID ({@code mail:/C:/Post/a.pst/2097188}). */
    private final String locator;

    OutlookKnowledgeSource(SourceAddress sourceId, Path mailbox) {
        if (sourceId == null || mailbox == null) {
            throw new IllegalArgumentException("sourceId and mailbox are required");
        }
        this.sourceId = sourceId;
        this.mailbox = mailbox.toAbsolutePath().normalize();
        this.locator = locatorOf(this.mailbox);
    }

    /** Kanonischer Locator eines Postfachs: absolut, normalisiert, mit {@code /} getrennt. */
    static String locatorOf(Path mailbox) {
        return SourceLocators.path(mailbox.toAbsolutePath().normalize().toString());
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
        for (Path file : mailFiles()) {
            PSTFile pst = open(file);
            try {
                PSTFolder contentRoot = findContentRoot(pst);
                for (String startPoint : scope.startPoints()) {
                    String folderPath = folderPath(startPoint);
                    PSTFolder start = folderPath.isEmpty() ? contentRoot : navigateToFolder(pst, contentRoot, folderPath);
                    if (start == null) {
                        LOG.fine("Outlook " + sourceId + ": Ordner nicht gefunden: " + folderPath + " in " + file);
                        continue;
                    }
                    if (!scanFolder(start, folderPath, file, 0, scope, found)) {
                        return found;
                    }
                }
            } finally {
                close(pst);
            }
        }
        return found;
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        return withMessage(resourceId, (file, pst, message) -> {
            String folder = folderOf(pst, message);
            KnowledgeResource resource = resource(file, message, folder);
            return KnowledgeDocument.of(resource, buildIndexableText(message, folder));
        });
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        mailFile(partsOf(resourceId)[0]);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "OutlookKnowledgeSource{" + sourceId + ", " + mailbox + "}";
    }

    // ── Anhänge (ADR 0026 Punkt 9 im Space Harvester) ──────────────────────────────────────────────────────────

    /**
     * Die Anhangstabelle einer Nachricht. Lokale ID ist der Teil der Ressourcen-ID hinter dem Postfach
     * ({@code <Dateiname>/<Node-ID>}); die ganze Ressourcen-ID ({@code mail:…}) gilt ebenso. Rohantworten gibt es
     * nicht, die Liste stammt aus der Postfachdatei. Es fehlt kein Eintrag: eine doppelte Anhangsnummer bekommt einen
     * Zähler in Listenreihenfolge, eine unlesbare Tabellenzeile eine Kennung nach ihrer Position (ihr Abruf
     * scheitert); beides steht ohne Dateinamen in den Warnungen.
     */
    @Override
    public StoredAttachmentList storedAttachments(String localId) throws KnowledgeSourceException {
        KnowledgeResourceId messageId = messageIdOf(localId);
        Instant retrievedAt = Instant.now();
        return withMessage(messageId, (file, pst, message) -> {
            Listing listing = list(file, message, messageId);
            return new StoredAttachmentList(listing.resources, Collections.<byte[]>emptyList(),
                    ATTACHMENT_LIST_MEDIA_TYPE, ATTACHMENT_LIST_FORMAT, retrievedAt, listing.warnings);
        });
    }

    @Override
    public List<KnowledgeResource> attachmentsOf(KnowledgeResourceId page) throws KnowledgeSourceException {
        return withMessage(page, (file, pst, message) -> list(file, message, page).resources);
    }

    @Override
    public boolean isAttachment(KnowledgeResourceId id) {
        return attachmentParts(id) != null;
    }

    @Override
    public SourceFile loadAttachmentFile(KnowledgeResourceId attachment) throws KnowledgeSourceException {
        return readAttachment(attachment, Long.MAX_VALUE);
    }

    @Override
    public SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
            throws KnowledgeSourceException {
        return readAttachment(attachment, maxBytes);
    }

    /** Die Originalbytes fließen aus der Postfachdatei nach {@code sink}, nie ganz in den Speicher. */
    @Override
    public KnowledgeResource streamAttachmentFile(KnowledgeResourceId attachment, OutputStream sink)
            throws KnowledgeSourceException {
        return withAttachment(attachment, (resource, found) -> {
            transfer(attachment, found, sink);
            return resource;
        });
    }

    @Override
    public byte[] loadAttachment(PageView.Attachment attachment) throws KnowledgeSourceException {
        return readAttachment(attachmentIdOf(attachment.id()), Long.MAX_VALUE).content();
    }

    /**
     * Eine schlichte Lese-Ansicht der Nachricht: Kopfdaten und Text, alles maskiert, ohne Bilder; die Anhänge als
     * Liste ohne Vorschauen.
     */
    @Override
    public PageView pageView(KnowledgeResourceId page) throws KnowledgeSourceException {
        return withMessage(page, (file, pst, message) -> {
            String folder = folderOf(pst, message);
            StringBuilder html = new StringBuilder();
            String subject = safe(message.getSubject()).trim();
            html.append("<h1>").append(escape(subject.isEmpty() ? "(ohne Betreff)" : subject)).append("</h1>\n<p>");
            html.append("Von: ").append(escape(safe(message.getSenderName()))).append("<br>\n");
            html.append("An: ").append(escape(safe(message.getDisplayTo()))).append("<br>\n");
            if (message.getMessageDeliveryTime() != null) {
                html.append("Datum: ").append(message.getMessageDeliveryTime().toInstant()).append("<br>\n");
            }
            html.append("Ordner: ").append(escape(folder)).append("</p>\n");
            String body;
            try {
                body = safe(message.getBody());
            } catch (RuntimeException e) {
                body = "";
            }
            html.append("<pre>").append(escape(body)).append("</pre>");
            List<PageView.Attachment> attachments = new ArrayList<PageView.Attachment>();
            List<KnowledgeResource> listed;
            try {
                listed = list(file, message, page).resources;
            } catch (KnowledgeSourceException e) {
                // Wie im Lesebereich üblich: ohne lesbare Anhangstabelle bleibt die Nachricht lesbar, nur ohne Liste;
                // storedAttachments und attachmentsOf melden den Fehler.
                listed = Collections.emptyList();
            }
            for (KnowledgeResource resource : listed) {
                String size = resource.metadata().asMap().get(KnowledgeMetadata.FILE_SIZE);
                attachments.add(new PageView.Attachment(lastSegment(resource.id()), page.value(), resource.title(),
                        resource.contentType(), resource.revision().version(),
                        size == null ? -1 : Long.parseLong(size), null, null, null));
            }
            return new PageView(html.toString(), "", Collections.<PageView.Image>emptyList(), attachments);
        });
    }

    /** Die Ansicht hat keine Bilder. */
    @Override
    public byte[] loadViewImage(String src) throws KnowledgeSourceException {
        throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Die Outlook-Ansicht hat keine Bilder");
    }

    private Listing list(Path file, PSTMessage message, KnowledgeResourceId messageId)
            throws KnowledgeSourceException {
        int count = attachmentCount(messageId, message);
        if (count > MAX_ATTACHMENTS) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, messageId + " hat mehr als " + MAX_ATTACHMENTS
                    + " Anhänge");
        }
        Listing listing = new Listing();
        Map<Integer, Integer> seen = new HashMap<Integer, Integer>();
        for (int i = 0; i < count; i++) {
            PSTAttachment attachment = readableAttachment(message, i);
            if (attachment == null) {
                listing.warnings.add("Anhang " + (i + 1) + ": Tabellenzeile nicht lesbar; Kennung nach Position");
                listing.resources.add(unreadableResource(file, message, messageId, i));
                continue;
            }
            Integer before = seen.get(attachment.getAttachNum());
            int occurrence = before == null ? 0 : before + 1;
            seen.put(attachment.getAttachNum(), occurrence);
            if (occurrence > 0) {
                listing.warnings.add("Anhang " + (i + 1) + ": Anhangsnummer in der Nachricht doppelt; Kennung mit "
                        + "Zähler in Listenreihenfolge");
            }
            listing.resources.add(attachmentResource(file, message, messageId, attachment, occurrence));
        }
        return listing;
    }

    private static int attachmentCount(KnowledgeResourceId messageId, PSTMessage message)
            throws KnowledgeSourceException {
        try {
            return OutlookMessageWriter.attachmentCount(message);
        } catch (IOException | RuntimeException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, messageId + ": Anhangstabelle nicht lesbar ("
                    + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static PSTAttachment readableAttachment(PSTMessage message, int index) {
        try {
            return message.getAttachment(index);
        } catch (IOException | PSTException | RuntimeException e) {
            return null;
        }
    }

    /** Eine Tabellenzeile ohne lesbaren Anhang: gelistet, damit nichts fehlt; der Abruf scheitert. */
    private KnowledgeResource unreadableResource(Path file, PSTMessage message, KnowledgeResourceId messageId,
                                                 int position) {
        String fileName = file.getFileName().toString();
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("mail.file", fileName);
        return KnowledgeResource.builder(attachmentId(fileName, message.getDescriptorNodeId(), "@" + position),
                        sourceId)
                .title("Anhang " + (position + 1))
                .contentType(PageView.Attachment.UNKNOWN_MEDIA_TYPE)
                .revision(KnowledgeRevision.of(Instant.EPOCH, "unreadable"))
                .parentId(messageId)
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private KnowledgeResource attachmentResource(Path file, PSTMessage message, KnowledgeResourceId messageId,
                                                 PSTAttachment attachment, int occurrence) {
        String fileName = file.getFileName().toString();
        int number = attachment.getAttachNum();
        int method = attachment.getAttachMethod();
        boolean embedded = method == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED;
        String title = attachmentTitle(attachment, embedded);
        java.util.Date time = attachment.getModificationTime() != null ? attachment.getModificationTime()
                : message.getMessageDeliveryTime() != null ? message.getMessageDeliveryTime()
                : message.getLastModificationTime();
        Instant modified = time == null ? Instant.EPOCH : time.toInstant();
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("mail.file", fileName);
        metadata.put(METADATA_ATTACHMENT_NUMBER, String.valueOf(number));
        metadata.put(METADATA_ATTACHMENT_METHOD, String.valueOf(method));
        if (method == PSTAttachment.ATTACHMENT_METHOD_BY_VALUE) {
            long size = fileSize(attachment);
            if (size >= 0) {
                metadata.put(KnowledgeMetadata.FILE_SIZE, String.valueOf(size));
            }
        }
        // Ein angehängtes Element wird erst beim Abruf zur Mail; ändert sich die Darstellung, ändert sich die Revision.
        String version = (embedded ? OutlookMessageWriter.VERSION + "-" : "") + attachment.getAttachSize();
        String key = occurrence == 0 ? String.valueOf(number) : number + "/" + occurrence;
        return KnowledgeResource.builder(attachmentId(fileName, message.getDescriptorNodeId(), key), sourceId)
                .title(title)
                .contentType(embedded ? EMBEDDED_MEDIA_TYPE : mediaType(attachment, title))
                .revision(KnowledgeRevision.of(modified, version))
                .parentId(messageId)
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private SourceFile readAttachment(KnowledgeResourceId attachment, long maxBytes) throws KnowledgeSourceException {
        return withAttachment(attachment, (resource, found) -> {
            String size = resource.metadata().asMap().get(KnowledgeMetadata.FILE_SIZE);
            if (size != null && Long.parseLong(size) > maxBytes) {
                throw new ResponseTooLargeException("Anhang größer als " + maxBytes + " Bytes", maxBytes);
            }
            Bounded content = new Bounded(Math.min(maxBytes, Integer.MAX_VALUE - 8L));
            try {
                transfer(attachment, found, content);
            } catch (KnowledgeSourceException e) {
                if (content.exceeded) {
                    throw new ResponseTooLargeException("Anhang größer als " + maxBytes + " Bytes", maxBytes);
                }
                throw e;
            }
            return new SourceFile(resource, content.bytes.toByteArray());
        });
    }

    /** Schreibt die Bytes eines Anhangs; Lesefehler der Postfachdatei und Schreibfehler des Ziels getrennt. */
    private static void transfer(KnowledgeResourceId id, PSTAttachment attachment, OutputStream sink)
            throws KnowledgeSourceException {
        Guarded target = new Guarded(sink);
        int method = attachment.getAttachMethod();
        try {
            if (method == PSTAttachment.ATTACHMENT_METHOD_BY_VALUE) {
                InputStream in = fileStream(attachment);
                if (in == null) {
                    throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + " hat keine Dateibytes");
                }
                try {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        target.write(buffer, 0, n);
                    }
                } finally {
                    in.close();
                }
            } else if (method == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) {
                PSTMessage inner = attachment.getEmbeddedPSTMessage();
                if (inner == null) {
                    throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + " ohne lesbares Element");
                }
                // Nie gekürzt: zu tief verschachtelt liefert das Element nichts, steht aber in der Liste.
                if (OutlookMessageWriter.depth(inner) > OutlookMessageWriter.MAX_DEPTH) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + ": angehängte Elemente tiefer als "
                            + OutlookMessageWriter.MAX_DEPTH + " Ebenen");
                }
                OutlookMessageWriter.write(inner, target);
            } else {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + ": Anhangsart " + method
                        + " hat keine Datei in der Postfachdatei");
            }
            target.flush();
        } catch (IOException e) {
            if (target.failed) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Anhang nicht geschrieben ("
                        + e.getClass().getSimpleName() + ")", e);
            }
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + " nicht lesbar ("
                    + e.getClass().getSimpleName() + ")", e);
        } catch (PSTException | RuntimeException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + " nicht lesbar ("
                    + e.getClass().getSimpleName() + ")", e);
        }
    }

    private <T> T withAttachment(KnowledgeResourceId id, AttachmentAction<T> action) throws KnowledgeSourceException {
        String[] parts = attachmentParts(id);
        if (parts == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + " ist kein Anhang der Quelle " + sourceId);
        }
        KnowledgeResourceId messageId = KnowledgeResourceId.of(SCHEME, locator + "/" + parts[0] + "/" + parts[1]);
        if (parts[2].startsWith("@")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + ": Tabellenzeile nicht lesbar");
        }
        int number = Integer.parseInt(parts[2]);
        int wanted = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
        return withMessage(messageId, (file, pst, message) -> {
            int count = attachmentCount(messageId, message);
            int occurrence = 0;
            for (int i = 0; i < count; i++) {
                PSTAttachment attachment = readableAttachment(message, i);
                // Wie in der Liste: bei doppelter Nummer zählt die Reihenfolge.
                if (attachment != null && attachment.getAttachNum() == number && occurrence++ == wanted) {
                    return action.apply(attachmentResource(file, message, messageId, attachment, wanted),
                            attachment);
                }
            }
            throw new KnowledgeSourceException(Kind.NOT_FOUND, id + " nicht gefunden");
        });
    }

    private <T> T withMessage(KnowledgeResourceId resourceId, MessageAction<T> action)
            throws KnowledgeSourceException {
        String[] parts = partsOf(resourceId);
        Path file = mailFile(parts[0]);
        long nodeId = Long.parseLong(parts[1]);
        PSTFile pst = open(file);
        try {
            PSTObject object;
            try {
                object = PSTObject.detectAndLoadPSTObject(pst, nodeId);
            } catch (IOException | PSTException | RuntimeException e) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, resourceId + " nicht gefunden", e);
            }
            if (!(object instanceof PSTMessage)) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, resourceId + " ist keine Nachricht");
            }
            return action.apply(file, pst, (PSTMessage) object);
        } finally {
            close(pst);
        }
    }

    /** Die Nachricht zu einer lokalen ID ({@code <Dateiname>/<Node-ID>}) oder zur ganzen Ressourcen-ID. */
    private KnowledgeResourceId messageIdOf(String localId) throws KnowledgeSourceException {
        if (localId == null || localId.trim().isEmpty()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine ID einer Nachricht");
        }
        try {
            KnowledgeResourceId id = localId.startsWith(SCHEME + ":") ? KnowledgeResourceId.of(localId)
                    : KnowledgeResourceId.of(SCHEME, locator + "/" + localId);
            partsOf(id);
            return id;
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, localId + " gehört nicht zur Quelle " + sourceId, e);
        }
    }

    /** Die Ressourcen-ID zu einer Anhangskennung (letzter Teil der ID) oder der ganzen ID. */
    private KnowledgeResourceId attachmentIdOf(String value) throws KnowledgeSourceException {
        try {
            if (value != null && !value.startsWith(SCHEME + ":")) {
                String[] key = decodeKey(value);
                if (key != null) {
                    return KnowledgeResourceId.of(SCHEME, locator + "/" + key[0] + "/" + key[1] + "/" + value);
                }
            }
            return KnowledgeResourceId.of(value);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Kein Anhang der Quelle " + sourceId, e);
        }
    }

    /** {@code mail:<Quell-ID>/<Dateiname>/<Node-ID>/<Base64url von Dateiname/Node-ID/Schlüssel>}. */
    private KnowledgeResourceId attachmentId(String fileName, long nodeId, String key) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString((fileName + "/" + nodeId + "/" + key)
                .getBytes(StandardCharsets.UTF_8));
        return KnowledgeResourceId.of(SCHEME, locator + "/" + fileName + "/" + nodeId + "/" + token);
    }

    /**
     * Dateiname, Node-ID, Anhangsnummer (oder {@code @<Position>}) und gegebenenfalls Zähler einer Anhangs-ID dieser
     * Quelle, sonst {@code null}; ohne Dateizugriff.
     */
    private String[] attachmentParts(KnowledgeResourceId id) {
        String prefix = SCHEME + ":" + locator + "/";
        String value = id == null ? "" : id.value();
        if (!value.startsWith(prefix)) {
            return null;
        }
        String[] parts = value.substring(prefix.length()).split("/", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !digits(parts[1])) {
            return null;
        }
        String[] key = decodeKey(parts[2]);
        if (key == null || !key[0].equals(parts[0]) || !key[1].equals(parts[1])) {
            return null;
        }
        return key;
    }

    /** Die Teile einer Kennung, wenn sie kanonisch kodiert und wohlgeformt ist, sonst {@code null}. */
    private static String[] decodeKey(String token) {
        String text;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            text = new String(bytes, StandardCharsets.UTF_8);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                    .equals(token)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] parts = text.split("/", -1);
        if (parts.length < 3 || parts.length > 4 || parts[0].isEmpty() || !digits(parts[1])) {
            return null;
        }
        if (parts[2].startsWith("@")) {
            return parts.length == 3 && digits(parts[2].substring(1)) ? parts : null;
        }
        if (!canonicalInt(parts[2]) || parts.length == 4 && !(digits(parts[3]) && canonicalInt(parts[3])
                && Integer.parseInt(parts[3]) > 0)) {
            return null;
        }
        return parts;
    }

    private static boolean canonicalInt(String value) {
        try {
            return String.valueOf(Integer.parseInt(value)).equals(value);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean digits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String lastSegment(KnowledgeResourceId id) {
        String value = id.value();
        return value.substring(value.lastIndexOf('/') + 1);
    }

    /** Der Name des Anhangs, wie ihn die Postfachdatei nennt (lang, kurz, Anzeigename), sonst leer. */
    static String sourceName(PSTAttachment attachment) {
        String name = OutlookMessageWriter.oneLine(attachment.getLongFilename());
        if (name.isEmpty()) {
            name = OutlookMessageWriter.oneLine(attachment.getFilename());
        }
        if (name.isEmpty()) {
            name = OutlookMessageWriter.oneLine(attachment.getDisplayName());
        }
        return name;
    }

    /** Anzeigename eines Anhangs; nur Anzeige, nie Kennung. Ein angehängtes Element endet auf {@code .eml}. */
    static String attachmentTitle(PSTAttachment attachment, boolean embedded) {
        String title = sourceName(attachment);
        if (!embedded) {
            return title.isEmpty() ? "Anhang " + attachment.getAttachNum() : title;
        }
        if (title.isEmpty()) {
            title = "Nachricht " + attachment.getAttachNum();
        }
        return title.toLowerCase(Locale.ROOT).endsWith(".eml") ? title : title + ".eml";
    }

    /** Medientyp aus der Angabe der Postfachdatei, sonst aus der Dateiendung, sonst unbekannt. */
    static String mediaType(PSTAttachment attachment, String title) {
        String tag = OutlookMessageWriter.oneLine(attachment.getMimeTag()).toLowerCase(Locale.ROOT);
        if (!tag.isEmpty() && tag.indexOf('/') > 0) {
            return tag;
        }
        int dot = title.lastIndexOf('.');
        String type = dot < 0 ? null : MEDIA_TYPES.get(title.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type == null ? PageView.Attachment.UNKNOWN_MEDIA_TYPE : type;
    }

    /** Der Datenstrom eines Anhangs mit Dateibytes oder {@code null}, wenn die Postfachdatei keine Daten hat. */
    static InputStream fileStream(PSTAttachment attachment) throws IOException, PSTException {
        try {
            return attachment.getFileInputStream();
        } catch (NullPointerException e) {
            // java-libpst: ohne Datenfeld (0x3701) gibt es keinen Strom.
            return null;
        }
    }

    private static long fileSize(PSTAttachment attachment) {
        try {
            return attachment.getFilesize();
        } catch (IOException | PSTException | RuntimeException e) {
            return -1;
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final Map<String, String> MEDIA_TYPES;

    static {
        Map<String, String> types = new HashMap<String, String>();
        types.put("pdf", "application/pdf");
        types.put("doc", "application/msword");
        types.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        types.put("xls", "application/vnd.ms-excel");
        types.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        types.put("ppt", "application/vnd.ms-powerpoint");
        types.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        types.put("odt", "application/vnd.oasis.opendocument.text");
        types.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        types.put("odp", "application/vnd.oasis.opendocument.presentation");
        types.put("rtf", "application/rtf");
        types.put("txt", "text/plain");
        types.put("csv", "text/csv");
        types.put("md", "text/markdown");
        types.put("htm", "text/html");
        types.put("html", "text/html");
        types.put("xml", "application/xml");
        types.put("json", "application/json");
        types.put("zip", "application/zip");
        types.put("msg", "application/vnd.ms-outlook");
        types.put("eml", EMBEDDED_MEDIA_TYPE);
        types.put("png", "image/png");
        types.put("jpg", "image/jpeg");
        types.put("jpeg", "image/jpeg");
        types.put("gif", "image/gif");
        types.put("bmp", "image/bmp");
        types.put("tif", "image/tiff");
        types.put("tiff", "image/tiff");
        types.put("svg", "image/svg+xml");
        MEDIA_TYPES = Collections.unmodifiableMap(types);
    }

    private interface MessageAction<T> {
        T apply(Path file, PSTFile pst, PSTMessage message) throws KnowledgeSourceException;
    }

    private interface AttachmentAction<T> {
        T apply(KnowledgeResource resource, PSTAttachment attachment) throws KnowledgeSourceException;
    }

    private static final class Listing {
        final List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>();
        final List<String> warnings = new ArrayList<String>();
    }

    /** Merkt sich, ob ein Fehler beim Schreiben ins Ziel entstand (nicht beim Lesen der Postfachdatei). */
    private static final class Guarded extends FilterOutputStream {

        boolean failed;

        Guarded(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }
    }

    /** Sammelt Bytes bis zu einer Grenze; darüber bricht das Schreiben ab. */
    private static final class Bounded extends OutputStream {

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit;
        boolean exceeded;

        Bounded(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            bytes.write(b, off, len);
        }

        private void count(int n) throws IOException {
            if (bytes.size() + (long) n > limit) {
                exceeded = true;
                throw new IOException("Grenze von " + limit + " Bytes überschritten");
            }
        }
    }

    // ── Dateien ────────────────────────────────────────────────────────────────────────────────────────────────

    private List<Path> mailFiles() throws KnowledgeSourceException {
        if (Files.isRegularFile(mailbox)) {
            return Collections.singletonList(mailbox);
        }
        if (!Files.isDirectory(mailbox)) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Postfach nicht gefunden: " + mailbox);
        }
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> entries = Files.list(mailbox)) {
            entries.filter(p -> Files.isRegularFile(p) && isMailFile(p)).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Ordner nicht lesbar: " + mailbox, e);
        }
        return files;
    }

    private static boolean isMailFile(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pst") || name.endsWith(".ost");
    }

    private Path mailFile(String name) throws KnowledgeSourceException {
        Path directory = Files.isRegularFile(mailbox) ? mailbox.getParent() : mailbox;
        Path file = directory == null ? null : directory.resolve(name).normalize();
        boolean owned = file != null && file.getParent() != null && file.getParent().equals(directory)
                && isMailFile(file) && (!Files.isRegularFile(mailbox) || file.equals(mailbox));
        if (!owned) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, name + " gehört nicht zur Quelle " + sourceId);
        }
        if (!Files.isRegularFile(file)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Postfachdatei nicht gefunden: " + file);
        }
        return file;
    }

    private PSTFile open(Path file) throws KnowledgeSourceException {
        try {
            return new PSTFile(file.toFile());
        } catch (IOException | PSTException | RuntimeException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Postfachdatei nicht lesbar: " + file
                    + " (" + e.getMessage() + ")", e);
        }
    }

    private static void close(PSTFile pst) {
        try {
            pst.close();
        } catch (IOException e) {
            // nur Lesezugriff
        }
    }

    // ── Ordnerlauf (MainframeMate MailSourceScanner.scanFolderStreaming) ───────────────────────────────────────

    /** @return {@code false}, wenn die Obergrenze erreicht ist */
    private boolean scanFolder(PSTFolder folder, String path, Path file, int depth, SourceScope scope,
                               List<KnowledgeResource> found) {
        if (depth > MAX_FOLDER_DEPTH) {
            return true;
        }
        try {
            if (folder.getContentCount() > 0) {
                PSTObject child = folder.getNextChild();
                while (child != null) {
                    if (child instanceof PSTMessage) {
                        PSTMessage message = (PSTMessage) child;
                        try {
                            if (!shouldSkipMessageClass(message.getMessageClass())) {
                                if (found.size() >= scope.maxResources()) {
                                    return false;
                                }
                                found.add(resource(file, message, path));
                            }
                        } catch (RuntimeException e) {
                            LOG.log(Level.FINE, "Outlook " + sourceId + ": Nachricht übersprungen in " + path, e);
                        }
                    }
                    try {
                        child = folder.getNextChild();
                    } catch (RuntimeException e) {
                        break;
                    }
                }
            }
        } catch (IOException | PSTException | RuntimeException e) {
            LOG.log(Level.FINE, "Outlook " + sourceId + ": Ordner nicht lesbar: " + path, e);
        }
        if (depth >= scope.maxDepth()) {
            return true;
        }
        try {
            for (PSTFolder sub : folder.getSubFolders()) {
                String subPath = path + "/" + sub.getDisplayName();
                if (!scanFolder(sub, subPath, file, depth + 1, scope, found)) {
                    return false;
                }
            }
        } catch (IOException | PSTException | RuntimeException e) {
            LOG.log(Level.FINE, "Outlook " + sourceId + ": Unterordner nicht lesbar: " + path, e);
        }
        return true;
    }

    /**
     * Nachrichtenklassen ohne Volltextwert (aus MainframeMate): Lese-/Zustellberichte, Kontakte,
     * Konfigurations- und Formularelemente.
     */
    static boolean shouldSkipMessageClass(String msgClass) {
        if (msgClass == null || msgClass.isEmpty()) {
            return false;
        }
        String upper = msgClass.toUpperCase(Locale.ROOT);
        return upper.startsWith("REPORT.")
                || upper.startsWith("IPM.ABCHPERSON")
                || upper.equals("IPM.CONTACT")
                || upper.startsWith("IPM.CONFIGURATION")
                || upper.startsWith("IPM.MICROSOFT.")
                || upper.startsWith("IPM.INFOPATH");
    }

    // ── Ressourcen und Text ────────────────────────────────────────────────────────────────────────────────────

    private KnowledgeResource resource(Path file, PSTMessage message, String folder) {
        String fileName = file.getFileName().toString();
        long nodeId = message.getDescriptorNodeId();
        java.util.Date time = message.getMessageDeliveryTime() != null ? message.getMessageDeliveryTime()
                : message.getLastModificationTime();
        Instant modified = time == null ? Instant.EPOCH : time.toInstant();
        String subject = safe(message.getSubject()).trim();
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("mail.file", fileName);
        if (folder != null && !folder.isEmpty()) {
            metadata.put("mail.folder", folder);
        }
        String sender = safe(message.getSenderName()).trim();
        if (!sender.isEmpty()) {
            metadata.put("mail.from", sender);
        }
        return KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + fileName + "/" + nodeId),
                        sourceId)
                .title(subject.isEmpty() ? "(ohne Betreff)" : subject)
                .contentType(KnowledgeResource.DEFAULT_CONTENT_TYPE)
                .revision(KnowledgeRevision.of(modified, String.valueOf(message.getMessageSize())))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    /** Indexierbarer Text wie MainframeMate {@code MailSourceScanner.buildIndexableText}. */
    static String buildIndexableText(PSTMessage msg, String folderPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(safe(msg.getSubject()).trim().isEmpty() ? "(ohne Betreff)" : msg.getSubject().trim())
                .append("\n\n");
        sb.append("Betreff: ").append(safe(msg.getSubject())).append("\n");
        sb.append("Von: ").append(safe(msg.getSenderName()));
        String email = msg.getSenderEmailAddress();
        if (email != null && !email.isEmpty()) {
            sb.append(" <").append(email).append(">");
        }
        sb.append("\n");
        sb.append("An: ").append(safe(msg.getDisplayTo())).append("\n");
        if (msg.getMessageDeliveryTime() != null) {
            sb.append("Datum: ").append(msg.getMessageDeliveryTime().toInstant()).append("\n");
        }
        sb.append("Ordner: ").append(safe(folderPath)).append("\n\n");
        try {
            String body = msg.getBody();
            if (body != null && !body.isEmpty()) {
                sb.append(body);
            }
        } catch (RuntimeException e) {
            sb.append("[Fehler beim Lesen des Nachrichtentexts]");
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    // ── Navigation (MainframeMate MailSourceScanner.findContentRoot/navigateToFolder) ─────────────────────────

    private static String folderPath(String startPoint) {
        String trimmed = startPoint == null ? "" : startPoint.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "/".equals(trimmed)) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /** Ordnerpfad über die Eltern-Descriptoren bis zur Inhaltswurzel ({@code IPM_SUBTREE}). */
    private static String folderOf(PSTFile pst, PSTMessage message) {
        try {
            List<String> names = new ArrayList<String>();
            long parentId = message.getDescriptorNode().parentDescriptorIndexIdentifier;
            while (parentId != 0 && names.size() < MAX_FOLDER_DEPTH) {
                PSTObject parent = PSTObject.detectAndLoadPSTObject(pst, parentId);
                if (!(parent instanceof PSTFolder)) {
                    break;
                }
                String name = ((PSTFolder) parent).getDisplayName();
                if (name == null || name.isEmpty() || "IPM_SUBTREE".equalsIgnoreCase(name)) {
                    break;
                }
                names.add(0, name);
                long next = parent.getDescriptorNode().parentDescriptorIndexIdentifier;
                if (next == parentId) {
                    break;
                }
                parentId = next;
            }
            StringBuilder sb = new StringBuilder();
            for (String name : names) {
                sb.append('/').append(name);
            }
            return sb.toString();
        } catch (IOException | PSTException | RuntimeException e) {
            return "";
        }
    }

    private static PSTFolder navigateToFolder(PSTFile pst, PSTFolder contentRoot, String folderPath) {
        try {
            PSTFolder result = navigateFromBase(contentRoot, folderPath);
            if (result != null) {
                return result;
            }
            PSTFolder root = pst.getRootFolder();
            for (PSTFolder l1 : root.getSubFolders()) {
                result = navigateFromBase(l1, folderPath);
                if (result != null) {
                    return result;
                }
            }
            return navigateFromBase(root, folderPath);
        } catch (IOException | PSTException | RuntimeException e) {
            return null;
        }
    }

    private static PSTFolder findContentRoot(PSTFile pst) throws KnowledgeSourceException {
        PSTFolder root;
        try {
            root = pst.getRootFolder();
        } catch (IOException | PSTException | RuntimeException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Postfach ohne lesbaren Wurzelordner", e);
        }
        PSTFolder best = null;
        int bestChildCount = -1;
        try {
            for (PSTFolder l1 : root.getSubFolders()) {
                if ("IPM_SUBTREE".equalsIgnoreCase(l1.getDisplayName())) {
                    int cc = countContentChildren(l1);
                    if (cc > bestChildCount) {
                        best = l1;
                        bestChildCount = cc;
                    }
                }
                try {
                    for (PSTFolder l2 : l1.getSubFolders()) {
                        if ("IPM_SUBTREE".equalsIgnoreCase(l2.getDisplayName())) {
                            int cc = countContentChildren(l2);
                            if (cc > bestChildCount) {
                                best = l2;
                                bestChildCount = cc;
                            }
                        }
                        try {
                            for (PSTFolder l3 : l2.getSubFolders()) {
                                if ("IPM_SUBTREE".equalsIgnoreCase(l3.getDisplayName())) {
                                    int cc = countContentChildren(l3);
                                    if (cc > bestChildCount) {
                                        best = l3;
                                        bestChildCount = cc;
                                    }
                                }
                            }
                        } catch (IOException | PSTException | RuntimeException ignored) {
                            // Ebene 3 nicht lesbar
                        }
                    }
                } catch (IOException | PSTException | RuntimeException ignored) {
                    // Ebene 2 nicht lesbar
                }
            }
        } catch (IOException | PSTException | RuntimeException e) {
            return root;
        }
        return best != null ? best : root;
    }

    private static int countContentChildren(PSTFolder folder) {
        try {
            int count = 0;
            for (PSTFolder sub : folder.getSubFolders()) {
                if (sub.getContentCount() > 0 || !sub.getSubFolders().isEmpty()) {
                    count++;
                }
            }
            return count;
        } catch (IOException | PSTException | RuntimeException e) {
            return 0;
        }
    }

    private static PSTFolder navigateFromBase(PSTFolder base, String folderPath) {
        PSTFolder current = base;
        for (String part : folderPath.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                PSTFolder found = null;
                for (PSTFolder sub : current.getSubFolders()) {
                    if (part.equals(sub.getDisplayName())) {
                        found = sub;
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                current = found;
            } catch (IOException | PSTException | RuntimeException e) {
                return null;
            }
        }
        return current;
    }

    private String[] partsOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId == null ? "" : resourceId.value();
        String rest = value.startsWith(prefix) ? value.substring(prefix.length()) : "";
        int slash = rest.lastIndexOf('/');
        if (slash <= 0 || slash == rest.length() - 1 || rest.substring(0, slash).contains("/")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        String nodeId = rest.substring(slash + 1);
        for (int i = 0; i < nodeId.length(); i++) {
            char c = nodeId.charAt(i);
            if (c < '0' || c > '9') {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
            }
        }
        return new String[] { rest.substring(0, slash), nodeId };
    }

}
