package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Ein Exportauftrag: Quelle, Space, gegebenenfalls eine Startseite, und Formate. Ob der Space schon im Cache liegt, ist
 * keine Voraussetzung; der Export liest ihn bei Bedarf selbst ein ({@link Exports}). Eine Ausgabe aus dem Bestand
 * ({@link #holdingsOnly()}, ADR 0029) liest dagegen nur den Seiten-Cache.
 *
 * @param sourceId Quelle
 * @param spaceKey Space
 * @param owner    auslösender Client; nur er sieht Auftrag und Artefakt
 * @param formats  Formate aus {@link Exports#formats()}; leer für nur das Raw-First-Artefakt
 * @param refresh  {@code true}: vor dem Export gegen die Quelle abgleichen, auch wenn eine bestätigte Generation
 *                 vorliegt; {@code false}: eine bestätigte Generation wiederverwenden und nur einlesen, wenn keine
 *                 vorliegt
 * @param visible    Seiten, die der Auftraggeber lesen darf (Rechte je Seite, bei delegierten Benutzern über die
 *                   Quelle); die übrigen gehören nicht zu seinem Export und werden nirgends genannt
 * @param authorized ob die Freigabe des Auftraggebers für diesen Export noch gilt; geprüft zu Beginn und vor dem
 *                   Veröffentlichen, damit ein entzogenes Recht oder eine abgelaufene Anmeldung nichts veröffentlicht
 * @param rootPageId Startseite eines Teilbaum-Exports: exportiert werden nur sie und ihre Unterseiten nach den
 *                   Elternbeziehungen des Cache; {@code null} für den ganzen Space
 * @param depth      nur mit Startseite: wie viele Ebenen unter ihr mitkommen; {@code 0} für die Startseite allein ohne
 *                   Unterseiten (Einzelseite, wie „Seite exportieren“ im Enterprise AI Client), {@code null} für alle
 * @param holdingsOnly Ausgabe aus dem Bestand (ADR 0029): liest nur den Seiten-Cache, nie die Quelle; enthält die
 *                     gespeicherten Seiten des Umfangs, auch veraltete im gespeicherten Stand, und nennt die übrigen
 *                     nur mit ihrer Zahl. Schließt {@code refresh} aus.
 */
public record ExportRequest(String sourceId, String spaceKey, String owner, List<String> formats, boolean refresh,
                            Predicate<PageHeader> visible, BooleanSupplier authorized, String rootPageId,
                            Integer depth, boolean holdingsOnly) {

    public ExportRequest {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        formats = formats == null ? List.of() : List.copyOf(formats);
        Objects.requireNonNull(visible, "visible");
        Objects.requireNonNull(authorized, "authorized");
        if (rootPageId != null && rootPageId.isBlank()) {
            throw new IllegalArgumentException("rootPageId darf nicht leer sein");
        }
        if (depth != null && depth < 0) {
            throw new IllegalArgumentException("depth darf nicht negativ sein");
        }
        if (depth != null && rootPageId == null) {
            throw new IllegalArgumentException("depth gibt es nur mit rootPageId");
        }
        if (holdingsOnly && refresh) {
            throw new IllegalArgumentException("Eine Ausgabe aus dem Bestand liest die Quelle nicht; refresh geht "
                    + "nicht");
        }
    }

    /** Export mit Beschaffung bei Bedarf (ADR 0010). */
    public ExportRequest(String sourceId, String spaceKey, String owner, List<String> formats, boolean refresh,
                         Predicate<PageHeader> visible, BooleanSupplier authorized, String rootPageId,
                         Integer depth) {
        this(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth, false);
    }

    /** Teilbaum ohne Tiefenbeschränkung. */
    public ExportRequest(String sourceId, String spaceKey, String owner, List<String> formats, boolean refresh,
                         Predicate<PageHeader> visible, BooleanSupplier authorized, String rootPageId) {
        this(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, null);
    }

    /** Export des ganzen Space. */
    public ExportRequest(String sourceId, String spaceKey, String owner, List<String> formats, boolean refresh,
                         Predicate<PageHeader> visible, BooleanSupplier authorized) {
        this(sourceId, spaceKey, owner, formats, refresh, visible, authorized, null);
    }

    /** Ohne Abgleich vorab und ohne Einschränkung je Seite. */
    public static ExportRequest of(String sourceId, String spaceKey, String owner, List<String> formats) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, false, header -> true, () -> true);
    }

    /** Ausgabe des ganzen Space aus dem Bestand (ADR 0029), ohne Einschränkung je Seite. */
    public static ExportRequest fromHoldings(String sourceId, String spaceKey, String owner, List<String> formats) {
        return of(sourceId, spaceKey, owner, formats).withHoldingsOnly(true);
    }

    public ExportRequest withRefresh(boolean refresh) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth,
                holdingsOnly);
    }

    public ExportRequest withVisible(Predicate<PageHeader> visible) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth,
                holdingsOnly);
    }

    public ExportRequest withAuthorized(BooleanSupplier authorized) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth,
                holdingsOnly);
    }

    /**
     * Derselbe Umfang als Ausgabe aus dem Bestand oder als Export mit Beschaffung.
     *
     * @throws IllegalArgumentException wenn {@code holdingsOnly} und {@link #refresh()} zusammenkämen
     */
    public ExportRequest withHoldingsOnly(boolean holdingsOnly) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth,
                holdingsOnly);
    }

    /**
     * Derselbe Auftrag, beschränkt auf eine Seite und ihre Unterseiten.
     *
     * @param rootPageId Startseite im Space oder {@code null} für den ganzen Space
     */
    public ExportRequest withRoot(String rootPageId) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId,
                rootPageId == null ? null : depth, holdingsOnly);
    }

    /**
     * Derselbe Teilbaum, beschränkt auf {@code depth} Ebenen unter der Startseite.
     *
     * @param depth {@code 0} für die Startseite allein, {@code null} für alle Ebenen
     * @throws IllegalArgumentException ohne Startseite oder bei negativer Tiefe
     */
    public ExportRequest withDepth(Integer depth) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId, depth,
                holdingsOnly);
    }

    /** Derselbe Auftrag für eine einzelne Seite ohne Unterseiten ({@code withRoot(pageId).withDepth(0)}). */
    public ExportRequest withPage(String pageId) {
        return withRoot(Objects.requireNonNull(pageId, "pageId")).withDepth(0);
    }
}
