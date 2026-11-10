package com.aresstack.spaceharvester.core;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.facts.FactEdit;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphStore;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.persistence.files.FileInventoryLog;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Ablagen, die Daten je Quelle halten, als Liste für {@link SourceDataPurge}. Harvester und Dienst stellen sie aus
 * ihren Ablagen zusammen, damit „Daten bereinigen“ und „Quelle entfernen“ dasselbe löschen.
 *
 * <p>Bewusst nicht dabei: Exporte samt den Bildern, auf die sie verweisen (gehören ihrem Besitzer), Auftragsverlauf
 * und Suchlauf-Protokoll (quellübergreifende Diagnose mit eigener Frist), Git-Arbeitskopien der Quelle (Transport, kein
 * Bestand) sowie manuelle, quellübergreifende Begriffsbeziehungen (stammen vom Bearbeiter).
 */
public final class SourceDataStores {

    /** Kennung im Protokoll der Begriffsbeziehungen und Fakten. */
    public static final String ACTOR = "purge";

    private static final System.Logger LOG = System.getLogger(SourceDataStores.class.getName());

    private final List<SourceDataPurge.Store> stores = new ArrayList<>();

    private SourceDataStores() {
    }

    public static SourceDataStores builder() {
        return new SourceDataStores();
    }

    /** Wartende Seiten der Hintergrundfüllung; zuerst, damit sie nichts nachlädt. {@code null} ohne Füllung. */
    public SourceDataStores fillQueue(LuceneFillQueue queue) {
        return queue == null ? this : add("Warteschlange der Hintergrundfüllung", queue::removeSource);
    }

    /** Seiten samt Verlauf, Katalog, bestätigte Generationen, Abgleichstände und zurückgehaltene Seiten. */
    public SourceDataStores pageCache(LucenePageCache cache) {
        return add("Seiten-Cache, Katalog und Abgleichstände", cache::removeSource);
    }

    /** Volltextindex der Seiten und Anhänge. */
    public SourceDataStores fullText(LucenePageTextIndex texts) {
        return add("Volltextindex", texts::removeSource);
    }

    /**
     * Anreicherungen: Absätze, Begriffe und Akronyme mit Fundstellen, Fakten, Embeddings, Gliederung, Lese-Ansichten,
     * Bildanalysen und Aussagen-Anreicherung.
     */
    public SourceDataStores enrichments(LuceneEnrichmentStore enrichments) {
        return add("Analysen (Absätze, Begriffe, Fakten, Embeddings)", enrichments::removeSource);
    }

    /** Anhänge, Bilder und ihre Beschreibungen und Texte; Inhalte bestehender Exporte bleiben. */
    public SourceDataStores assets(FileSystemAssetStore assets, ExportArtifactWriter exports, Clock clock) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(exports, "exports");
        Objects.requireNonNull(clock, "clock");
        return add("Anhänge und Bilder", sourceId -> {
            int blobs = assets.removeSource(sourceId, exportImages(exports)::contains, clock.instant());
            LOG.log(System.Logger.Level.INFO, "Asset-Inhalte der Quelle {0} entfernt: {1}", sourceId, blobs);
        });
    }

    /** Begriffsbeziehungen der Quelle und ihre Belege, dazu der Ergebnis-Cache der KI-Akronymauflösung. */
    public SourceDataStores termRelations(TermRelations relations, FileResolutionCache... resolutions) {
        Objects.requireNonNull(relations, "relations");
        return add("Begriffsbeziehungen", sourceId -> {
            relations.removeSource(sourceId, ACTOR);
            for (FileResolutionCache cache : resolutions) {
                if (cache != null) {
                    cache.removeSource(sourceId);
                }
            }
        });
    }

    /** Manuelle und geprüfte Fakten der Seiten dieser Quelle (Prüfablage). */
    public SourceDataStores factEdits(FactEditStore edits, Clock clock) {
        Objects.requireNonNull(edits, "edits");
        Objects.requireNonNull(clock, "clock");
        return add("Geprüfte Fakten", sourceId -> {
            List<FactEdit> gone = edits.all().stream()
                    .filter(edit -> edit.fact().page().sourceId().equals(sourceId)).toList();
            if (gone.isEmpty()) {
                return;
            }
            var now = clock.instant();
            edits.write(List.of(), gone.stream().map(FactEdit::id).toList(), gone.stream()
                    .map(edit -> new FactEditStore.AuditEntry(edit.id(), edit.fact().page(), ACTOR, ACTOR, now,
                            edit.fact().review(), edit.revision())).toList());
        });
    }

    /** Aussagenanalysen der Seiten dieser Quelle, also ihr Anteil am Knowledge Graph. */
    public SourceDataStores knowledgeGraph(KnowledgeGraphStore graph, Runnable forgetInputs) {
        Objects.requireNonNull(graph, "graph");
        return add("Aussagen (Knowledge Graph)", sourceId -> {
            for (PageStatementAnalysis analysis : graph.all()) {
                if (analysis.key().sourceId().equals(sourceId)) {
                    graph.remove(analysis.key());
                }
            }
            if (forgetInputs != null) {
                forgetInputs.run();
            }
        });
    }

    /** Zeitpunkte der Bestandsinventur je Space; danach zählt der nächste Abstand ab jetzt. */
    public SourceDataStores inventoryLog(FileInventoryLog log) {
        return log == null ? this : add("Inventurzeitpunkte", log::removeSource);
    }

    /** Eine weitere Ablage, etwa des einbettenden Programms. */
    public SourceDataStores add(String name, Consumer<String> remove) {
        stores.add(new SourceDataPurge.Store(name, remove));
        return this;
    }

    public List<SourceDataPurge.Store> build() {
        return List.copyOf(stores);
    }

    /** Inhalte, auf die bestehende Exporte verweisen (Bilder und gezeichnete Diagramme). */
    static Set<String> exportImages(ExportArtifactWriter exports) {
        Set<String> referenced = new HashSet<>();
        for (String exportId : exports.published()) {
            try {
                exports.assets(exportId).ifPresent(manifest -> {
                    for (ExportAssetManifest.Entry entry : manifest.assets()) {
                        if (entry.sha256() != null) {
                            referenced.add(entry.sha256());
                        }
                        if (entry.rendition() != null && entry.rendition().drawn()) {
                            referenced.add(entry.rendition().sha256());
                        }
                    }
                });
            } catch (RuntimeException e) {
                // Ein unlesbares Manifest darf das Entfernen nicht verhindern; seine Bilder fehlen dann ggf. später.
                LOG.log(System.Logger.Level.WARNING, "Bildmanifest eines Exports nicht lesbar ({0})",
                        e.getClass().getName());
            }
        }
        return referenced;
    }
}
