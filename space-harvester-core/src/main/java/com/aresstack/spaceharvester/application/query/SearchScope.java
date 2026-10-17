package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Die Quellen, die eine Suche lesen darf (#114), für Titelsuche ({@link PageSearch}) und Relevanzsuche gleich
 * bestimmt.
 *
 * <ul>
 *   <li>Mit {@code sourceId}: genau diese Quelle. Ohne {@link AccessKind#METADATA} für jeden genannten Space bzw. die
 *       Quelle scheitert die Suche wie vor #114 mit {@link AccessDeniedException} (403).</li>
 *   <li>Ohne {@code sourceId}: jede konfigurierte Quelle, für die der Aufrufer {@code metadata} hat, mit
 *       {@code spaceKey} für mindestens einen der genannten Spaces. Andere Quellen fallen still weg: Eine globale Suche
 *       verrät weder durch 403 noch durch Zähler, dass es sie gibt. Mit Quellenliste ({@code sourceIds}) zählen nur
 *       deren Quellen; unbekannte oder nicht freigegebene IDs der Liste fallen ebenso still weg.</li>
 * </ul>
 *
 * Danach prüft der Aufrufer weiter jeden Treffer an seiner Seite; der Umfang ist nur die Vorauswahl im Index.
 *
 * @param sourceId  die angefragte Quelle oder {@code null} für die globale Suche
 * @param sourceIds die lesbaren Quellen, sortiert; global auch leer
 */
public record SearchScope(String sourceId, Set<String> sourceIds) {

    public SearchScope {
        sourceIds = Set.copyOf(new TreeSet<>(sourceIds));
    }

    /**
     * Bestimmt den Umfang und prüft die Rechte vor dem Lesen.
     *
     * @throws AccessDeniedException nur mit {@code sourceId}, wenn ein genannter Space oder die Quelle nicht für
     *                               {@code metadata} freigegeben ist
     */
    public static SearchScope authorize(AccessGuard access, SourceCatalog catalog, AccessContext caller,
                                        PageSearchQuery query) {
        String sourceId = query.sourceId();
        if (sourceId != null) {
            if (query.spaceKeys().isEmpty()) {
                access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
            } else {
                for (String spaceKey : query.spaceKeys()) {
                    access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
                }
            }
            return new SearchScope(sourceId, Set.of(sourceId));
        }
        Set<String> visible = new TreeSet<>();
        for (HarvestSource source : catalog.all()) {
            String id = source.id();
            if (!query.sourceIds().isEmpty() && !query.sourceIds().contains(id)) {
                continue;
            }
            boolean permitted = query.spaceKeys().isEmpty()
                    ? access.permits(caller, ResourceScope.source(id), AccessKind.METADATA)
                    : query.spaceKeys().stream().anyMatch(spaceKey ->
                            access.permits(caller, ResourceScope.space(id, spaceKey), AccessKind.METADATA));
            if (permitted) {
                visible.add(id);
            }
        }
        return new SearchScope(null, visible);
    }

    public boolean global() {
        return sourceId == null;
    }

    /**
     * Mit {@code sourceId}: Die Quelle muss konfiguriert sein.
     *
     * @throws UnknownSourceException wenn nicht
     */
    public void requireKnown(SourceCatalog catalog) {
        if (sourceId != null && catalog.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
    }

    /**
     * Der Indexstand, den die Suche liest.
     *
     * @return global leer, wenn keine der lesbaren Quellen im Index liegt
     * @throws CacheMissException mit {@code sourceId}, wenn für die Quelle nichts im Index liegt
     */
    public Optional<SearchIndexState> state(PageSearchIndex index) {
        if (sourceId != null) {
            return Optional.of(index.state(sourceId)
                    .orElseThrow(() -> new CacheMissException(sourceId, "Quelle nicht im Suchindex")));
        }
        return sourceIds.isEmpty() ? Optional.empty() : index.state(sourceIds);
    }
}
