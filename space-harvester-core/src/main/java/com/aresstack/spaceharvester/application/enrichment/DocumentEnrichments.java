package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.IndexedDocuments;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Analysen je Anhang und enthaltener Datei (ADR 0026, AP9a): Liegt ein Dokument-Anhang neu oder geändert im
 * Volltextindex, rechnen die Prozessoren mit {@link EnrichmentProcessor#documents()} je abgelegtem Abschnitt, Eingabe
 * {@link DocumentSectionText}. Gerechnet wird im selben Mechanismus wie für Seiten: als Auftrag
 * {@link JobType#ENRICHMENT} der Quelle und des Space, sichtbar, anhaltbar und abbrechbar wie dort, kein zweiter
 * Hintergrundweg. Der Auftrag nimmt alle bis dahin gemeldeten Anhänge seiner Partition mit; ein weiterer entsteht erst,
 * wenn er fertig ist.
 *
 * <ul>
 *   <li>Nur Dokumente und Teile mit Status {@code OK} oder {@code PARTIAL} ({@link DocumentExtractions#documentSections});
 *       ein Abschnitt ohne Text bekommt kein Ergebnis.</li>
 *   <li>Aktuelle Ergebnisse (gleiche Eingabe, gleiche Version) bleiben; ein neu gelesenes Dokument mit gleichem Text
 *       kostet nur das Lesen der Abschnitte.</li>
 *   <li>Erst wenn alle Abschnitte eines Anhangs durchgelaufen sind, fallen Ergebnisse von Teilen und Abschnitten weg,
 *       die es nicht mehr gibt (Schlüsselform Punkt 5): Der alte Stand gilt bis zum Erfolg. Nennt die vollständige
 *       Anhangsliste einer Seite einen Anhang nicht mehr, fallen seine Ergebnisse samt Teilen sofort weg.</li>
 *   <li>Abgeschaltet ({@code enabled} liefert {@code false}) entsteht nichts Neues; vorhandene Ergebnisse bleiben
 *       lesbar.</li>
 *   <li>Nach Abbruch oder Anhalten bleiben die übrigen Anhänge gemeldet und laufen mit dem nächsten Auftrag der
 *       Partition.</li>
 * </ul>
 */
public final class DocumentEnrichments implements IndexedDocuments {

    private static final System.Logger LOG = System.getLogger(DocumentEnrichments.class.getName());
    private static final String COMPONENT = "Enrichments";
    static final String PHASE = "Anreichern: Anhänge";

    private final Enrichments enrichments;
    private final Jobs jobs;
    private final BooleanSupplier enabled;
    /** Gemeldete Anhänge nach {@link DocumentKey#value()}, in Meldereihenfolge; bewacht von {@code this}. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /**
     * Partitionen ({@code Quelle \0 Space}) mit dem zuletzt eingeplanten Auftrag; bewacht von {@code this}. Ob er noch
     * wartet oder läuft, fragt {@link #alive} die Auftragsverwaltung: Ein vor dem Start abgebrochener Auftrag läuft nie
     * und hält so keine spätere Meldung auf.
     */
    private final Map<String, String> scheduled = new HashMap<>();
    /** Wie oft die Ergebnisse eines Anhangs entfernt wurden, nach {@link DocumentKey#value()}; bewacht von {@code this}. */
    private final Map<String, Integer> removals = new HashMap<>();

    /**
     * @param jobs    Auftragsverwaltung oder {@code null}, dann im aufrufenden Thread
     * @param enabled ob Analysen je Dokument entstehen ({@code space-harvester.enrichment.documents.enabled})
     */
    public DocumentEnrichments(Enrichments enrichments, Jobs jobs, BooleanSupplier enabled) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.jobs = jobs;
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /** Ein gemeldeter Anhang. */
    private record Pending(DocumentKey attachment, String spaceKey, String sha256, DocumentExtractions extractions) {
    }

    @Override
    public void indexed(PageAttachmentList list, Asset asset, DocumentExtractions extractions) {
        if (!enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        Pending item = new Pending(DocumentKey.attachment(page, asset.attachmentId()), list.spaceKey(),
                asset.sha256(), Objects.requireNonNull(extractions, "extractions"));
        String partition = partition(list.sourceId(), list.spaceKey());
        String job;
        synchronized (this) {
            // Ein erneut gemeldeter Anhang rückt nach hinten und rechnet mit seinem neuesten Stand.
            pending.remove(item.attachment().value());
            pending.put(item.attachment().value(), item);
            job = scheduled.get(partition);
        }
        if (jobs == null) {
            drain(list.sourceId(), list.spaceKey(), null);
            return;
        }
        if (job != null && alive(job)) {
            return;
        }
        try {
            String id = jobs.submit(JobType.ENRICHMENT, list.sourceId(), list.spaceKey(), null,
                    JobTask.blocking(context -> drain(list.sourceId(), list.spaceKey(), context))).id();
            synchronized (this) {
                scheduled.put(partition, id);
            }
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Analysen der Anhänge in {0} nicht eingeplant ({1}); die nächste "
                    + "Meldung holt sie nach", list.sourceId(), e.getClass().getSimpleName());
        }
    }

    /** Ob der Auftrag noch wartet oder läuft; angehalten, beendet oder unbekannt zählt nicht. */
    private boolean alive(String jobId) {
        try {
            return !jobs.status(jobId).state().terminal();
        } catch (UnknownJobException e) {
            return false;
        }
    }

    @Override
    public void retained(PageKey page, Set<String> attachmentIds) {
        Set<String> gone = new HashSet<>();
        synchronized (this) {
            // Gemeldete, aber entfernte Anhänge rechnen nicht mehr; ein laufender bemerkt es über removals.
            Iterator<Pending> iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                DocumentKey attachment = iterator.next().attachment();
                if (attachment.page().equals(page) && !attachmentIds.contains(attachment.attachmentId().orElse(""))) {
                    iterator.remove();
                    removals.merge(attachment.value(), 1, Integer::sum);
                }
            }
        }
        for (Enrichment enrichment : enrichments.store().findDocuments(page)) {
            enrichment.document().attachmentId().filter(id -> !attachmentIds.contains(id)).ifPresent(gone::add);
        }
        for (String attachmentId : gone) {
            DocumentKey attachment = DocumentKey.attachment(page, attachmentId);
            synchronized (this) {
                removals.merge(attachment.value(), 1, Integer::sum);
            }
            enrichments.store().removeDocument(attachment);
        }
        if (!gone.isEmpty()) {
            enrichments.flush();
        }
    }

    /** Rechnet die gemeldeten Anhänge der Partition, bis keiner mehr wartet. */
    private void drain(String sourceId, String spaceKey, JobContext context) {
        String partition = partition(sourceId, spaceKey);
        if (context != null) {
            context.phase(COMPONENT, PHASE);
        }
        boolean finished = false;
        try {
            Pending item;
            while ((item = take(sourceId, spaceKey, partition)) != null) {
                int removed = removals(item);
                try {
                    process(item, context);
                } catch (JobCancelledException | JobFailedException e) {
                    // Abgebrochen, angehalten oder Zeit abgelaufen: Der Anhang bleibt gemeldet, wenn er nicht neu
                    // gemeldet wurde.
                    requeue(item);
                    throw e;
                } catch (RuntimeException e) {
                    // Ein Anhang, der sich nicht lesen oder ablegen lässt, hält die übrigen nicht auf.
                    LOG.log(System.Logger.Level.WARNING, "Analysen eines Anhangs in {0} übersprungen ({1})",
                            sourceId, e.getClass().getName());
                } finally {
                    forgetIfRemoved(item, removed);
                }
            }
            finished = true;
        } finally {
            enrichments.flush();
            if (!finished) {
                synchronized (this) {
                    scheduled.remove(partition);
                }
            }
        }
    }

    /** Der nächste gemeldete Anhang der Partition; ohne einen endet die Einplanung im selben Schritt. */
    private synchronized Pending take(String sourceId, String spaceKey, String partition) {
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            if (item.attachment().page().sourceId().equals(sourceId)
                    && Objects.equals(item.spaceKey(), spaceKey)) {
                iterator.remove();
                return item;
            }
        }
        scheduled.remove(partition);
        return null;
    }

    private synchronized void requeue(Pending item) {
        pending.putIfAbsent(item.attachment().value(), item);
    }

    private synchronized int removals(Pending item) {
        return removals.getOrDefault(item.attachment().value(), 0);
    }

    /** Wurde der Anhang entfernt, während er rechnete, fällt auch das eben Gespeicherte wieder weg. */
    private void forgetIfRemoved(Pending item, int before) {
        if (removals(item) != before) {
            enrichments.store().removeDocument(item.attachment());
        }
    }

    private void process(Pending item, JobContext context) {
        Optional<StoredExtraction> extraction = item.extractions().readable(item.sha256());
        if (extraction.isEmpty()) {
            // Ohne lesbare Extraktion gilt der alte Stand weiter.
            return;
        }
        DocumentKey attachment = item.attachment();
        Map<DocumentKey, List<Enrichment>> stored = new HashMap<>();
        for (Enrichment enrichment : enrichments.store().findDocuments(attachment.page())) {
            if (enrichment.document().attachmentId().equals(attachment.attachmentId())) {
                stored.computeIfAbsent(enrichment.document(), key -> new java.util.ArrayList<>()).add(enrichment);
            }
        }
        Map<DocumentKey, Set<Integer>> seen = new HashMap<>();
        try (Stream<DocumentExtractions.DocumentSection> sections =
                     item.extractions().documentSections(extraction.get())) {
            Iterator<DocumentExtractions.DocumentSection> found = sections.iterator();
            while (found.hasNext()) {
                if (context != null) {
                    context.checkpoint();
                }
                DocumentExtractions.DocumentSection section = found.next();
                DocumentKey document = section.part().map(part -> attachment.child(part.id())).orElse(attachment);
                PageText text = DocumentSectionText.project(section.section());
                if (text.text().isBlank()) {
                    continue;
                }
                int index = section.section().index();
                seen.computeIfAbsent(document, key -> new HashSet<>()).add(index);
                String revision = section.part().flatMap(EmbeddedEntry::sha256).map(DocumentEnrichments::prefixed)
                        .orElse(item.sha256());
                if (context != null) {
                    context.discovered(1);
                }
                Enrichments.Run run = enrichments.refreshDocument(document, index, revision, text,
                        stored.getOrDefault(document, List.of()), null);
                if (context != null) {
                    context.written(run.computed() - run.failed());
                    context.failed(run.failed());
                    context.processed(1);
                }
            }
        }
        // Alle Abschnitte durchgelaufen: was es nicht mehr gibt, fällt weg.
        stored.forEach((document, results) -> {
            Set<Integer> current = seen.getOrDefault(document, Set.of());
            results.stream().map(Enrichment::section).distinct().filter(section -> !current.contains(section))
                    .forEach(section -> enrichments.store().removeSection(document, section));
        });
        enrichments.flush();
    }

    /** Inhalts-Hash in der Form der Anhänge ({@code sha256:<hex>}); Teile führen ihn ohne Präfix. */
    private static String prefixed(String sha256) {
        return sha256.startsWith("sha256:") ? sha256 : "sha256:" + sha256;
    }

    private static String partition(String sourceId, String spaceKey) {
        return sourceId + "\0" + (spaceKey == null ? "" : spaceKey);
    }
}
