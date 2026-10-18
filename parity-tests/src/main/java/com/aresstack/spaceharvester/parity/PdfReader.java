package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.DocumentOutline.Heading;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liest ein PDF in das neutrale Modell. Überschriften sind die Lesezeichen; Tabellen sind im PDF nicht erkennbar
 * ({@link DocumentOutline#tables()} ist {@code null}).
 */
public final class PdfReader {

    /** Ein Verzeichnis beginnt mit dieser Zeile auf einer der ersten Seiten. */
    private static final Pattern TOC_TITLE = Pattern.compile("(?m)^\\s*(Inhalt|Inhaltsverzeichnis)\\s*$");
    /** Eine Seitenzahl als eigene Zeile: „12“, „12 / 85“, „Seite 12 von 85“. */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?m)^\\s*(Seite\\s+)?\\d{1,4}(\\s*(/|von)\\s*\\d{1,4})?\\s*$");
    private static final int TOC_PAGES = 3;

    private PdfReader() {
    }

    public static DocumentOutline read(Path file) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            List<Heading> headings = new ArrayList<>();
            PDDocumentOutline outline = pdf.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                collect(outline, 1, headings);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            boolean toc = false;
            int numbered = 0;
            int pages = pdf.getNumberOfPages();
            Set<String> external = new LinkedHashSet<>();
            int internal = 0;
            Map<PDImageXObject, Boolean> images = new IdentityHashMap<>();
            Set<Object> seenStreams = new LinkedHashSet<>();
            for (int index = 1; index <= pages; index++) {
                stripper.setStartPage(index);
                stripper.setEndPage(index);
                String pageText = stripper.getText(pdf);
                if (PAGE_NUMBER.matcher(pageText).find()) {
                    numbered++;
                }
                PDPage page = pdf.getPage(index - 1);
                int goTo = 0;
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        if (action instanceof PDActionURI uri && uri.getURI() != null) {
                            external.add(uri.getURI());
                        } else if (action instanceof PDActionGoTo || link.getDestination() != null) {
                            internal++;
                            goTo++;
                        }
                    }
                }
                if (index <= TOC_PAGES && goTo >= 2 && TOC_TITLE.matcher(pageText).find()) {
                    toc = true;
                } else {
                    // Verzeichnisseiten gehören zum Exporter, nicht zum Inhalt
                    text.append(pageText);
                }
                images(page.getResources(), images, seenStreams);
            }
            boolean pageNumbers = pages > 1 && numbered * 2 >= pages;
            String plain = text.toString();
            return new DocumentOutline("pdf", headings, toc, false, pageNumbers, headings.size(), images.size(),
                    Patterns.count(Patterns.IMAGE_NOTE, plain), null, external, internal,
                    Patterns.count(Patterns.MERMAID, plain), plain);
        }
    }

    private static void collect(PDOutlineNode node, int level, List<Heading> headings) {
        for (var item : node.children()) {
            headings.add(new Heading(level, item.getTitle() == null ? "" : item.getTitle().strip()));
            collect(item, level + 1, headings);
        }
    }

    private static void images(PDResources resources, Map<PDImageXObject, Boolean> images, Set<Object> seen)
            throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object == null || !seen.add(object.getCOSObject())) {
                continue;
            }
            if (object instanceof PDImageXObject image) {
                images.put(image, Boolean.TRUE);
            } else if (object instanceof PDFormXObject form) {
                images(form.getResources(), images, seen);
            }
        }
    }
}
