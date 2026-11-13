package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.Location;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Foliennummern bei {@code .pptx} (ADR 0026, AP10; Architekt 13.11.2026): Tika gibt die Folien nicht in
 * Folienreihenfolge aus, {@link PptxSlideOrder} ordnet jeder ihre Position in {@code p:sldIdLst} zu. Geprüft gegen das
 * Original (POI liest die Folienreihenfolge): mehr als zehn Folien, umsortiert, eine ausgeblendet, Lücken in den
 * Kennungen und Kennungen außerhalb des Musters {@code rId<Zahl>}. Bricht, wenn Tika die Reihenfolge ändert.
 */
class PptxSlideOrderTest {

    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final int SLIDES = 14;

    @TempDir
    Path directory;

    @Test
    void theTestedTikaAndPoiVersionsAreTheOnesOnTheClasspath() {
        // Hebt jemand Tika oder POI an, bricht dieser Test: Reihenfolge prüfen, dann die Konstanten anheben.
        assertThat(org.apache.tika.Tika.getString()).isEqualTo(PptxSlideOrder.TIKA);
        assertThat(org.apache.poi.Version.getVersion()).isEqualTo(PptxSlideOrder.POI);
        assertThat(PptxSlideOrder.verified()).isTrue();
    }

    @Test
    void everyMarkNamesThePositionOfItsSlideInTheSlideList() throws Exception {
        Path pptx = renamed(presentation(directory.resolve("roh.pptx")), directory.resolve("folien.pptx"));
        Map<String, Integer> expected = originalOrder(pptx);
        assertThat(expected).hasSize(SLIDES);

        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        new TikaDocumentStructure().extract(pptx, "folien.pptx", PPTX, ExtractionLimits.DEFAULTS, sink);

        Map<String, Integer> found = new HashMap<>();
        Map<String, Integer> notes = new HashMap<>();
        for (Block block : sink.blocks) {
            String text = DocumentExtractions.plain(block).strip();
            Optional<Integer> slide = block.location().flatMap(Location::slide);
            if (text.startsWith("MARKE-")) {
                found.put(text, slide.orElse(0));
            } else if (text.startsWith("NOTIZ-")) {
                notes.put("MARKE-" + text.substring("NOTIZ-".length()), slide.orElse(0));
            }
        }
        assertThat(found).isEqualTo(expected);
        assertThat(notes).isNotEmpty().allSatisfy((mark, slide) -> assertThat(slide).isEqualTo(expected.get(mark)));
        // Die Reihenfolge der Blöcke ist Tikas, nicht die Folienreihenfolge (benannte Grenze): sonst wäre der Test
        // ohne Aussage.
        List<Integer> sequence = sink.blocks.stream().map(DocumentExtractions::plain).map(String::strip)
                .filter(text -> text.startsWith("MARKE-")).map(expected::get).toList();
        assertThat(sequence).isNotEqualTo(sequence.stream().sorted().toList());
    }

    @Test
    void withoutAnUnambiguousOrderThereIsNoSlideNumber() throws Exception {
        Path pptx = renamed(presentation(directory.resolve("roh.pptx")), directory.resolve("folien.pptx"));
        assertThat(PptxSlideOrder.of(pptx)).isPresent();

        // Eine Folie ohne Eintrag in sldIdLst, eine doppelte Kennung, fehlende presentation.xml.
        assertThat(PptxSlideOrder.of(edited(pptx, "ppt/presentation.xml",
                xml -> xml.replaceFirst("<p:sldId [^>]*/>", "")))).isEmpty();
        assertThat(PptxSlideOrder.of(edited(pptx, "ppt/presentation.xml",
                xml -> xml.replaceFirst("(<p:sldId [^>]*/>)", "$1$1")))).isEmpty();
        assertThat(PptxSlideOrder.of(edited(pptx, "ppt/presentation.xml", null))).isEmpty();
        assertThat(PptxSlideOrder.of(edited(pptx, "ppt/presentation.xml",
                xml -> xml.replaceFirst("^(<\\?xml[^>]*\\?>)",
                                "$1<!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>")
                        .replaceFirst("(<p:sldId [^>]*r:id=\")[^\"]*", "$1&x;"))))
                .as("keine externe Entität").isEmpty();

        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        new TikaDocumentStructure().extract(edited(pptx, "ppt/presentation.xml",
                xml -> xml.replaceFirst("<p:sldId [^>]*/>", "")), "folien.pptx", PPTX, ExtractionLimits.DEFAULTS, sink);
        assertThat(sink.text()).contains("MARKE-");
        assertThat(sink.blocks).allSatisfy(block -> assertThat(block.location().flatMap(Location::slide)).isEmpty());
    }

    /** {@value #SLIDES} Folien, umsortiert, Folie 3 ausgeblendet, Notizen an jeder dritten. */
    private static Path presentation(Path target) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow()) {
            for (int i = 1; i <= SLIDES; i++) {
                XSLFSlide slide = show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setText("MARKE-" + i);
                box.setAnchor(new java.awt.Rectangle(50, 50, 300, 50));
                if (i == 3) {
                    slide.setHidden(true);
                }
                if (i % 3 == 0) {
                    XSLFNotes notes = show.getNotesSlide(slide);
                    for (XSLFTextShape shape : notes.getPlaceholders()) {
                        if (shape.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                            shape.setText("NOTIZ-" + i);
                        }
                    }
                }
            }
            List<XSLFSlide> slides = show.getSlides();
            show.setSlideOrder(slides.get(12), 0);
            show.setSlideOrder(show.getSlides().get(5), 2);
            show.setSlideOrder(show.getSlides().get(1), 9);
            show.setSlideOrder(show.getSlides().get(13), 4);
            try (OutputStream out = Files.newOutputStream(target)) {
                show.write(out);
            }
        }
        return target;
    }

    /** Kennungen mit Lücken und außerhalb des Musters: rId7 → folie-a, rId9 → rId42, rId12 → Z1. */
    private static Path renamed(Path source, Path target) throws IOException {
        Map<String, String> ids = Map.of("rId7", "folie-a", "rId9", "rId42", "rId12", "Z1");
        return rewrite(source, target, (name, xml) -> {
            if (!name.equals("ppt/presentation.xml") && !name.equals("ppt/_rels/presentation.xml.rels")) {
                return xml;
            }
            String result = xml;
            for (Map.Entry<String, String> id : ids.entrySet()) {
                result = result.replace("\"" + id.getKey() + "\"", "\"" + id.getValue() + "\"");
            }
            return result;
        });
    }

    private Path edited(Path source, String part, java.util.function.UnaryOperator<String> change)
            throws IOException {
        Path target = Files.createTempFile(directory, "geaendert", ".pptx");
        return rewrite(source, target, (name, xml) -> !name.equals(part) ? xml
                : change == null ? null : change.apply(xml));
    }

    private static Path rewrite(Path source, Path target,
                                java.util.function.BiFunction<String, String, String> change) throws IOException {
        try (ZipFile in = new ZipFile(source.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry entry : java.util.Collections.list(in.entries())) {
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) {
                    String changed = change.apply(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                    if (changed == null) {
                        continue;
                    }
                    bytes = changed.getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(bytes);
                out.closeEntry();
            }
        }
        return target;
    }

    /** Marke → Position der Folie in sldIdLst, gelesen mit POI (Folienreihenfolge). */
    private static Map<String, Integer> originalOrder(Path pptx) throws IOException {
        Map<String, Integer> order = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(pptx); XMLSlideShow show = new XMLSlideShow(in)) {
            int position = 0;
            for (XSLFSlide slide : show.getSlides()) {
                position++;
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape text && text.getText().startsWith("MARKE-")) {
                        order.put(text.getText().strip(), position);
                    }
                }
            }
        }
        return order;
    }
}
