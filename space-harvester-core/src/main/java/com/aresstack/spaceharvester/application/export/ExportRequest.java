package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Ein Exportauftrag: Quelle, Space, gegebenenfalls eine Startseite, und Formate. Ob der Space schon im Cache liegt, ist keine Voraussetzung; der Export
 * liest ihn bei Bedarf selbst ein ({@link Exports}).
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
 */
public record ExportRequest(String sourceId, String spaceKey, String owner, List<String> formats, boolean refresh,
                            Predicate<PageHeader> visible, BooleanSupplier authorized, String rootPageId) {

    public ExportRequest {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        formats = formats == null ? List.of() : List.copyOf(formats);
        Objects.requireNonNull(visible, "visible");
        Objects.requireNonNull(authorized, "authorized");
        if (rootPageId != null && rootPageId.isBlank()) {
            throw new IllegalArgumentException("rootPageId darf nicht leer sein");
        }
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

    public ExportRequest withRefresh(boolean refresh) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId);
    }

    public ExportRequest withVisible(Predicate<PageHeader> visible) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId);
    }

    public ExportRequest withAuthorized(BooleanSupplier authorized) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId);
    }

    /**
     * Derselbe Auftrag, beschränkt auf eine Seite und ihre Unterseiten.
     *
     * @param rootPageId Startseite im Space oder {@code null} für den ganzen Space
     */
    public ExportRequest withRoot(String rootPageId) {
        return new ExportRequest(sourceId, spaceKey, owner, formats, refresh, visible, authorized, rootPageId);
    }
}
