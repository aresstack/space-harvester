package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;

import java.util.Collections;
import java.util.List;

/**
 * Generischer Port zu einer konfigurierten Wissensquelle (MediaWiki, Confluence, später SharePoint,
 * Dateien ...). Der Application-Kern kennt nur diesen Port; Protokoll, Authentifizierung, Paging und
 * Formatumwandlung bleiben im Adapter.
 *
 * <p>Vertrag für Implementierungen (geprüft durch {@code KnowledgeSourceContractTest} aus den
 * Test-Fixtures dieses Moduls):
 * <ul>
 *   <li>Alle gelieferten Ressourcen tragen {@link #sourceId()} und eine stabile
 *       {@link KnowledgeResourceId}: dieselbe Ressource hat bei jedem Lauf dieselbe ID.</li>
 *   <li>{@link #discover(SourceScope)} liefert jede Ressource höchstens einmal, in stabiler Reihenfolge,
 *       höchstens {@link SourceScope#maxResources()} Stück und ohne Inhalt.</li>
 *   <li>{@link #load(KnowledgeResourceId)} liefert den aktuellen Stand als {@link KnowledgeDocument}
 *       (Klartext mit leichtgewichtigem Markdown, siehe dort) samt aktueller Revision.</li>
 *   <li>Fehler sind {@link KnowledgeSourceException} mit fachlicher Fehlerart; Zugangsdaten, Tokens und
 *       rohe Antworten erscheinen weder in Meldungen noch in Metadaten.</li>
 *   <li>Implementierungen sind thread-sicher und halten keinen globalen Zustand.</li>
 * </ul>
 */
public interface KnowledgeSourcePort {

    /** Die konfigurierte Quelle, zu der alle gelieferten Ressourcen gehören. */
    SourceAddress sourceId();

    /**
     * Ermittelt die Ressourcen im angegebenen Ausschnitt der Quelle, ohne ihren Inhalt zu laden.
     *
     * <p>Nicht auffindbare Startpunkte werden übersprungen; einzelne Fehler beim Verfolgen von Links oder
     * Kindern dürfen übersprungen werden. Grundsätzliche Fehler (Quelle nicht erreichbar, Zugriff
     * verweigert) beenden den Aufruf mit einer Ausnahme.
     */
    List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException;

    /**
     * Lädt den aktuellen Inhalt einer Ressource dieser Quelle.
     *
     * <p>Leitet die Quelle die Ressource weiter (z. B. Wiki-Redirect), trägt das Dokument die ID des Ziels;
     * Aufrufer vergleichen deshalb {@code document.id()} statt die angefragte ID weiterzuverwenden.
     *
     * @throws KnowledgeSourceException {@code NOT_FOUND}, wenn die Ressource nicht (mehr) existiert;
     *                                  {@code UNSUPPORTED}, wenn die ID nicht zu dieser Quelle gehört
     */
    KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException;

    /**
     * Ausgehende Verweise einer Ressource auf andere Ressourcen derselben Quelle (Wiki-Links,
     * Confluence-Kindseiten). Externe Links werden nicht gemeldet. Jedes Ziel höchstens einmal, in der
     * Reihenfolge der Quelle. Adapter dürfen Ziele vorab auflösen (Weiterleitungen) und nicht existierende
     * Ziele weglassen; verbindlich prüft das erst {@link #load(KnowledgeResourceId)}.
     *
     * @throws KnowledgeSourceException {@code NOT_FOUND}, wenn die Ressource selbst nicht existiert
     */
    List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException;

    /**
     * Die Bereiche der Quelle, die der Benutzer an- und abhaken kann (Confluence: alle Spaces), in der Reihenfolge
     * der Quelle; leer bei Quellen ohne Bereiche. Abgehakte Bereiche gibt der Aufrufer über
     * {@link SourceScope#excludedPartitions()} an {@link #discover(SourceScope)} zurück.
     */
    default List<SourcePartition> partitions() throws KnowledgeSourceException {
        return Collections.emptyList();
    }
}
