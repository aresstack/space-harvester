package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.pff.PSTException;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * </ul>
 */
final class OutlookKnowledgeSource implements KnowledgeSourcePort {

    static final String SCHEME = "mail";

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
            PSTMessage message = (PSTMessage) object;
            String folder = folderOf(pst, message);
            KnowledgeResource resource = resource(file, message, folder);
            return KnowledgeDocument.of(resource, buildIndexableText(message, folder));
        } finally {
            close(pst);
        }
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
