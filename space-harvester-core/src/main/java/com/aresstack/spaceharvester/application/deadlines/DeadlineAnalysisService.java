package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import com.aresstack.spaceharvester.application.extraction.AnalysisNote;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentContent;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.FieldMerge;
import com.aresstack.spaceharvester.application.extraction.InMemoryDocumentAnalyses;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Die Dokumentanalyse der Fristen-Fachlogik (ADR 0035, Abschnitte 2–4) hinter {@link DocumentAnalysisProvider}: liest
 * den Dokumentstand über {@link DocumentContent}, den Text über die vorhandene Extraktion ({@link DocumentText}) und
 * führt die drei Stufen als ein Auftrag {@link JobType#DOCUMENT_ANALYSIS} aus:
 * <ol>
 *   <li>Regex ({@link IdentifierExtractor}); das Ergebnis ist sofort sichtbar,</li>
 *   <li>KI ({@link AiFactExtractor}), nur wenn das Dokument freigegeben ist ({@code AiRelease.mayProcess(origin, id)},
 *       unmittelbar vor dem Senden geprüft) und ein Modell eingestellt ist, sonst Hinweis
 *       {@link AnalysisNote.Code#AI_NOT_RELEASED} bzw. {@link AnalysisNote.Code#AI_UNAVAILABLE},</li>
 *   <li>Validierung ({@link ExtractionValidator}) der KI-Angaben.</li>
 * </ol>
 * Ergebnisse liegen nur im Speicher ({@link InMemoryDocumentAnalyses}) und gehen per Beobachter hinaus (ADR 0027).
 * Ein Fehler der KI macht das Ergebnis nur teilweise; scheitert das Lesen, bleibt das bisherige Ergebnis mit Grund.
 * Hier werden keine Regeln angewandt.
 */
public final class DeadlineAnalysisService implements DocumentAnalysisProvider {

    /** Angabe im Auftrag: Kennung des Dokuments. */
    public static final String DOCUMENT_PARAMETER = "document.id";
    /** Angabe im Auftrag: Version (ETag) des Dokuments. */
    public static final String VERSION_PARAMETER = "document.version";
    /** Angabe im Auftrag: Titel, nur zur Anzeige in der Auftragsliste. */
    public static final String TITLE_PARAMETER = "document.title";

    static final String AFTER_RESTART = "Nach dem Neustart nicht fortgesetzt: Analyseergebnisse liegen nur im Speicher";
    private static final System.Logger LOG = System.getLogger(DeadlineAnalysisService.class.getName());

    private final Jobs jobs;
    private final String owner;
    private final DocumentText texts;
    private final ExtractionStage regex;
    private final ExtractionStage ai;
    private final ExtractionStage validator;
    private final BiPredicate<String, String> mayProcess;
    private final InMemoryDocumentAnalyses results = new InMemoryDocumentAnalyses();
    private final Set<DocumentRef> running = ConcurrentHashMap.newKeySet();
    private volatile DocumentContent content = DocumentContent.NONE;

    /**
     * @param jobs       das Auftragssystem
     * @param owner      Auftraggeber der Analyseaufträge
     * @param texts      die vorhandene Textextraktion
     * @param regex      Stufe 1, produktiv {@link IdentifierExtractor}
     * @param ai         Stufe 2, produktiv {@link AiFactExtractor} mit der Modellzeile „Fristen“
     * @param validator  Stufe 3, produktiv {@link ExtractionValidator}
     * @param mayProcess KI-Freigabe je (Ablage, Kennung), produktiv {@code AiRelease::mayProcess}
     */
    public DeadlineAnalysisService(Jobs jobs, String owner, DocumentText texts, ExtractionStage regex,
                                   ExtractionStage ai, ExtractionStage validator,
                                   BiPredicate<String, String> mayProcess) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.regex = Objects.requireNonNull(regex, "regex");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.mayProcess = Objects.requireNonNull(mayProcess, "mayProcess");
        for (JobStatus left : jobs.listAll(JobType.DOCUMENT_ANALYSIS)) {
            try {
                jobs.retire(left.id(), left.owner(), AFTER_RESTART);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Analyse nach dem Neustart nicht abgeschlossen ({0})",
                        e.getClass().getName());
            }
        }
    }

    /** Schließt die Ablage an, aus der Dokumente gelesen werden (die App: E-Akte); {@code null} trennt sie. */
    public void content(DocumentContent content) {
        this.content = content == null ? DocumentContent.NONE : content;
    }

    @Override
    public DocumentAnalysis result(DocumentRef document) {
        return results.result(document);
    }

    @Override
    public boolean canAnalyze(DocumentRef document) {
        return content.supports(document);
    }

    @Override
    public Subscription observe(Consumer<DocumentRef> changed) {
        return results.observe(changed);
    }

    @Override
    public void startAnalysis(DocumentRef document) {
        Objects.requireNonNull(document, "document");
        DocumentContent source = content;
        if (!source.supports(document)) {
            throw new UnsupportedOperationException("Für „" + document.origin() + "“ ist keine Ablage angeschlossen");
        }
        if (!running.add(document)) {
            return; // läuft schon; das Ergebnis kommt über den Beobachter
        }
        try {
            // Ein neuer Lauf ersetzt das bisherige Ergebnis ganz, auch Angaben, deren Freigabe inzwischen fehlt.
            results.forget(document);
            results.record(DocumentAnalysis.inProgress(document));
            jobs.submitInteractive(JobType.DOCUMENT_ANALYSIS, document.origin(), null, null, owner,
                    Map.of(DOCUMENT_PARAMETER, document.id(), VERSION_PARAMETER, document.version()),
                    JobTask.blocking(context -> {
                        try {
                            analyze(context, source, document);
                        } finally {
                            running.remove(document);
                        }
                    }));
        } catch (RuntimeException e) {
            running.remove(document);
            results.record(DocumentAnalysis.failed(document, "Analyse nicht angenommen: " + e.getMessage()));
            throw e;
        }
    }

    /** Der Auftrag. Blockiert. */
    void analyze(JobContext context, DocumentContent source, DocumentRef document) throws Exception {
        context.discovered(1);
        try {
            AnalysisInput input = read(context, source, document);
            context.checkpoint();
            DocumentAnalysis facts = regex.extract(input, DocumentAnalysis.notAnalyzed(document));
            // Teilergebnis sofort: Die Regex-Stufe ist schnell, die KI kann dauern.
            results.record(withState(facts, AnalysisState.IN_PROGRESS, List.of()));
            context.checkpoint();
            List<AnalysisNote> notes = new ArrayList<>();
            if (!mayProcess.test(document.origin(), document.id())) {
                notes.add(new AnalysisNote(AnalysisNote.Code.AI_NOT_RELEASED,
                        "Das Dokument ist nicht für die KI freigegeben; nur die Mustersuche ist gelaufen.",
                        ai.processor()));
            } else if (!ai.available(input)) {
                notes.add(new AnalysisNote(AnalysisNote.Code.AI_UNAVAILABLE,
                        "Kein Modell für „Fristen“ eingestellt; nur die Mustersuche ist gelaufen.", ai.processor()));
            } else {
                context.phase("deadline-ai", "KI liest die Sachverhalte");
                DocumentAnalysis answered = ai.extract(input, DocumentAnalysis.notAnalyzed(document));
                context.checkpoint();
                if (answered.state() == AnalysisState.FAILED) {
                    notes.addAll(answered.notes());
                    notes.add(new AnalysisNote(AnalysisNote.Code.AI_UNAVAILABLE,
                            "Die KI lieferte nichts (" + answered.failure() + "); nur die Mustersuche ist gelaufen.",
                            ai.processor()));
                } else {
                    DocumentAnalysis checked = validator.extract(input, answered);
                    facts = FieldMerge.merge(facts, checked);
                }
            }
            results.record(withState(facts, AnalysisState.COMPLETED, notes));
            context.processed(1);
            context.written(1);
        } catch (CancellationException | JobCancelledException e) {
            results.record(DocumentAnalysis.failed(document, "Abgebrochen"));
            throw e;
        } catch (JobFailedException e) {
            results.record(DocumentAnalysis.failed(document, e.getMessage()));
            context.failed(1);
            throw e;
        } catch (RuntimeException e) {
            results.record(DocumentAnalysis.failed(document, "Analyse gescheitert (" + reason(e) + ")"));
            context.failed(1);
            throw new JobFailedException("ANALYSIS_FAILED", "Analyse gescheitert (" + reason(e) + ")", e);
        }
    }

    private AnalysisInput read(JobContext context, DocumentContent source, DocumentRef document) {
        context.phase("document", "Dokument lesen");
        DocumentContent.Content read;
        try {
            read = source.read(document);
        } catch (CancellationException | JobCancelledException e) {
            throw e;
        } catch (Exception e) {
            throw new JobFailedException("DOCUMENT_UNREADABLE", "Dokument nicht lesbar (" + reason(e) + ")", e);
        }
        context.checkpoint();
        context.phase("text", "Text lesen");
        String mediaType = read.mediaType().isBlank()
                ? texts.detect(read.title(), read.bytes()).orElse("") : read.mediaType();
        DocumentText.Extracted text;
        try {
            text = texts.extract(read.title(), mediaType, read.bytes());
        } catch (DocumentTextException e) {
            throw new JobFailedException("TEXT_" + e.reason(), "Kein Text lesbar (" + e.reason() + ")", e);
        }
        return new AnalysisInput(document, read.title(), mediaType, text.text(), text.truncated());
    }

    private static DocumentAnalysis withState(DocumentAnalysis analysis, AnalysisState state,
                                              List<AnalysisNote> extra) {
        List<AnalysisNote> notes = new ArrayList<>(analysis.notes());
        notes.addAll(extra);
        return new DocumentAnalysis(analysis.document(), state, analysis.sections(), "", false, notes,
                analysis.processors());
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
