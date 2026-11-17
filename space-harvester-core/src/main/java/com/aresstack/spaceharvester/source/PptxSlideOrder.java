package com.aresstack.spaceharvester.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Foliennummern einer PowerPoint-Datei ({@code .pptx}) in der Reihenfolge, in der Tika die Folien ausgibt (ADR 0026,
 * AP10; Architekt 13.11.2026). Tika liest eine pptx-Datei vom Datenträger auf dem SAX-Weg und geht dort die
 * Folienbeziehungen von {@code presentation.xml} in der Reihenfolge ihrer Kennungen als Text durch (POIs
 * {@code PackageRelationshipCollection}, {@code rId10} vor {@code rId6}), nicht in der Folienreihenfolge
 * ({@code p:sldIdLst}). Diese Klasse liest nur {@code presentation.xml} und ihre Beziehungen (sicherer XML-Leser,
 * Größe begrenzt) und ordnet der k-ten ausgegebenen Folie ihre Position in {@code p:sldIdLst} zu (ab 1, ausgeblendete
 * zählen mit).
 * <p>
 * Die Zuordnung beruht auf Tikas und POIs innerer Reihenfolge und gilt nur für die geprüften Fassungen
 * ({@link #TIKA}, {@link #POI}); bei anderen gibt es keine Nummer, bis {@code PptxSlideOrderTest} dort grün war.
 * Ebenso keine, wenn etwas nicht eindeutig ist: Lieber keine Fundstelle als eine geratene.
 */
final class PptxSlideOrder {

    /** Tika-Fassung, an der die Reihenfolge geprüft ist. */
    static final String TIKA = "Apache Tika 3.3.2";
    /** POI-Fassung, an der die Reihenfolge geprüft ist. */
    static final String POI = "5.5.1";

    /** Höchstgröße von {@code presentation.xml} und Beziehungsdateien. */
    static final long MAX_PART_BYTES = 8L * 1024 * 1024;

    private static final String OFFICE_DOCUMENT =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String STRICT_OFFICE_DOCUMENT =
            "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument";
    private static final String SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide";
    private static final String STRICT_SLIDE = "http://purl.oclc.org/ooxml/officeDocument/relationships/slide";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STRICT_RELATIONSHIPS = "http://purl.oclc.org/ooxml/officeDocument/relationships";

    private static final XMLInputFactory XML = secureFactory();

    private PptxSlideOrder() {
    }

    /** Ob die laufenden Fassungen die geprüften sind. */
    static boolean verified() {
        try {
            return TIKA.equals(org.apache.tika.Tika.getString()) && POI.equals(org.apache.poi.Version.getVersion());
        } catch (LinkageError e) {
            return false;
        }
    }

    /**
     * Die Foliennummern in Tikas Ausgabereihenfolge; leer, wenn die Fassungen nicht geprüft sind oder die Datei nicht
     * eindeutig ist (Teile fehlen oder sind zu groß, doppelte Beziehungen, Folien außerhalb von {@code p:sldIdLst}).
     */
    static Optional<int[]> of(Path file) {
        if (file == null || !verified()) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return read(zip);
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<int[]> read(ZipFile zip) throws IOException, XMLStreamException {
        String presentation = null;
        for (Relationship relationship : relationships(zip, "_rels/.rels")) {
            if (OFFICE_DOCUMENT.equals(relationship.type()) || STRICT_OFFICE_DOCUMENT.equals(relationship.type())) {
                if (presentation != null) {
                    return Optional.empty();
                }
                presentation = resolve("", relationship.target());
            }
        }
        if (presentation == null) {
            return Optional.empty();
        }
        int slash = presentation.lastIndexOf('/');
        String directory = slash < 0 ? "" : presentation.substring(0, slash + 1);
        String name = presentation.substring(slash + 1);
        // Folienbeziehungen nach Kennung als Text, wie POI sie Tika gibt.
        TreeMap<String, String> slides = new TreeMap<>();
        Set<String> targets = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Relationship relationship : relationships(zip, directory + "_rels/" + name + ".rels")) {
            // POI überschreibt Beziehungen gleicher Kennung, bevor es nach Typ filtert: jede Kennung nur einmal.
            if (!ids.add(relationship.id())) {
                return Optional.empty();
            }
            if (!SLIDE.equals(relationship.type()) && !STRICT_SLIDE.equals(relationship.type())) {
                continue;
            }
            String target = resolve(directory, relationship.target());
            if (relationship.external() || zip.getEntry(target) == null || !targets.add(target)
                    || slides.put(relationship.id(), target) != null) {
                return Optional.empty();
            }
        }
        List<String> order = slideOrder(zip, presentation);
        if (order == null || order.size() != slides.size() || new HashSet<>(order).size() != order.size()) {
            return Optional.empty();
        }
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            positions.put(order.get(i), i + 1);
        }
        int[] numbers = new int[slides.size()];
        int k = 0;
        for (String id : slides.keySet()) {
            Integer position = positions.get(id);
            if (position == null) {
                return Optional.empty();
            }
            numbers[k++] = position;
        }
        return Optional.of(numbers);
    }

    /** Die Beziehungskennungen aus {@code p:sldIdLst} in Folienreihenfolge; {@code null}, wenn nicht lesbar. */
    private static List<String> slideOrder(ZipFile zip, String presentation) throws IOException, XMLStreamException {
        List<String> order = new ArrayList<>();
        try (InputStream in = open(zip, presentation)) {
            if (in == null) {
                return null;
            }
            XMLStreamReader reader = XML.createXMLStreamReader(in);
            try {
                boolean list = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if ("sldIdLst".equals(reader.getLocalName())) {
                            list = true;
                        } else if (list && "sldId".equals(reader.getLocalName())) {
                            String id = reader.getAttributeValue(RELATIONSHIPS, "id");
                            if (id == null) {
                                id = reader.getAttributeValue(STRICT_RELATIONSHIPS, "id");
                            }
                            if (id == null) {
                                return null;
                            }
                            order.add(id);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "sldIdLst".equals(reader.getLocalName())) {
                        return order;
                    }
                }
            } finally {
                reader.close();
            }
        }
        return order;
    }

    private record Relationship(String id, String type, String target, boolean external) {
    }

    private static List<Relationship> relationships(ZipFile zip, String part) throws IOException,
            XMLStreamException {
        List<Relationship> found = new ArrayList<>();
        try (InputStream in = open(zip, part)) {
            if (in == null) {
                return found;
            }
            XMLStreamReader reader = XML.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "Relationship".equals(reader.getLocalName())) {
                        String id = reader.getAttributeValue(null, "Id");
                        String type = reader.getAttributeValue(null, "Type");
                        String target = reader.getAttributeValue(null, "Target");
                        if (id == null || type == null || target == null) {
                            throw new IOException("Beziehung unvollständig");
                        }
                        found.add(new Relationship(id, type, target,
                                "External".equalsIgnoreCase(reader.getAttributeValue(null, "TargetMode"))));
                    }
                }
            } finally {
                reader.close();
            }
        }
        return found;
    }

    /** Ein Teil des Pakets, höchstens {@link #MAX_PART_BYTES} gelesen; {@code null}, wenn er fehlt. */
    private static InputStream open(ZipFile zip, String part) throws IOException {
        ZipEntry entry = zip.getEntry(part);
        if (entry == null) {
            return null;
        }
        // Die Größenangabe im Verzeichnis ist nicht verlässlich: beim Lesen gezählt.
        return new InputStream() {
            private final InputStream in = zip.getInputStream(entry);
            private long read;

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    count(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = in.read(buffer, offset, length);
                if (n > 0) {
                    count(n);
                }
                return n;
            }

            private void count(int n) throws IOException {
                read += n;
                if (read > MAX_PART_BYTES) {
                    throw new IOException("Teil zu groß");
                }
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }

    /** Ziel einer Beziehung relativ zum Verzeichnis ihres Teils, als Eintragsname im ZIP. */
    static String resolve(String directory, String target) {
        String path = target.startsWith("/") ? target.substring(1) : directory + target;
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Ziel außerhalb des Pakets");
                }
                parts.removeLast();
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }
}
