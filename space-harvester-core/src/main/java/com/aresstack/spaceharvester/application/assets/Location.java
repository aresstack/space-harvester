package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;

/**
 * Fundstelle eines Blocks (ADR 0026, Punkt 4). Jedes Feld wird nur gesetzt, wenn der Extraktor den Wert aus der Datei
 * liest; gezählt oder geschätzt wird nie. Eine Word-Seite gibt es nicht, der Abschnitt ergibt sich aus den
 * Überschriften davor, Zeichenpositionen gehören zur Ansicht. Die Excel-Zelle steht an der {@link Block.Cell}.
 *
 * @param page  PDF-Seite ab 1
 * @param slide PowerPoint-Folie ab 1
 * @param sheet Name des Excel-Blatts
 */
public record Location(Optional<Integer> page, Optional<Integer> slide, Optional<String> sheet) {

    public Location {
        page = page == null ? Optional.empty() : page;
        slide = slide == null ? Optional.empty() : slide;
        sheet = sheet == null ? Optional.empty() : sheet.filter(name -> !name.isBlank());
        if (page.filter(value -> value < 1).isPresent() || slide.filter(value -> value < 1).isPresent()) {
            throw new IllegalArgumentException("Seite und Folie zählen ab 1");
        }
        if (page.isEmpty() && slide.isEmpty() && sheet.isEmpty()) {
            throw new IllegalArgumentException("Fundstelle ohne Angabe; dann keine Fundstelle setzen");
        }
    }

    public static Location page(int page) {
        return new Location(Optional.of(page), Optional.empty(), Optional.empty());
    }

    public static Location slide(int slide) {
        return new Location(Optional.empty(), Optional.of(slide), Optional.empty());
    }

    public static Location sheet(String sheet) {
        return new Location(Optional.empty(), Optional.empty(), Optional.of(sheet));
    }
}
