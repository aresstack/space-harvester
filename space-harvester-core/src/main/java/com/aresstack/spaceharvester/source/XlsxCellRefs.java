package com.aresstack.spaceharvester.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Zellbezüge einer Excel-Mappe ({@code .xlsx}, {@code .xlsm}) neben Tikas Lauf (ADR 0026, AP10; Architekt 13.11.2026).
 * Tika gibt auf dem SAX-Weg je {@code row}-Element des Blatt-XML ein {@code tr} aus (auch ein leeres; Zeilen, die im XML
 * fehlen, fehlen auch dort) und füllt die Spalten ab A mit leeren Zellen auf; eine Zelle gibt es nur mit gespeichertem
 * Wert ({@code v} oder {@code is}). Ein {@link Sheet} liest das Blatt-XML mit, eine Zeile je {@code tr}, ohne Puffer:
 * Zeilennummer aus {@code r} (fehlt es, die vorige plus 1), belegte Spalten aus den Zellen.
 * <p>
 * Belegt statt geraten: Der Aufrufer prüft je Zeile, dass Tikas Zellen zu den belegten Spalten passen, sonst endet die
 * Zuordnung für das Blatt. Gilt nur für die geprüften Fassungen von Tika und POI ({@link PptxSlideOrder#verified()}).
 */
final class XlsxCellRefs {

    /** Höchstgröße von {@code workbook.xml} und ihren Beziehungen. */
    static final long MAX_PART_BYTES = 8L * 1024 * 1024;
    /** Höchstgröße eines mitgelesenen Blatts; darüber endet die Zuordnung (Tika liest weiter). */
    static final long MAX_SHEET_BYTES = 4L * 1024 * 1024 * 1024;

    private static final String WORKSHEET =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";
    private static final String STRICT_WORKSHEET = "http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STRICT_RELATIONSHIPS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final String OFFICE_DOCUMENT =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String STRICT_OFFICE_DOCUMENT =
            "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument";

    private static final XMLInputFactory XML = secureFactory();

    private XlsxCellRefs() {
    }

    /** Eine Zeile des Blatt-XML: Nummer ab 1 und Spalten (ab 0) der Zellen mit gespeichertem Wert. */
    record Row(long number, BitSet values) {

        /** Zahl der Zellen, die Tika für diese Zeile ausgibt (bis zur letzten belegten Spalte). */
        int cells() {
            return values.length();
        }
    }

    /** Die Arbeitsblätter einer Mappe nach Namen; leer, wenn nicht eindeutig lesbar oder Fassung nicht geprüft. */
    static Optional<Workbook> open(Path file, UnaryOperator<String> normalize) {
        if (file == null || !PptxSlideOrder.verified()) {
            return Optional.empty();
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(file.toFile());
            Optional<Workbook> workbook = read(zip, normalize);
            if (workbook.isPresent()) {
                return workbook;
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            // keine Zuordnung
        }
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nur gelesen
            }
        }
        return Optional.empty();
    }

    private static Optional<Workbook> read(ZipFile zip, UnaryOperator<String> normalize)
            throws IOException, XMLStreamException {
        String workbook = null;
        for (String[] relationship : relationships(zip, "_rels/.rels")) {
            if (OFFICE_DOCUMENT.equals(relationship[1]) || STRICT_OFFICE_DOCUMENT.equals(relationship[1])) {
                if (workbook != null) {
                    return Optional.empty();
                }
                workbook = PptxSlideOrder.resolve("", relationship[2]);
            }
        }
        if (workbook == null) {
            return Optional.empty();
        }
        int slash = workbook.lastIndexOf('/');
        String directory = slash < 0 ? "" : workbook.substring(0, slash + 1);
        Map<String, String> worksheets = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (String[] relationship : relationships(zip, directory + "_rels/" + workbook.substring(slash + 1)
                + ".rels")) {
            if (!ids.add(relationship[0])) {
                return Optional.empty();
            }
            if (WORKSHEET.equals(relationship[1]) || STRICT_WORKSHEET.equals(relationship[1])) {
                worksheets.put(relationship[0], PptxSlideOrder.resolve(directory, relationship[2]));
            }
        }
        Map<String, String> byName = new HashMap<>();
        try (InputStream in = limited(zip, workbook, MAX_PART_BYTES)) {
            if (in == null) {
                return Optional.empty();
            }
            XMLStreamReader reader = XML.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.getLocalName())) {
                        String name = reader.getAttributeValue(null, "name");
                        String id = reader.getAttributeValue(RELATIONSHIPS, "id");
                        if (id == null) {
                            id = reader.getAttributeValue(STRICT_RELATIONSHIPS, "id");
                        }
                        String part = id == null ? null : worksheets.get(id);
                        if (name == null) {
                            return Optional.empty();
                        }
                        // Gleicher Name zweimal: keines der beiden Blätter ist eindeutig.
                        String key = normalize.apply(name);
                        if (byName.containsKey(key)) {
                            byName.put(key, null);
                        } else {
                            byName.put(key, part);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }
        return Optional.of(new Workbook(zip, byName));
    }

    /** Eine geöffnete Mappe; {@link #close()} schließt die Datei. */
    static final class Workbook implements AutoCloseable {

        private final ZipFile zip;
        private final Map<String, String> sheets;

        private Workbook(ZipFile zip, Map<String, String> sheets) {
            this.zip = zip;
            this.sheets = sheets;
        }

        /** Das Blatt mit diesem (normalisierten) Namen zum Mitlesen; leer, wenn es kein eindeutiges Arbeitsblatt ist. */
        Optional<Sheet> sheet(String name) {
            String part = name == null ? null : sheets.get(name);
            if (part == null) {
                return Optional.empty();
            }
            try {
                InputStream in = limited(zip, part, MAX_SHEET_BYTES);
                if (in == null) {
                    return Optional.empty();
                }
                return Optional.of(new Sheet(in, XML.createXMLStreamReader(in)));
            } catch (IOException | XMLStreamException e) {
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nur gelesen
            }
        }
    }

    /** Ein mitgelesenes Blatt: {@link #next()} gibt die nächste Zeile. */
    static final class Sheet implements AutoCloseable {

        private final InputStream in;
        private final XMLStreamReader reader;
        private long previous;

        private Sheet(InputStream in, XMLStreamReader reader) {
            this.in = in;
            this.reader = reader;
        }

        /** Die nächste Zeile des Blatt-XML; leer am Ende oder wenn das XML nicht lesbar ist. */
        Optional<Row> next() {
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "row".equals(reader.getLocalName())) {
                        String r = reader.getAttributeValue(null, "r");
                        long number = r == null ? previous + 1 : Long.parseLong(r.strip());
                        if (number <= previous) {
                            return Optional.empty();
                        }
                        previous = number;
                        return Optional.of(new Row(number, cells(number)));
                    }
                }
            } catch (XMLStreamException | RuntimeException e) {
                // keine weitere Zeile
            }
            return Optional.empty();
        }

        /** Liest die Zellen bis zum Ende der Zeile; Spalten mit gespeichertem Wert. */
        private BitSet cells(long number) throws XMLStreamException {
            BitSet values = new BitSet();
            int column = -1;
            int depth = 1;
            int cell = -1;
            while (reader.hasNext() && depth > 0) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = reader.getLocalName();
                    if (depth == 2 && "c".equals(name)) {
                        String ref = reader.getAttributeValue(null, "r");
                        column = ref == null ? column + 1 : column(ref, number);
                        cell = column;
                    } else if (depth == 3 && cell >= 0 && ("v".equals(name) || "is".equals(name))) {
                        values.set(cell);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    if (depth == 1) {
                        cell = -1;
                    }
                }
            }
            return values;
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // nur gelesen
            }
            try {
                in.close();
            } catch (IOException ignored) {
                // nur gelesen
            }
        }
    }

    /** Spalte ab 0 aus einem Zellbezug wie {@code AA6}; die Zeile muss {@code number} sein. */
    static int column(String ref, long number) {
        int i = 0;
        int column = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("Zellbezug");
            }
            column = column * 26 + (c - 'A' + 1);
            i++;
        }
        if (i == 0 || i > 3 || Long.parseLong(ref.substring(i)) != number) {
            throw new IllegalArgumentException("Zellbezug");
        }
        return column - 1;
    }

    /** Zellbezug aus Spalte ab 0 und Zeile ab 1, etwa {@code C3}. */
    static String ref(int column, long row) {
        StringBuilder letters = new StringBuilder();
        for (int n = column + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.append(row).toString();
    }

    private static java.util.List<String[]> relationships(ZipFile zip, String part)
            throws IOException, XMLStreamException {
        java.util.List<String[]> found = new java.util.ArrayList<>();
        try (InputStream in = limited(zip, part, MAX_PART_BYTES)) {
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
                        if (id == null || type == null || target == null
                                || "External".equalsIgnoreCase(reader.getAttributeValue(null, "TargetMode"))) {
                            if (id == null) {
                                throw new IOException("Beziehung unvollständig");
                            }
                            found.add(new String[] {id, "", ""});
                            continue;
                        }
                        found.add(new String[] {id, type, target});
                    }
                }
            } finally {
                reader.close();
            }
        }
        return found;
    }

    /** Ein Teil, beim Lesen gezählt; {@code null}, wenn er fehlt. */
    private static InputStream limited(ZipFile zip, String part, long max) throws IOException {
        ZipEntry entry = zip.getEntry(part);
        if (entry == null) {
            return null;
        }
        InputStream source = zip.getInputStream(entry);
        return new java.io.FilterInputStream(source) {
            private long read;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    count(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) {
                    count(n);
                }
                return n;
            }

            private void count(int n) throws IOException {
                read += n;
                if (read > max) {
                    throw new IOException("Teil zu groß");
                }
            }
        };
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }
}
