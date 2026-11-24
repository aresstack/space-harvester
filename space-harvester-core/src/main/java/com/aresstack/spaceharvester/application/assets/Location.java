package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;

/**
 * Identify only locations supported by the source structure (ADR 0026, point 4).
 * Count OneNote page containers only when the parser explicitly emits their boundaries; never infer pages from text.
 * Eine Word-Seite gibt es nicht, der Abschnitt ergibt sich aus den
 * Überschriften davor, Zeichenpositionen gehören zur Ansicht. Die Excel-Zelle steht an der {@link Block.Cell}.
 *
 * @param page  PDF-Seite ab 1
 * @param slide PowerPoint-Folie ab 1
 * @param sheet Name des Excel-Blatts
 * @param notePage Position eines belegten OneNote-Seitencontainers ab 1 innerhalb der Extraktion, keine Original-ID
 */
public record Location(Optional<Integer> page, Optional<Integer> slide, Optional<String> sheet,
                       Optional<NotePageLocation> notePage) {

    public Location(Optional<Integer> page, Optional<Integer> slide, Optional<String> sheet) {
        this(page, slide, sheet, Optional.empty());
    }

    public Location {
        page = page == null ? Optional.empty() : page;
        slide = slide == null ? Optional.empty() : slide;
        sheet = sheet == null ? Optional.empty() : sheet.filter(name -> !name.isBlank());
        notePage = notePage == null ? Optional.empty() : notePage;
        if (page.filter(value -> value < 1).isPresent() || slide.filter(value -> value < 1).isPresent()) {
            throw new IllegalArgumentException("Seite und Folie zählen ab 1");
        }
        if (page.isEmpty() && slide.isEmpty() && sheet.isEmpty() && notePage.isEmpty()) {
            throw new IllegalArgumentException("Fundstelle ohne Angabe; dann keine Fundstelle setzen");
        }
    }

    public static Location page(int page) {
        return new Location(Optional.of(page), Optional.empty(), Optional.empty());
    }

    public static Location slide(int slide) {
        return new Location(Optional.empty(), Optional.of(slide), Optional.empty());
    }

    /** Locate a parser-confirmed OneNote page by its order within this extraction. */
    public static Location notePage(int index) {
        return new Location(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new NotePageLocation(index)));
    }

    public static Location sheet(String sheet) {
        return new Location(Optional.empty(), Optional.empty(), Optional.of(sheet));
    }
}
