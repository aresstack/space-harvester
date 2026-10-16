package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Eine Titel- und Metadatensuche (#28, erste Ausbaustufe) über die gespeicherten Kopfdaten einer Quelle. Alle
 * gesetzten Bedingungen gelten zugleich; ohne Bedingung trifft jede Seite der Quelle.
 *
 * <p>Titelsuche: Der Titel wird an jedem Zeichen, das weder Buchstabe noch Ziffer ist, in Wörter zerlegt und klein
 * geschrieben ({@link Locale#ROOT}); jeder Suchbegriff muss als ganzes Wort darin vorkommen. Kein Präfix, keine
 * Unschärfe, keine Abfragesprache: Sonderzeichen trennen nur Wörter. Volltext über den Inhalt folgt getrennt.
 *
 * @param spaceKeys      nur Seiten dieser Spaces (nach aktuellen Kopfdaten); leer für alle Spaces der Quelle
 * @param titleTerms     Suchbegriffe, bereits zerlegt und klein geschrieben; leer ohne Titelbedingung
 * @param pageId         genau diese Seite oder {@code null}
 * @param parentPageId   nur direkte Kinder dieser Seite oder {@code null}
 * @param version        genau diese Version der Quelle oder {@code null}
 * @param modifiedFrom   Änderungszeit ab einschließlich oder {@code null}
 * @param modifiedBefore Änderungszeit vor ausschließlich oder {@code null}; Seiten ohne Änderungszeit treffen bei
 *                       einer gesetzten Zeitgrenze nie
 */
public record PageSearchQuery(String sourceId, Set<String> spaceKeys, List<String> titleTerms, String pageId,
                              String parentPageId, String version, Instant modifiedFrom, Instant modifiedBefore,
                              SearchSort sort) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_TITLE_TERMS = 8;
    public static final int MAX_SPACE_KEYS = 20;
    public static final int MAX_VALUE_LENGTH = 255;

    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    public PageSearchQuery {
        Objects.requireNonNull(sourceId, "sourceId");
        spaceKeys = Set.copyOf(new TreeSet<>(spaceKeys));
        titleTerms = List.copyOf(titleTerms);
        Objects.requireNonNull(sort, "sort");
    }

    /**
     * Prüft und normalisiert die Eingaben der API.
     *
     * @param title Suchtext für den Titel oder {@code null}
     * @throws InvalidQueryException bei überschrittenen Grenzen oder widersprüchlichen Zeitgrenzen
     */
    public static PageSearchQuery of(String sourceId, List<String> spaceKeys, String title, String pageId,
                                     String parentPageId, String version, Instant modifiedFrom,
                                     Instant modifiedBefore, SearchSort sort) {
        Set<String> spaces = new TreeSet<>();
        for (String spaceKey : spaceKeys == null ? List.<String>of() : spaceKeys) {
            spaces.add(value("spaceKey", spaceKey));
        }
        if (spaces.size() > MAX_SPACE_KEYS) {
            throw new InvalidQueryException("höchstens " + MAX_SPACE_KEYS + " spaceKey");
        }
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new InvalidQueryException("title hat höchstens " + MAX_TITLE_LENGTH + " Zeichen");
        }
        List<String> terms = title == null ? List.of() : words(title).stream().distinct().toList();
        if (terms.size() > MAX_TITLE_TERMS) {
            throw new InvalidQueryException("title hat höchstens " + MAX_TITLE_TERMS + " Wörter");
        }
        if (title != null && !title.isBlank() && terms.isEmpty()) {
            throw new InvalidQueryException("title enthält kein Wort");
        }
        if (modifiedFrom != null && modifiedBefore != null && !modifiedFrom.isBefore(modifiedBefore)) {
            throw new InvalidQueryException("modifiedFrom muss vor modifiedBefore liegen");
        }
        return new PageSearchQuery(sourceId, spaces, terms, optional("pageId", pageId),
                optional("parentPageId", parentPageId), optional("version", version), modifiedFrom, modifiedBefore,
                sort);
    }

    /** Zerlegt einen Text wie einen Titel in klein geschriebene Wörter; Maßstab für jede Umsetzung des Index. */
    public static List<String> words(String text) {
        return WORD_SEPARATOR.splitAsStream(text.toLowerCase(Locale.ROOT)).filter(word -> !word.isEmpty()).toList();
    }

    /** Trifft die Anfrage diese Kopfdaten? Referenz der Semantik für Fakes und Tests des Index. */
    public boolean matches(ListedPage page) {
        var metadata = page.header().metadata();
        return page.header().key().sourceId().equals(sourceId)
                && (spaceKeys.isEmpty() || spaceKeys.contains(metadata.partitionKey()))
                && (titleTerms.isEmpty() || words(metadata.title()).containsAll(titleTerms))
                && (pageId == null || pageId.equals(metadata.pageId()))
                && (parentPageId == null || parentPageId.equals(metadata.parentPageId()))
                && (version == null || version.equals(metadata.version()))
                && (modifiedFrom == null || metadata.modifiedAt() != null
                        && !metadata.modifiedAt().isBefore(modifiedFrom))
                && (modifiedBefore == null || metadata.modifiedAt() != null
                        && metadata.modifiedAt().isBefore(modifiedBefore));
    }

    /** Kanonische Form, an die ein Cursor gebunden wird. */
    String canonical() {
        return String.join("\u0001", sourceId, String.join("\u0002", spaceKeys), String.join("\u0002", titleTerms),
                String.valueOf(pageId), String.valueOf(parentPageId), String.valueOf(version),
                String.valueOf(modifiedFrom), String.valueOf(modifiedBefore), sort.name());
    }

    private static String optional(String name, String value) {
        return value == null || value.isEmpty() ? null : value(name, value);
    }

    private static String value(String name, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw new InvalidQueryException(name + " muss 1 bis " + MAX_VALUE_LENGTH + " Zeichen haben");
        }
        return value;
    }
}
