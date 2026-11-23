package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** PROPFIND-Anfrage und das Lesen der {@code DAV:multistatus}-Antwort (RFC 4918), ohne externe Entitäten. */
final class WebDavXml {

    static final String DAV = "DAV:";

    /**
     * {@code allprop} wie im Testprogramm v2.0, das damit auf dem echten System 207 bekam; so kommen auch die
     * Fabasoft-Eigenschaften ({@code objaddress}) mit, die nicht im DAV-Namensraum stehen.
     */
    static final String PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>";

    private WebDavXml() {
    }

    /** Die Einträge der Antwort ohne die angefragte Mappe selbst; Mappen zuerst, dann nach Namen. */
    static List<EakteEntry> entries(byte[] xml, String requestedHref) throws IOException {
        List<EakteEntry> entries = new ArrayList<>();
        String self = normalised(requestedHref);
        NodeList responses = parse(xml).getElementsByTagNameNS(DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            String href = text(child(response, "href"));
            if (href.isEmpty()) {
                continue;
            }
            String path = path(href);
            if (normalised(path).equals(self)) {
                continue;
            }
            Element prop = okProp(response);
            boolean folder = prop != null && (child(child(prop, "resourcetype"), "collection") != null
                    || "1".equals(text(child(prop, "iscollection")))
                    || "true".equalsIgnoreCase(text(child(prop, "iscollection"))));
            String name = prop == null ? "" : text(child(prop, "displayname"));
            if (name.isEmpty()) {
                name = lastSegment(path);
            }
            long size = -1;
            String length = prop == null ? "" : text(child(prop, "getcontentlength"));
            try {
                size = length.isEmpty() ? -1 : Long.parseLong(length);
            } catch (NumberFormatException ignored) {
                // unbekannt
            }
            entries.add(new EakteEntry(name, path, folder, size,
                    modified(prop == null ? "" : text(child(prop, "getlastmodified"))),
                    prop == null ? "" : text(child(prop, "getcontenttype")),
                    modified(prop == null ? "" : text(child(prop, "creationdate"))),
                    prop == null ? "" : text(child(prop, "getetag")), objectAddress(prop)));
        }
        entries.sort(Comparator.comparing((EakteEntry entry) -> !entry.folder())
                .thenComparing(EakteEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** Zählt die {@code DAV:response}-Einträge (für das Protokoll). */
    static int count(byte[] xml) throws IOException {
        return parse(xml).getElementsByTagNameNS(DAV, "response").getLength();
    }

    private static org.w3c.dom.Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("WebDAV-Antwort ist kein lesbares XML", e);
        }
    }

    /** Die {@code prop} aus dem {@code propstat} mit Status 200 (oder dem einzigen). */
    private static Element okProp(Element response) {
        Element fallback = null;
        for (Node node = response.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element propstat && DAV.equals(propstat.getNamespaceURI())
                    && "propstat".equals(propstat.getLocalName())) {
                Element prop = child(propstat, "prop");
                if (text(child(propstat, "status")).contains(" 200")) {
                    return prop;
                }
                if (fallback == null) {
                    fallback = prop;
                }
            }
        }
        return fallback;
    }

    /** Die Fabasoft-Objektadresse: ein Element, dessen lokaler Name auf {@code objaddress} endet, gleich welcher Namensraum. */
    private static String objectAddress(Element prop) {
        if (prop == null) {
            return "";
        }
        for (Node node = prop.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getLocalName() != null
                    && element.getLocalName().toLowerCase(java.util.Locale.ROOT).endsWith("objaddress")) {
                return text(element);
            }
        }
        return "";
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && DAV.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }

    /** Pfad eines {@code href}, auch wenn der Server eine vollständige Adresse liefert. */
    static String path(String href) {
        String trimmed = href.strip().replace(" ", "%20");
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                String raw = URI.create(trimmed).getRawPath();
                return raw == null || raw.isEmpty() ? "/" : raw;
            } catch (IllegalArgumentException e) {
                return trimmed;
            }
        }
        return trimmed;
    }

    static String normalised(String href) {
        String decoded = URLDecoder.decode(path(href).replace("+", "%2B"), StandardCharsets.UTF_8);
        while (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    static String lastSegment(String href) {
        String path = normalised(href);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Instant modified(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
