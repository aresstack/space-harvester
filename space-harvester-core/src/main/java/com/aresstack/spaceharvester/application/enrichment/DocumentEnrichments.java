package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.DocumentInventory;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    public static final String PHASE = "Anreichern: Anhänge";

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
    /** Anhänge für den Bestand ({@link #catchUp}); ohne sie holt der Lauf nichts nach. */
    private volatile DocumentInventory assets;

    /**
     * @param jobs    Auftragsverwaltung oder {@code null}, dann im aufrufenden Thread
     * @param enabled ob Analysen je Dokument entstehen ({@code space-harvester.enrichment.documents.enabled})
     */
    public DocumentEnrichments(Enrichments enrichments, Jobs jobs, BooleanSupplier enabled) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.jobs = jobs;
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /** Schließt die Anhänge an, aus deren Ablage {@link #catchUp} den Bestand nachholt. */
    public void assets(DocumentInventory assets) {
        this.assets = assets;
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
                if (!enabled.getAsBoolean()) {
                    // Inzwischen abgeschaltet: nichts Neues; der Lauf nach dem Abgleich holt es nach dem Einschalten
                    // aus dem Zustand nach.
                    continue;
                }
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

    /**
     * Holt den Bestand nach (ADR 0026, AP9a): für die abgelegten Dokument-Anhänge dieser Seiten, die im Volltextindex
     * stehen, jedes Dokument mit lesbarer Extraktion, dessen Analysestand fehlt oder nicht aktuell ist. Aktuell heißt:
     * Stände aller zuständigen Prozessoren in ihrer Version, gebildet für diese Extraktion; das prüft der Lauf am
     * Manifest, ohne einen Abschnitt zu lesen. Läuft im Auftrag der Anreicherung nach einem Abgleich.
     */
    public void catchUp(String sourceId, String spaceKey, Collection<String> pageIds, JobContext context) {
        DocumentInventory source = assets;
        if (source == null || !enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        Optional<DocumentExtractions> structured = source.documentExtractions();
        if (structured.isEmpty()) {
            return;
        }
        List<Asset> documents = source.indexedDocuments(sourceId, pageIds);
        if (documents.isEmpty()) {
            return;
        }
        if (context != null) {
            context.phase(COMPONENT, PHASE);
        }
        try {
            for (Asset asset : documents) {
                if (context != null) {
                    context.checkpoint();
                }
                PageKey page = new PageKey(sourceId, asset.pageId());
                Pending item = new Pending(DocumentKey.attachment(page, asset.attachmentId()), spaceKey,
                        asset.sha256(), structured.get());
                int removed = removals(item);
                try {
                    process(item, context);
                } catch (JobCancelledException | JobFailedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Analysen eines Anhangs in {0} übersprungen ({1})",
                            sourceId, e.getClass().getName());
                } finally {
                    forgetIfRemoved(item, removed);
                }
            }
        } finally {
            enrichments.flush();
        }
    }

    /**
     * Rechnet die Dokumente eines Anhangs, deren Analysestand fehlt oder nicht aktuell ist. Erster Durchgang nur, wenn
     * das Manifest nicht schon genügt: Hash des Lesetextes je Dokument. Zweiter Durchgang nur für Dokumente mit
     * anderem Hash oder fehlenden Ständen. Gleicher Hash aus einer neuen Extraktion trägt nur die Extraktion nach.
     */
    private void process(Pending item, JobContext context) {
        Optional<StoredExtraction> extraction = item.extractions().readable(item.sha256());
        if (extraction.isEmpty()) {
            // Ohne lesbare Extraktion gilt der alte Stand weiter.
            return;
        }
        DocumentKey attachment = item.attachment();
        String basis = basis(extraction.get());
        Map<String, String> versions = enrichments.documentProcessorVersions(attachment.page());
        Map<DocumentKey, List<Enrichment>> stored = new HashMap<>();
        for (Enrichment enrichment : enrichments.store().findDocuments(attachment.page())) {
            if (enrichment.document().attachmentId().equals(attachment.attachmentId())) {
                stored.computeIfAbsent(enrichment.document(), key -> new ArrayList<>()).add(enrichment);
            }
        }
        List<DocumentKey> expected = item.extractions().documents(extraction.get()).stream()
                .map(part -> part.map(entry -> attachment.child(entry.id())).orElse(attachment)).toList();
        // Die Extraktion steht erst am Stand, wenn das Dokument vollständig durchlief; ein abgebrochener Lauf genügt
        // dieser Abkürzung also nie.
        if (!expected.isEmpty() && expected.stream().allMatch(document -> current(stored.get(document), versions,
                basis))) {
            return;
        }
        // Erster Durchgang: Hash des Lesetextes je Dokument und die Abschnitte mit Text.
        Map<DocumentKey, MessageDigest> digests = new LinkedHashMap<>();
        Map<DocumentKey, Set<Integer>> seen = new HashMap<>();
        forEachSection(item, extraction.get(), context, (document, index, text) -> {
            digests.computeIfAbsent(document, key -> sha256()).update((text.inputSha256() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            seen.computeIfAbsent(document, key -> new HashSet<>()).add(index);
        });
        Map<DocumentKey, String> hashes = new HashMap<>();
        digests.forEach((document, digest) -> hashes.put(document, "sha256:"
                + HexFormat.of().formatHex(digest.digest())));
        Set<DocumentKey> work = new HashSet<>();
        for (Map.Entry<DocumentKey, String> hash : hashes.entrySet()) {
            if (!covered(stored.get(hash.getKey()), versions, hash.getValue(), seen.get(hash.getKey()))) {
                work.add(hash.getKey());
            }
        }
        // Zweiter Durchgang: nur die Dokumente mit anderem Text oder fehlenden Ständen.
        if (!work.isEmpty()) {
            forEachSection(item, extraction.get(), context, (document, index, text) -> {
                if (!work.contains(document)) {
                    return;
                }
                if (context != null) {
                    context.discovered(1);
                }
                // Ohne Extraktion: die trägt erst der Abschluss unten nach, wenn alle Abschnitte durchliefen.
                Enrichments.Run run = enrichments.refreshDocument(document, index, hashes.get(document), null, text,
                        stored.getOrDefault(document, List.of()), null);
                if (context != null) {
                    context.written(run.computed() - run.failed());
                    context.failed(run.failed());
                    context.processed(1);
                }
            });
        }
        // Alle Abschnitte durchliefen: Hash und Extraktion am Stand nachtragen, ohne zu rechnen; Weggefallenes fällt
        // weg. Ein Abbruch kommt nicht hierher, ein halb gerechnetes Dokument trägt also nie die Extraktion.
        Set<DocumentKey> documents = new LinkedHashSet<>(seen.keySet());
        documents.addAll(stored.keySet());
        for (DocumentKey document : documents) {
            Set<Integer> current = seen.getOrDefault(document, Set.of());
            String hash = hashes.get(document);
            Set<Integer> sections = new HashSet<>();
            for (Enrichment result : enrichments.store().find(document)) {
                sections.add(result.section());
                if (!current.contains(result.section())) {
                    continue;
                }
                if (!Objects.equals(result.revision(), hash) || !basis.equals(result.extraction())) {
                    enrichments.store().save(result.withBasis(hash, basis));
                }
            }
            sections.stream().filter(section -> !current.contains(section))
                    .forEach(section -> enrichments.store().removeSection(document, section));
        }
        enrichments.flush();
    }

    /**
     * Ob der Analysestand eines Dokuments zu dieser Extraktion ({@code basis}) gehört: je zuständigem Prozessor Stände in
     * seiner Version, alle mit dieser Extraktion.
     */
    private static boolean current(List<Enrichment> stored, Map<String, String> versions, String basis) {
        if (stored == null || stored.isEmpty()) {
            return versions.isEmpty();
        }
        for (Map.Entry<String, String> processor : versions.entrySet()) {
            List<Enrichment> results = stored.stream()
                    .filter(result -> result.processorId().equals(processor.getKey())).toList();
            if (results.isEmpty() || results.stream().anyMatch(result -> !result.processorVersion()
                    .equals(processor.getValue()) || !basis.equals(result.extraction()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ob jeder Abschnitt mit Text ({@code sections}) je zuständigem Prozessor einen Stand in seiner Version für diesen
     * Hash des Lesetextes hat; fehlt einer, etwa nach einem Abbruch mitten im Dokument, wird das Dokument gerechnet.
     */
    private static boolean covered(List<Enrichment> stored, Map<String, String> versions, String hash,
                                   Set<Integer> sections) {
        for (Map.Entry<String, String> processor : versions.entrySet()) {
            Set<Integer> done = new HashSet<>();
            if (stored != null) {
                for (Enrichment result : stored) {
                    if (result.processorId().equals(processor.getKey())
                            && result.processorVersion().equals(processor.getValue())
                            && hash.equals(result.revision())) {
                        done.add(result.section());
                    }
                }
            }
            if (!done.containsAll(sections)) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface SectionVisitor {
        void visit(DocumentKey document, int index, PageText text);
    }

    /** Die Abschnitte mit Text in Reihenfolge, einzeln gelesen. */
    private static void forEachSection(Pending item, StoredExtraction extraction, JobContext context,
                                       SectionVisitor visitor) {
        DocumentKey attachment = item.attachment();
        try (Stream<DocumentExtractions.DocumentSection> sections =
                     item.extractions().documentSections(extraction)) {
            Iterator<DocumentExtractions.DocumentSection> found = sections.iterator();
            while (found.hasNext()) {
                if (context != null) {
                    context.checkpoint();
                }
                DocumentExtractions.DocumentSection section = found.next();
                PageText text = DocumentSectionText.project(section.section());
                if (!text.text().isBlank()) {
                    visitor.visit(section.part().map(part -> attachment.child(part.id())).orElse(attachment),
                            section.section().index(), text);
                }
            }
        }
    }

    /**
     * Undurchsichtige Kennung einer abgelegten Extraktion; nur verglichen, nie zerlegt. Mit Ablagezeitpunkt und
     * Zählern, denn ein erneutes Lesen mit weiteren Grenzen ersetzt die Abschnitte unter demselben Schlüssel.
     */
    private static String basis(StoredExtraction extraction) {
        ExtractionKey key = extraction.key();
        return key.sha256() + "|" + key.extractor() + "|" + key.version() + "|" + key.model() + "|"
                + extraction.createdAt() + "|" + extraction.sections() + "|" + extraction.blocks() + "|"
                + extraction.chars();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String partition(String sourceId, String spaceKey) {
        return sourceId + "\0" + (spaceKey == null ? "" : spaceKey);
    }
}
