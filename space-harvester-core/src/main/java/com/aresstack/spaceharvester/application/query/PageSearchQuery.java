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
 * Eine Titel- und Metadatensuche (#28, erste Ausbaustufe) über die gespeicherten Kopfdaten einer Quelle oder, ohne
 * {@code sourceId}, aller Quellen, die der Aufrufer sehen darf (#114). Alle gesetzten Bedingungen gelten zugleich; ohne
 * Bedingung trifft jede Seite im Suchumfang. Global gelten die Filter in jeder Quelle: {@code spaceKey=DEV} heißt
 * „Space DEV in jeder freigegebenen Quelle“, {@code pageId} „diese ID in jeder Quelle“. Eine Quellenliste
 * ({@code sourceIds}) schränkt die globale Suche auf diese Quellen ein; ohne Liste umfasst sie alle.
 *
 * <p>Titelsuche: Der Titel wird an jedem Zeichen, das weder Buchstabe noch Ziffer ist, in Wörter zerlegt und klein
 * geschrieben ({@link Locale#ROOT}); jeder Suchbegriff muss als ganzes Wort darin vorkommen. Kein Präfix, keine
 * Unschärfe, keine Abfragesprache: Sonderzeichen trennen nur Wörter. Volltext über den Inhalt folgt getrennt.
 *
 * @param sourceId       nur diese Quelle oder {@code null} für alle freigegebenen Quellen (globale Suche)
 * @param sourceIds      nur bei globaler Suche: höchstens diese Quellen; leer für alle freigegebenen Quellen.
 *                       Unbekannte oder nicht freigegebene Quellen der Liste fallen wie bei der globalen Suche still
 *                       weg
 * @param spaceKeys      nur Seiten dieser Spaces (nach aktuellen Kopfdaten); leer für alle Spaces der Quelle
 * @param titleTerms     Suchbegriffe, bereits zerlegt und klein geschrieben; leer ohne Titelbedingung
 * @param pageId         genau diese Seite oder {@code null}
 * @param parentPageId   nur direkte Kinder dieser Seite oder {@code null}
 * @param version        genau diese Version der Quelle oder {@code null}
 * @param modifiedFrom   Änderungszeit ab einschließlich oder {@code null}
 * @param modifiedBefore Änderungszeit vor ausschließlich oder {@code null}; Seiten ohne Änderungszeit treffen bei
 *                       einer gesetzten Zeitgrenze nie
 */
public record PageSearchQuery(String sourceId, Set<String> sourceIds, Set<String> spaceKeys, List<String> titleTerms, String pageId,
                              String parentPageId, String version, Instant modifiedFrom, Instant modifiedBefore,
                              SearchSort sort) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_TITLE_TERMS = 8;
    public static final int MAX_SPACE_KEYS = 20;
    public static final int MAX_SOURCE_IDS = 50;
    public static final int MAX_VALUE_LENGTH = 255;

    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    public PageSearchQuery {
        if (sourceId != null && sourceId.isEmpty()) {
            sourceId = null;
        }
        sourceIds = Set.copyOf(new TreeSet<>(sourceIds));
        if (sourceId != null && !sourceIds.isEmpty()) {
            throw new InvalidQueryException("sourceId und sourceIds schließen sich aus");
        }
        spaceKeys = Set.copyOf(new TreeSet<>(spaceKeys));
        titleTerms = List.copyOf(titleTerms);
        Objects.requireNonNull(sort, "sort");
    }

    /** Wie {@link #of(String, List, List, String, String, String, String, Instant, Instant, SearchSort)} ohne Quellenliste. */
    public static PageSearchQuery of(String sourceId, List<String> spaceKeys, String title, String pageId,
                                     String parentPageId, String version, Instant modifiedFrom,
                                     Instant modifiedBefore, SearchSort sort) {
        return of(sourceId, List.of(), spaceKeys, title, pageId, parentPageId, version, modifiedFrom, modifiedBefore,
                sort);
    }

    /**
     * Prüft und normalisiert die Eingaben der API.
     *
     * @param sourceId  Quelle oder {@code null} bzw. leer für die globale Suche
     * @param sourceIds Quellen, auf die die globale Suche beschränkt ist, oder leer für alle; nicht mit
     *                  {@code sourceId}
     * @param title     Suchtext für den Titel oder {@code null}
     * @throws InvalidQueryException bei überschrittenen Grenzen, widersprüchlichen Zeitgrenzen oder {@code sourceId}
     *                               zusammen mit {@code sourceIds}
     */
    public static PageSearchQuery of(String sourceId, List<String> sourceIds, List<String> spaceKeys, String title,
                                     String pageId, String parentPageId, String version, Instant modifiedFrom,
                                     Instant modifiedBefore, SearchSort sort) {
        Set<String> sources = new TreeSet<>();
        for (String id : sourceIds == null ? List.<String>of() : sourceIds) {
            sources.add(value("sourceIds", id));
        }
        if (sources.size() > MAX_SOURCE_IDS) {
            throw new InvalidQueryException("höchstens " + MAX_SOURCE_IDS + " sourceIds");
        }
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
        return new PageSearchQuery(sourceId, sources, spaces, terms, optional("pageId", pageId),
                optional("parentPageId", parentPageId), optional("version", version), modifiedFrom, modifiedBefore,
                sort);
    }

    /** Ob die Suche alle freigegebenen Quellen umfasst statt genau einer. */
    public boolean global() {
        return sourceId == null;
    }

    /**
     * Dieselbe Anfrage mit anderer Quelle, Seite und Sortierung, z. B. zum Nachschlagen eines Kandidaten. Mit
     * {@code sourceId} entfällt die Quellenliste.
     */
    public PageSearchQuery with(String sourceId, String pageId, SearchSort sort) {
        return new PageSearchQuery(sourceId, sourceId == null ? sourceIds : Set.of(), spaceKeys, titleTerms, pageId, parentPageId, version, modifiedFrom,
                modifiedBefore, sort);
    }

    /** Zerlegt einen Text wie einen Titel in klein geschriebene Wörter; Maßstab für jede Umsetzung des Index. */
    public static List<String> words(String text) {
        return WORD_SEPARATOR.splitAsStream(text.toLowerCase(Locale.ROOT)).filter(word -> !word.isEmpty()).toList();
    }

    /** Trifft die Anfrage diese Kopfdaten? Referenz der Semantik für Fakes und Tests des Index. */
    public boolean matches(ListedPage page) {
        var metadata = page.header().metadata();
        return (sourceId == null || page.header().key().sourceId().equals(sourceId))
                && (sourceIds.isEmpty() || sourceIds.contains(page.header().key().sourceId()))
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
        // Jeder Wert mit Länge, fehlende als "-": kein Wert kann mit einem anderen oder mit "fehlt" zusammenfallen. Die
        // globale Suche (sourceId fehlt) hat damit einen anderen Fingerabdruck als jede Einzelquelle. Eine Quellenliste
        // folgt mit "S" (nie eine Ziffer), sodass Cursor ohne Liste ihren Fingerabdruck von vor der Liste behalten.
        StringBuilder canonical = new StringBuilder();
        field(canonical, sourceId);
        if (!sourceIds.isEmpty()) {
            canonical.append('S').append(sourceIds.size()).append('[');
            sourceIds.stream().sorted().forEach(id -> field(canonical, id));
        }
        canonical.append(spaceKeys.size()).append('[');
        spaceKeys.stream().sorted().forEach(spaceKey -> field(canonical, spaceKey));
        canonical.append(titleTerms.size()).append('[');
        titleTerms.forEach(term -> field(canonical, term));
        for (Object value : new Object[]{pageId, parentPageId, version, modifiedFrom, modifiedBefore, sort}) {
            field(canonical, value == null ? null : value.toString());
        }
        return canonical.toString();
    }

    private static void field(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append('-');
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
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
