package com.aresstack.spaceharvester.document;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes the export tree as a Word document (#115): every page a chapter whose heading style follows its depth in the
 * tree (root Heading 1, child Heading 2 …), so Word's navigation pane shows the tree; the content of a page goes below
 * with its own headings shifted under the chapter. With more than one page a table of contents field comes first;
 * Word fills in its page numbers when it updates the fields on opening.
 *
 * <p>Space Harvester: besides the tree ({@link #write(ExportDocumentNode, OutputStream)}) the chapters can be handed
 * in one by one in tree order ({@link #open(String, boolean)}). Apache POI builds a document as XML objects in memory,
 * about 50 times the size of the text; a space with thousands of pages would need gigabytes. So each chapter is laid
 * out by POI as before, then its paragraphs and tables leave the document as XML into a temporary file, and
 * {@link Chapters#finish(OutputStream)} puts them back in place while it copies the archive. In memory stay only the
 * current chapter, the styles, the list numbering, the link targets and the embedded pictures. A chapter with an anchor
 * gets a bookmark on its heading. The file is deterministic: no creation date, fixed time stamps in the archive, so the
 * same chapters give the same bytes.</p>
 */
public final class DocxExportWriter {

    /** The table of contents over all heading levels, with hyperlinks, without page numbers in web view. */
    static final String TOC_INSTRUCTION = "TOC \\o \"1-" + MarkdownDocxRenderer.MAX_HEADING_LEVEL + "\" \\h \\z \\u";
    static final String TOC_TITLE = "Inhalt";
    static final String TOC_PLACEHOLDER = "Das Inhaltsverzeichnis erscheint, sobald Word die Felder aktualisiert (F9).";
    /** Time stamp of every archive entry: 1980-01-01, the earliest ZIP knows, in local DOS time on every machine. */
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final String DOCUMENT_PART = "word/document.xml";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Stands where the chapters go back in; no page text can contain it as one run. */
    private static final String MARKER = "space-harvester-chapters-6f1d2c";

    private final ExportGraphics graphics;
    private final String creator;

    public DocxExportWriter() {
        this(ExportGraphics.standard());
    }

    /** @param graphics the figures of this export (#132); it also collects the ones that are missing */
    public DocxExportWriter(ExportGraphics graphics) {
        this(graphics, "Space Harvester");
    }

    /** @param creator the author the document properties name */
    public DocxExportWriter(ExportGraphics graphics, String creator) {
        if (graphics == null) {
            throw new IllegalArgumentException("graphics must not be null");
        }
        this.graphics = graphics;
        this.creator = creator == null ? "" : creator;
    }

    public void write(ExportDocumentNode root, OutputStream out) throws IOException {
        Chapters chapters = open(root.title(), root.size() > 1);
        try {
            chapter(chapters, root, 1);
            chapters.finish(out);
        } finally {
            chapters.close();
        }
    }

    /**
     * Start a document whose chapters follow in tree order.
     *
     * @param title           the document title (properties)
     * @param tableOfContents whether a table of contents field comes first
     */
    public Chapters open(String title, boolean tableOfContents) {
        return new Chapters(title, tableOfContents);
    }

    private static void chapter(Chapters chapters, ExportDocumentNode node, int depth) {
        chapters.chapter(node.title(), node.anchor(), node.text(), node.images(), depth);
        for (ExportDocumentNode child : node.children()) {
            chapter(chapters, child, depth + 1);
        }
    }

    /** One document being written; {@link #close()} releases it, with or without {@link #finish(OutputStream)}. */
    public final class Chapters implements Closeable {

        private final XWPFDocument document;
        private final MarkdownDocxRenderer renderer;
        private final Path chapterFile;
        private final Writer chapterXml;
        /** Body elements before the first chapter (the table of contents); they stay in the document. */
        private final int fixed;
        private int bookmarks;

        private Chapters(String title, boolean tableOfContents) {
            try {
                chapterFile = Files.createTempFile("space-harvester-chapters-", ".xml");
                chapterXml = new BufferedWriter(Files.newBufferedWriter(chapterFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            document = new XWPFDocument();
            renderer = new MarkdownDocxRenderer(document, graphics);
            document.getProperties().getCoreProperties().setTitle(title == null ? "" : title);
            document.getProperties().getCoreProperties().setCreator(creator);
            document.getProperties().getCoreProperties().setCreated(Optional.<java.util.Date>empty());
            if (tableOfContents) {
                tableOfContents(document, renderer);
            }
            fixed = document.getBodyElements().size();
        }

        /**
         * Write the next chapter in tree order.
         *
         * @param anchor   the bookmark target, or {@code null}
         * @param markdown the text of the chapter
         * @param images   the figures the text refers to
         * @param depth    1 for the root, 2 for its children …
         */
        public void chapter(String title, String anchor, String markdown, List<ExportImage> images, int depth) {
            XWPFParagraph heading = renderer.paragraph();
            if (anchor != null) {
                bookmark(heading, anchor);
            }
            XWPFRun run = heading.createRun();
            run.setText(MarkdownDocxRenderer.clean(title));
            if (anchor != null) {
                heading.getCTP().addNewBookmarkEnd().setId(BigInteger.valueOf(bookmarks - 1L));
            }
            if (depth <= MarkdownDocxRenderer.MAX_HEADING_LEVEL) {
                heading.setStyle(MarkdownDocxRenderer.STYLE_HEADING + depth);
            } else {
                run.setBold(true);
            }
            graphics.with(images);
            try {
                renderer.markdown(markdown, false, depth);
            } finally {
                graphics.forget(); // the pictures are in the document now
            }
            try {
                moveOut();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        /** The paragraphs and tables of the chapter into the temporary file, out of the document. */
        private void moveOut() throws IOException {
            List<IBodyElement> elements = document.getBodyElements();
            XmlOptions options = new XmlOptions(POIXMLTypeLoader.DEFAULT_XML_OPTIONS).setSaveOuter();
            for (int i = fixed; i < elements.size(); i++) {
                IBodyElement element = elements.get(i);
                XmlObject xml = element instanceof XWPFTable ? ((XWPFTable) element).getCTTbl()
                        : ((XWPFParagraph) element).getCTP();
                chapterXml.write(withoutRootNamespace(xml.xmlText(options)));
            }
            for (int i = elements.size() - 1; i >= fixed; i--) {
                document.removeBodyElement(i);
            }
        }

        private void bookmark(XWPFParagraph heading, String anchor) {
            CTBookmark start = heading.getCTP().addNewBookmarkStart();
            start.setId(BigInteger.valueOf(bookmarks++));
            start.setName(MarkdownDocxRenderer.bookmark(anchor));
        }

        /** Write the document to {@code out}; the stream stays open. */
        public void finish(OutputStream out) throws IOException {
            chapterXml.close();
            document.createParagraph().createRun().setText(MARKER);
            Path file = Files.createTempFile("space-harvester-", ".docx");
            try {
                try (OutputStream raw = Files.newOutputStream(file)) {
                    document.write(raw);
                }
                copy(file, chapterFile, out);
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                chapterXml.close();
                document.close();
            } finally {
                Files.deleteIfExists(chapterFile);
            }
        }
    }

    /**
     * Copy the archive with fixed entry times (POI stamps every entry with the current time) and the chapters in place
     * of the paragraph holding {@link #MARKER}.
     */
    private static void copy(Path file, Path chapters, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new NonClosing(out));
        try (InputStream raw = Files.newInputStream(file); ZipInputStream in = new ZipInputStream(raw)) {
            byte[] buffer = new byte[64 * 1024];
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(ZIP_TIME);
                zip.putNextEntry(copy);
                if (DOCUMENT_PART.equals(entry.getName())) {
                    document(new String(readAll(in), StandardCharsets.UTF_8), chapters, zip);
                } else {
                    for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        zip.finish();
        zip.flush();
    }

    /** {@code skeleton} is small: the table of contents and the marker; the chapters stream from their file. */
    private static void document(String skeleton, Path chapters, OutputStream out) throws IOException {
        int marker = skeleton.indexOf(MARKER);
        int start = marker < 0 ? -1 : skeleton.lastIndexOf("<w:p>", marker);
        if (start < 0) {
            start = marker < 0 ? -1 : skeleton.lastIndexOf("<w:p ", marker);
        }
        int end = marker < 0 ? -1 : skeleton.indexOf("</w:p>", marker);
        if (start < 0 || end < 0) {
            throw new IOException("Word-Dokument ohne Platzhalter für die Kapitel");
        }
        Writer writer = new java.io.OutputStreamWriter(new NonClosing(out), StandardCharsets.UTF_8);
        writer.write(skeleton, 0, start);
        try (Reader reader = Files.newBufferedReader(chapters, StandardCharsets.UTF_8)) {
            char[] buffer = new char[64 * 1024];
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                writer.write(buffer, 0, read);
            }
        }
        writer.write(skeleton.substring(end + "</w:p>".length()));
        writer.flush();
    }

    /** The document element declares {@code w:} already; other prefixes stay declared on the element itself. */
    private static String withoutRootNamespace(String element) {
        String declaration = " xmlns:w=\"" + W + "\"";
        int tagEnd = element.indexOf('>');
        int at = element.indexOf(declaration);
        if (at < 0 || at > tagEnd) {
            return element;
        }
        return element.substring(0, at) + element.substring(at + declaration.length());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static void tableOfContents(XWPFDocument document, MarkdownDocxRenderer renderer) {
        XWPFParagraph title = renderer.paragraph();
        title.setStyle(MarkdownDocxRenderer.STYLE_TITLE);
        title.createRun().setText(TOC_TITLE);
        XWPFParagraph field = renderer.paragraph();
        CTSimpleField toc = field.getCTP().addNewFldSimple();
        toc.setInstr(TOC_INSTRUCTION);
        toc.addNewR().addNewT().setStringValue(TOC_PLACEHOLDER);
        renderer.paragraph().createRun().addBreak(BreakType.PAGE);
        document.enforceUpdateFields();
    }

    /** Lets {@link ZipOutputStream#finish()} write the end of the archive without closing the caller's stream. */
    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
