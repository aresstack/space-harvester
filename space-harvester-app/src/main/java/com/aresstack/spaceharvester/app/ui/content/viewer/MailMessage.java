package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Address;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Group;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Eine E-Mail, wie der Anhang-Viewer sie zeigt: Kopf (Von, An, Cc, Datum, Betreff), der Text formatiert und die
 * Anlagen mit ihren Bytes. Liest Outlook-Nachrichten ({@code .msg}) über Apache POI HSMF und Internet-Nachrichten
 * ({@code .eml}) über Apache James Mime4j, beides reines Java (Angelo 24.11.2026: der eine Viewer kann E-Mails, überall).
 *
 * <p>Der HTML-Text wird bereinigt (keine Skripte, keine Formulare, keine Bilder aus dem Netz); eingebettete Bilder
 * ({@code cid:}) zeigt der Viewer aus dem Speicher. Ändert nichts, schickt nichts.</p>
 */
public final class MailMessage {

    /** Adresse der eingebetteten Bilder im HTML; der Viewer findet sie in seinem Bildspeicher unter diesem Präfix. */
    static final String IMAGE_BASE = "http://mail.invalid/cid/";
    /** Verweis auf eine Anlage im HTML, gefolgt von ihrer Nummer; ein Klick öffnet sie. */
    static final String ATTACHMENT_LINK = "http://mail.invalid/anlage/";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);
    private static final int MAX_DEPTH = 8;

    /**
     * Eine Anlage der E-Mail.
     *
     * @param index     Stelle in {@link #attachments()}, von 0
     * @param name      Dateiname mit Endung (die Endung wählt den Viewer)
     * @param mediaType Medientyp, leer wenn unbekannt
     * @param data      Inhalt
     * @param contentId Content-ID eines eingebetteten Bildes ohne spitze Klammern, sonst leer
     */
    public record Attachment(int index, String name, String mediaType, byte[] data, String contentId) {

        public Attachment {
            Objects.requireNonNull(name, "name");
            mediaType = mediaType == null ? "" : mediaType;
            data = data == null ? new byte[0] : data;
            contentId = contentId == null ? "" : contentId;
        }

        /** Als Anhang für den Viewer; {@code key} ist die Stelle. */
        public ReaderAttachment asReaderAttachment() {
            return new ReaderAttachment("anlage-" + index, name, mediaType, data.length);
        }
    }

    private final String from;
    private final String to;
    private final String cc;
    private final String date;
    private final ZonedDateTime sent;
    private final String subject;
    private final String html;
    private final String text;
    private final List<Attachment> attachments;

    private MailMessage(String from, String to, String cc, ZonedDateTime sent, String subject, String html,
                        String text, List<Attachment> attachments) {
        this.from = blankToEmpty(from);
        this.to = blankToEmpty(to);
        this.cc = blankToEmpty(cc);
        this.sent = sent;
        this.date = sent == null ? "" : WHEN.format(sent);
        this.subject = blankToEmpty(subject);
        this.html = html;
        this.text = text == null ? "" : text;
        this.attachments = List.copyOf(attachments);
    }

    /** Ob {@code fileName} (oder, ohne Endung, der Medientyp) eine E-Mail ist: {@code .msg} oder {@code .eml}. */
    public static boolean supports(String fileName, String mediaType) {
        String extension = ReaderAttachmentKind.extension(fileName);
        if ("msg".equals(extension) || "eml".equals(extension)) {
            return true;
        }
        if (!extension.isEmpty()) {
            return false;
        }
        String type = ReaderAttachmentKind.baseType(mediaType);
        return "message/rfc822".equals(type) || "application/vnd.ms-outlook".equals(type);
    }

    /** Liest die E-Mail; Outlook erkennt sie an der OLE2-Signatur, sonst MIME. */
    public static MailMessage read(String fileName, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Leere Datei (0 Bytes).");
        }
        return isOle2(bytes) ? readMsg(bytes) : readEml(bytes);
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String cc() {
        return cc;
    }

    public String date() {
        return date;
    }

    public String subject() {
        return subject;
    }

    /** Der HTML-Text, roh wie gesendet; {@code null}, wenn die E-Mail nur Text hat. */
    public String html() {
        return html;
    }

    /** Der reine Text (aus dem HTML, wenn es keinen gibt). */
    public String text() {
        return text.isBlank() && html != null ? Jsoup.parse(html).wholeText().strip() : text;
    }

    /** Die Anlagen ohne eingebettete Bilder, die im Text stehen. */
    public List<Attachment> attachments() {
        return attachments.stream().filter(a -> !inline(a)).toList();
    }

    /** Alle Anlagen, auch die eingebetteten Bilder. */
    List<Attachment> allAttachments() {
        return attachments;
    }

    private boolean inline(Attachment attachment) {
        return !attachment.contentId().isEmpty() && html != null
                && html.contains("cid:" + attachment.contentId())
                && ReaderAttachmentKind.of(attachment.mediaType(), attachment.name()) == ReaderAttachmentKind.IMAGE;
    }

    /** Die E-Mail als Dokument für den Viewer: Kopf, Anlagenliste (anklickbar), Text mit eingebetteten Bildern. */
    WordDocumentPreview.Rendered render() {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        StringBuilder page = new StringBuilder("<html><body>");
        // Betreff groß, aber keine Überschrift: die Gliederung zeigt nur Überschriften aus dem Text selbst.
        page.append("<p style=\"font-size: 17pt\"><b>").append(escape(subject.isEmpty() ? "(ohne Betreff)"
                : subject)).append("</b></p>");
        page.append("<table cellspacing=\"0\" cellpadding=\"2\" class=\"kopf\">");
        row(page, "Von", from);
        row(page, "An", to);
        row(page, "Cc", cc);
        row(page, "Datum", date);
        page.append("</table>");
        List<Attachment> listed = attachments();
        if (!listed.isEmpty()) {
            page.append("<p><b>").append(listed.size() == 1 ? "1 Anlage" : listed.size() + " Anlagen")
                    .append(":</b> ");
            for (int i = 0; i < listed.size(); i++) {
                Attachment attachment = listed.get(i);
                if (i > 0) {
                    page.append(" &middot; ");
                }
                page.append("<a href=\"").append(ATTACHMENT_LINK).append(attachment.index()).append("\">")
                        .append(escape(attachment.name())).append("</a> (")
                        .append(escape(ReaderAttachmentKind.label(attachment.mediaType(), attachment.name())));
                String size = ReaderAttachment.size(attachment.data().length);
                if (!size.isEmpty()) {
                    page.append(", ").append(escape(size));
                }
                page.append(')');
            }
            page.append("</p>");
        }
        page.append("<hr>");
        if (html != null && !html.isBlank()) {
            page.append(cleanBody(images));
        } else {
            for (String paragraph : text.split("\\r?\\n\\s*\\r?\\n")) {
                page.append("<p>").append(escape(paragraph.strip()).replaceAll("\\r?\\n", "<br>")).append("</p>");
            }
        }
        page.append("</body></html>");
        return new WordDocumentPreview.Rendered(page.toString(), Map.copyOf(images), plainText(), 0);
    }

    /** Kopf und Text als reiner Text, so wie die KI die E-Mail liest. */
    String plainText() {
        StringBuilder plain = new StringBuilder();
        line(plain, "Betreff", subject);
        line(plain, "Von", from);
        line(plain, "An", to);
        line(plain, "Cc", cc);
        line(plain, "Datum", date);
        List<Attachment> listed = attachments();
        if (!listed.isEmpty()) {
            line(plain, "Anlagen", String.join(", ", listed.stream().map(Attachment::name).toList()));
        }
        plain.append('\n').append(text());
        return plain.toString().strip();
    }

    /** Nur der Inhalt des HTML-Körpers, bereinigt; eingebettete Bilder aus dem Speicher, Bilder aus dem Netz nicht. */
    private String cleanBody(Map<String, BufferedImage> images) {
        Document parsed = Jsoup.parse(html);
        for (Element image : parsed.select("img")) {
            String src = image.attr("src");
            if (src.regionMatches(true, 0, "cid:", 0, 4)) {
                String id = src.substring(4);
                BufferedImage decoded = attachments.stream().filter(a -> a.contentId().equals(id)).findFirst()
                        .map(a -> decode(a.data())).orElse(null);
                if (decoded != null) {
                    String address = IMAGE_BASE + images.size();
                    images.put(address, decoded);
                    image.attr("src", address);
                    continue;
                }
            }
            image.replaceWith(new org.jsoup.nodes.TextNode(image.attr("alt").isBlank() ? "" : "[" + image.attr("alt")
                    + "]"));
        }
        Safelist allowed = Safelist.relaxed().addTags("font", "center", "hr").addAttributes(":all", "align",
                        "bgcolor", "color", "face", "size", "width", "height", "border", "cellpadding", "cellspacing",
                        "valign");
        String body = Jsoup.clean(parsed.body().html(), "http://mail.invalid/", allowed);
        // Nur die eigenen Bildadressen bleiben; Bilder aus dem Netz holt der Viewer nie.
        Document cleaned = Jsoup.parseBodyFragment(body);
        for (Element image : cleaned.select("img")) {
            if (!image.attr("src").startsWith(IMAGE_BASE)) {
                image.remove();
            }
        }
        return cleaned.body().html();
    }

    private static BufferedImage decode(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- Outlook (.msg) ---------------------------------------------------------------------------------------------

    private static MailMessage readMsg(byte[] bytes) throws IOException {
        try (MAPIMessage message = new MAPIMessage(new ByteArrayInputStream(bytes))) {
            return fromMapi(message, 0);
        }
    }

    private static MailMessage fromMapi(MAPIMessage message, int depth) throws IOException {
        String html = optional(message::getHtmlBody);
        String text = optional(message::getTextBody);
        if ((text == null || text.isBlank()) && html == null) {
            text = rtfText(optional(message::getRtfBody));
        }
        Calendar when = null;
        try {
            when = message.getMessageDate();
        } catch (ChunkNotFoundException | RuntimeException e) {
            // ohne Datum
        }
        List<Attachment> attachments = new ArrayList<>();
        AttachmentChunks[] chunks = message.getAttachmentFiles();
        for (AttachmentChunks chunk : chunks == null ? new AttachmentChunks[0] : chunks) {
            int index = attachments.size();
            String mime = value(chunk.getAttachMimeTag());
            String contentId = stripAngles(value(chunk.getAttachContentId()));
            if (chunk.isEmbeddedMessage() && depth < MAX_DEPTH) {
                // Eine weitergeleitete Nachricht: als eigene E-Mail-Anlage, der Viewer öffnet sie wieder als E-Mail.
                MAPIMessage inner = chunk.getEmbeddedMessage();
                String name = firstNonBlank(value(chunk.getAttachDisplayName()), optional(inner::getSubject),
                        "Nachricht " + (index + 1));
                byte[] data = embeddedBytes(inner, depth);
                if (data != null) {
                    attachments.add(new Attachment(index, safeName(name, ".eml"), "message/rfc822", data,
                            contentId));
                }
                continue;
            }
            byte[] data = chunk.getEmbeddedAttachmentObject();
            if (data == null) {
                continue;
            }
            String name = firstNonBlank(value(chunk.getAttachLongFileName()), value(chunk.getAttachFileName()),
                    value(chunk.getAttachDisplayName()), "Anlage " + (index + 1) + value(chunk.getAttachExtension()));
            attachments.add(new Attachment(index, name, mime, data, contentId));
        }
        return new MailMessage(optional(message::getDisplayFrom), optional(message::getDisplayTo),
                optional(message::getDisplayCC), when == null ? null : ZonedDateTime.ofInstant(when.toInstant(),
                ZoneId.systemDefault()), optional(message::getSubject), html, text, attachments);
    }

    /**
     * Eine eingebettete Outlook-Nachricht als eigene E-Mail-Datei (.eml, MIME): Kopf, Text und ihre Anlagen bleiben,
     * der Viewer öffnet sie wieder als E-Mail. POI schreibt eingebettete Nachrichten nicht einzeln heraus.
     */
    private static byte[] embeddedBytes(MAPIMessage inner, int depth) {
        try {
            return fromMapi(inner, depth + 1).toEml();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Die E-Mail als MIME-Nachricht (.eml): Kopf, Text (HTML oder rein) und alle Anlagen. */
    byte[] toEml() {
        String boundary = "----=_space-harvester_" + Long.toHexString(System.nanoTime());
        StringBuilder out = new StringBuilder("MIME-Version: 1.0\r\n");
        header(out, "Subject", subject);
        header(out, "From", from);
        header(out, "To", to);
        header(out, "Cc", cc);
        if (sent != null) {
            out.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(sent)).append("\r\n");
        }
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        boolean isHtml = html != null && !html.isBlank();
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(isHtml ? "text/html" : "text/plain").append("; charset=UTF-8\r\n");
        base64(out, (isHtml ? html : text).getBytes(StandardCharsets.UTF_8));
        for (Attachment attachment : attachments) {
            String word = encodedWord(attachment.name());
            out.append("--").append(boundary).append("\r\n");
            out.append("Content-Type: ").append(attachment.mediaType().isBlank() ? "application/octet-stream"
                    : attachment.mediaType()).append("; name=\"").append(word).append("\"\r\n");
            out.append("Content-Disposition: attachment; filename=\"").append(word).append("\"\r\n");
            if (!attachment.contentId().isEmpty()) {
                out.append("Content-ID: <").append(attachment.contentId()).append(">\r\n");
            }
            base64(out, attachment.data());
        }
        out.append("--").append(boundary).append("--\r\n");
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void header(StringBuilder out, String name, String value) {
        if (!value.isEmpty()) {
            out.append(name).append(": ").append(encodedWord(value.replaceAll("[\\r\\n]+", " "))).append("\r\n");
        }
    }

    private static void base64(StringBuilder out, byte[] data) {
        out.append("Content-Transfer-Encoding: base64\r\n\r\n");
        out.append(java.util.Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(data)).append("\r\n");
    }

    /** Nicht-ASCII als RFC-2047-Wort (UTF-8, Base64). */
    private static String encodedWord(String value) {
        if (value.chars().allMatch(c -> c >= 0x20 && c < 0x7F && c != '"')) {
            return value;
        }
        return "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    private interface Chunk {
        String get() throws ChunkNotFoundException;
    }

    private static String optional(Chunk chunk) {
        try {
            String value = chunk.get();
            return value == null || value.isBlank() ? null : value;
        } catch (ChunkNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private static String value(StringChunk chunk) {
        return chunk == null || chunk.getValue() == null ? "" : chunk.getValue().trim();
    }

    /** Text aus dem RTF-Körper (über das RTF-Kit von Swing), wenn es weder HTML noch Text gibt. */
    private static String rtfText(String rtf) {
        if (rtf == null) {
            return "";
        }
        try {
            javax.swing.text.rtf.RTFEditorKit kit = new javax.swing.text.rtf.RTFEditorKit();
            javax.swing.text.Document document = kit.createDefaultDocument();
            kit.read(new StringReader(rtf), document, 0);
            return document.getText(0, document.getLength());
        } catch (IOException | javax.swing.text.BadLocationException | RuntimeException e) {
            return "";
        }
    }

    // ---- Internet (.eml) --------------------------------------------------------------------------------------------

    private static MailMessage readEml(byte[] bytes) throws IOException {
        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        builder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            Message message = builder.parseMessage(in);
            try {
                return fromMime(message);
            } finally {
                message.dispose();
            }
        }
    }

    private static MailMessage fromMime(Message message) throws IOException {
        Parts parts = new Parts();
        collect(message, parts, 0);
        Date when = message.getDate();
        return new MailMessage(mailboxes(message.getFrom()), addresses(message.getTo()), addresses(message.getCc()),
                when == null ? null : ZonedDateTime.ofInstant(when.toInstant(), ZoneId.systemDefault()),
                message.getSubject(), parts.html, parts.text, parts.attachments);
    }

    private static final class Parts {
        private String html;
        private String text;
        private final List<Attachment> attachments = new ArrayList<>();
    }

    private static void collect(Entity entity, Parts parts, int depth) throws IOException {
        Body body = entity.getBody();
        if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                if (depth < MAX_DEPTH) {
                    collect(part, parts, depth + 1);
                }
            }
            return;
        }
        String mime = entity.getMimeType() == null ? "" : entity.getMimeType().toLowerCase(Locale.ROOT);
        String fileName = entity.getFilename();
        boolean attachment = "attachment".equalsIgnoreCase(entity.getDispositionType())
                || (fileName != null && !fileName.isBlank());
        if (body instanceof Message inner) {
            // Eine angehängte Nachricht: als .eml-Anlage, der Viewer öffnet sie wieder als E-Mail.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new DefaultMessageWriter().writeMessage(inner, out);
            String name = firstNonBlank(fileName, inner.getSubject(), "Nachricht " + (parts.attachments.size() + 1));
            parts.attachments.add(new Attachment(parts.attachments.size(), safeName(name, ".eml"), "message/rfc822",
                    out.toByteArray(), contentId(entity)));
            return;
        }
        if (!attachment && body instanceof TextBody textBody && mime.equals("text/html") && parts.html == null) {
            parts.html = read(textBody);
            return;
        }
        if (!attachment && body instanceof TextBody textBody && mime.startsWith("text/") && parts.text == null) {
            parts.text = read(textBody);
            return;
        }
        if (body instanceof SingleBody single) {
            byte[] data;
            try (InputStream in = single.getInputStream()) {
                data = in.readAllBytes();
            }
            String name = firstNonBlank(fileName, "Anlage " + (parts.attachments.size() + 1) + extensionFor(mime));
            parts.attachments.add(new Attachment(parts.attachments.size(), name, mime, data, contentId(entity)));
        }
    }

    private static String read(TextBody body) throws IOException {
        try (Reader reader = body.getReader()) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[8192];
            for (int n; (n = reader.read(buffer)) > 0; ) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        } catch (java.nio.charset.UnsupportedCharsetException e) {
            try (InputStream in = body.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static String contentId(Entity entity) {
        Field field = entity.getHeader() == null ? null : entity.getHeader().getField("Content-ID");
        return field == null ? "" : stripAngles(field.getBody());
    }

    private static String mailboxes(MailboxList list) {
        if (list == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Mailbox mailbox : list) {
            names.add(mailbox(mailbox));
        }
        return String.join("; ", names);
    }

    private static String addresses(AddressList list) {
        if (list == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Address address : list) {
            if (address instanceof Mailbox mailbox) {
                names.add(mailbox(mailbox));
            } else if (address instanceof Group group) {
                names.add(group.getName() + ": " + mailboxes(group.getMailboxes()));
            }
        }
        return String.join("; ", names);
    }

    private static String mailbox(Mailbox mailbox) {
        String name = mailbox.getName();
        return name == null || name.isBlank() ? mailbox.getAddress() : name + " <" + mailbox.getAddress() + ">";
    }

    private static String extensionFor(String mime) {
        return switch (mime) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            default -> "";
        };
    }

    // ---- Hilfen ------------------------------------------------------------------------------------------------------

    /** Outlook-Nachrichten sind OLE2-Verbunddateien (D0 CF 11 E0 A1 B1 1A E1). */
    private static boolean isOle2(byte[] bytes) {
        int[] signature = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safeName(String name, String extension) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.toLowerCase(Locale.ROOT).endsWith(extension) ? clean : clean + extension;
    }

    private static String stripAngles(String id) {
        String value = id == null ? "" : id.trim();
        return value.startsWith("<") && value.endsWith(">") ? value.substring(1, value.length() - 1) : value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void row(StringBuilder page, String label, String value) {
        if (!value.isEmpty()) {
            page.append("<tr><td valign=\"top\"><b>").append(label).append(":</b></td><td>").append(escape(value))
                    .append("</td></tr>");
        }
    }

    private static void line(StringBuilder plain, String label, String value) {
        if (!value.isEmpty()) {
            plain.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
