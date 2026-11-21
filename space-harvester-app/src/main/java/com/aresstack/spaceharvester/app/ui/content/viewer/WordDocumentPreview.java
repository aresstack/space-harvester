package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A local, read-only Word preview without relying on source-specific conversion endpoints: the text of the document
 * ({@link #read}) or the document as HTML with its pictures in place ({@link #render}), so that a document of
 * screenshots shows its screenshots and not "no extractable text".
 */
final class WordDocumentPreview {

    static final int MAX_PREVIEW_CHARACTERS = 2_000_000;
    /** The address of the pictures in the HTML; the viewer finds them in its image cache under this prefix. */
    static final String IMAGE_BASE = "http://word.invalid/image/";
    /** CSS pixels per typographic point: a picture shows as large as the document lays it out (96 dpi). */
    private static final double PIXELS_PER_POINT = 96.0 / 72.0;
    static final String NO_TEXT = "Dieses Dokument enthält keinen extrahierbaren Text.";

    /**
     * The document as HTML with its pictures.
     *
     * @param html     body of the document: headings, paragraphs, tables, pictures in place
     * @param images   the decoded pictures by their address in {@code html}
     * @param text     the plain text, as {@link #read} returns it
     * @param missing  pictures Java cannot decode (such as EMF or WMF); they are marked in {@code html}
     */
    record Rendered(String html, Map<String, BufferedImage> images, String text, int missing) {
    }

    private WordDocumentPreview() {
    }

    static String read(String fileName, byte[] bytes) throws IOException {
        return render(fileName, bytes).text();
    }

    /** The document as HTML with its pictures in place; .doc files get their pictures after the text. */
    static Rendered render(String fileName, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Die Quelle liefert 0 Byte; kein Word-Dokumentinhalt vorhanden");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean zipDocument = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        if (name.endsWith(".docx") || name.endsWith(".docm") || name.endsWith(".dotx")
                || (ReaderAttachmentKind.extension(name).isEmpty() && zipDocument)) {
            return renderDocx(bytes);
        }
        if (name.endsWith(".doc") || name.endsWith(".dot")
                || (ReaderAttachmentKind.extension(name).isEmpty() && !zipDocument)) {
            return renderDoc(bytes);
        }
        throw new IOException("Unsupported Word file format");
    }

    private static Rendered renderDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            Html html = new Html();
            for (IBodyElement element : document.getBodyElements()) {
                html.element(element);
            }
            // Pictures outside the flow (text boxes, shapes) still belong to the document: they follow at the end.
            boolean heading = false;
            for (XWPFPictureData picture : document.getAllPictures()) {
                if (!html.placed.contains(picture)) {
                    if (!heading) {
                        html.body.append("<h3>Weitere Bilder</h3>");
                        heading = true;
                    }
                    html.body.append(html.picture(picture, picture.getFileName(), 0, 0));
                }
            }
            return html.rendered(extractor.getText());
        }
    }

    private static Rendered renderDoc(byte[] bytes) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            Html html = new Html();
            for (String paragraph : extractor.getParagraphText()) {
                String text = WordExtractor.stripFields(paragraph).strip();
                if (!text.isEmpty()) {
                    html.body.append("<p>").append(escape(text)).append("</p>");
                }
            }
            List<Picture> pictures = document.getPicturesTable().getAllPictures();
            for (Picture picture : pictures) {
                html.body.append(html.decoded(picture.getContent(), picture.suggestFullFileName(), 0, 0));
            }
            return html.rendered(extractor.getText());
        }
    }

    /** Builds the HTML of a .docx and collects its pictures. */
    private static final class Html {
        private final StringBuilder body = new StringBuilder();
        private final Map<String, BufferedImage> images = new LinkedHashMap<>();
        private final Set<XWPFPictureData> placed = new HashSet<>();
        private int missing;

        void element(IBodyElement element) {
            if (element instanceof XWPFParagraph paragraph) {
                paragraph(paragraph);
            } else if (element instanceof XWPFTable table) {
                body.append("<table>");
                for (XWPFTableRow row : table.getRows()) {
                    body.append("<tr>");
                    for (XWPFTableCell cell : row.getTableCells()) {
                        body.append("<td>");
                        for (IBodyElement inner : cell.getBodyElements()) {
                            element(inner);
                        }
                        body.append("</td>");
                    }
                    body.append("</tr>");
                }
                body.append("</table>");
            } else if (element instanceof XWPFSDT sdt) {
                String text = sdt.getContent().getText();
                if (text != null && !text.isBlank()) {
                    body.append("<p>").append(escape(text.strip())).append("</p>");
                }
            }
        }

        private void paragraph(XWPFParagraph paragraph) {
            String tag = headingTag(paragraph.getStyle());
            StringBuilder content = new StringBuilder();
            for (XWPFRun run : paragraph.getRuns()) {
                for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    XWPFPictureData data = picture.getPictureData();
                    if (data != null) {
                        content.append(picture(data, data.getFileName(), picture.getWidth(), picture.getDepth()));
                    }
                }
                String text = run.text();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                String escaped = escape(text).replace("\n", "<br>").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;");
                if (run.isBold()) {
                    escaped = "<b>" + escaped + "</b>";
                }
                if (run.isItalic()) {
                    escaped = "<i>" + escaped + "</i>";
                }
                if (run.getUnderline() != null && run.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) {
                    escaped = "<u>" + escaped + "</u>";
                }
                content.append(escaped);
            }
            if (content.length() == 0) {
                return;
            }
            body.append('<').append(tag).append('>').append(content).append("</").append(tag).append('>');
        }

        String picture(XWPFPictureData data, String name, double widthPoints, double heightPoints) {
            placed.add(data);
            return decoded(data.getData(), name, widthPoints, heightPoints);
        }

        /** A picture in place, or a marker if Java cannot decode it; the size as laid out in the document. */
        String decoded(byte[] bytes, String name, double widthPoints, double heightPoints) {
            BufferedImage image = null;
            try {
                image = bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException | RuntimeException unreadable) {
                image = null;
            }
            if (image == null) {
                missing++;
                return "<i>" + escape("[Bild „" + (name == null ? "ohne Namen" : name)
                        + "“ – dieses Format lässt sich hier nicht anzeigen]") + "</i><br>";
            }
            String src = IMAGE_BASE + images.size();
            images.put(src, image);
            int width = widthPoints > 0 ? (int) Math.round(widthPoints * PIXELS_PER_POINT) : image.getWidth();
            int height = heightPoints > 0 ? (int) Math.round(heightPoints * PIXELS_PER_POINT)
                    : (int) Math.round(image.getHeight() * (width / (double) image.getWidth()));
            return "<img src=\"" + src + "\" width=\"" + width + "\" height=\"" + height + "\"><br>";
        }

        Rendered rendered(String text) {
            String plain = text == null || text.isBlank() ? (images.isEmpty() ? NO_TEXT : "") : text;
            if (plain.length() > MAX_PREVIEW_CHARACTERS) {
                plain = plain.substring(0, MAX_PREVIEW_CHARACTERS)
                        + "\n\n[Die Vorschau wurde gekürzt. Das Original bleibt unverändert.]";
            }
            String html = body.length() == 0 ? "<p>" + escape(NO_TEXT) + "</p>" : body.toString();
            return new Rendered("<html><body>" + html + "</body></html>", Map.copyOf(images), plain.isEmpty()
                    ? NO_TEXT : plain, missing);
        }
    }

    /** {@code h1} … {@code h3} for heading styles („Heading 1“, „Überschrift2“ …), otherwise {@code p}. */
    private static String headingTag(String style) {
        if (style == null) {
            return "p";
        }
        String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.startsWith("heading") || normalized.startsWith("berschrift")
                || normalized.startsWith("überschrift")) {
            char level = normalized.charAt(normalized.length() - 1);
            return level >= '1' && level <= '3' ? "h" + level : "h3";
        }
        if (normalized.equals("title") || normalized.equals("titel")) {
            return "h1";
        }
        return "p";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static boolean supports(String fileName, String mediaType) {
        if (supports(fileName)) {
            return true;
        }
        if (!ReaderAttachmentKind.extension(fileName).isEmpty()) {
            return false;
        }
        String type = ReaderAttachmentKind.baseType(mediaType);
        return "application/msword".equals(type) || type.contains("wordprocessingml");
    }

    static boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".docm")
                || name.endsWith(".dot") || name.endsWith(".dotx");
    }
}
