package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.background.services.AnalysisService;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * „Wissensgraph erzeugen“ als Ziel des Dienst-Planers (W5, Nachtrag D1 zu ADR 0025). Der Graph rechnet nichts Eigenes:
 * {@link KnowledgeGraph} und seine Sichten leiten alles beim Lesen aus den gespeicherten Aussagenanalysen ab. Der Teil
 * der Art {@value #KIND} ist deshalb ein kurzer Abschlussteil je Space: Er zählt nach den Vorstufen die Abdeckung
 * ({@link StatementAnalysisJobs#coverage}), hält sie als Ereignis {@value #COVERAGE} fest (gelb, wenn etwas fehlt)
 * und meldet dem Graph, dass sich Analysen geändert haben.
 *
 * <p>Eigentümer wie bei der Aussagenanalyse: Der erste Lauf rechnet mit den Rechten des Start-Aufrufers
 * ({@value #CALLER}); nach einem Neustart nur, wenn der wiederhergestellte Aufrufer der Eigentümer ({@value #OWNER})
 * ist, sonst hält der Teil gelb an ({@value #RESUME_BY_HAND}). Ein fremder Teil läuft nie mit den Rechten dessen
 * weiter, der ihn fortsetzt: Nur sein Eigentümer setzt ihn über {@link #resume(AccessContext, String)} fort.
 */
public class KnowledgeGraphJobs {

    /** Kennung der Hintergrundart und des Dienstes im Planer. */
    public static final String KIND = "knowledge-graph";
    /** Komponente in Diagnoseereignissen. */
    public static final String COMPONENT = "Wissensgraph";
    /** Code der gelben Angabe: nicht fortgesetzt, weil ein anderer Eigentümer startete; nur er setzt fort. */
    public static final String RESUME_BY_HAND = StatementAnalysisJobs.RESUME_BY_HAND;
    /** Code des Ereignisses mit der Abdeckung eines Space (Info, gelb wenn etwas fehlt); nur Zahlen. */
    public static final String COVERAGE = "GRAPH_COVERAGE";
    /** Angabe: Eigentümer ({@link AccessContext#jobOwner()}) des Aufrufers, der startete. */
    public static final String OWNER = StatementAnalysisJobs.OWNER;
    /** Angabe: Kennung des Start-Aufrufers in diesem Prozess; nach einem Neustart gilt {@value #OWNER}. */
    public static final String CALLER = StatementAnalysisJobs.CALLER;
    /** Angabe: der Analysator ({@link AnalyzerChoice#value()}), dessen Ablage zählt; beim Start festgelegt. */
    public static final String ANALYZER = StatementAnalysisJobs.ANALYZER;

    private final StatementAnalysisJobs statements;
    private final Runnable analysesChanged;
    private volatile AccessContext restored;
    private volatile BackgroundJobs background;
    /**
     * Aufrufer der jüngsten Starts (unter der Kennung {@value #CALLER}) und der Eigentümer, die einen Teil von Hand
     * fortsetzten (unter der Kennung des Teils), älteste zuerst verdrängt; danach und nach einem Neustart gilt {@link #restored}. */
    private final Map<String, AccessContext> callers = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AccessContext> eldest) {
                    return size() > 256;
                }
            });

    /**
     * @param statements      die Aussagenanalyse, nach deren Regel die Abdeckung zählt
     * @param analysesChanged meldet dem Graph geänderte Analysen ({@link KnowledgeGraph#analysesChanged()})
     */
    public KnowledgeGraphJobs(StatementAnalysisJobs statements, Runnable analysesChanged) {
        this.statements = Objects.requireNonNull(statements, "statements");
        this.analysesChanged = Objects.requireNonNull(analysesChanged, "analysesChanged");
    }

    /**
     * Meldet die Art {@value #KIND} an, nicht exklusiv und nach einem Neustart von selbst fortgesetzt; vor
     * {@link BackgroundJobs#resumeInterrupted()} aufrufen.
     *
     * @param restored der Aufrufer nach einem Neustart (in der Desktop-App der App-Benutzer {@code Harvester.OWNER})
     */
    public void background(BackgroundJobs jobs, AccessContext restored) {
        this.restored = Objects.requireNonNull(restored, "restored");
        Objects.requireNonNull(jobs, "jobs").register(new BackgroundKind(KIND, this::work));
        this.background = jobs;
    }

    /**
     * Setzt einen Teil der Art {@value #KIND} von Hand fort; der Weg der App für ▶. Mit den Rechten des Fortsetzenden
     * nur, wenn er der Eigentümer ({@value #OWNER}) ist; sonst bleibt alles wie ohne Aufrufer, ein fremder Teil hält
     * also wieder gelb an ({@value #RESUME_BY_HAND}).
     *
     * @throws IllegalStateException ohne angeschlossenen Hintergrund
     */
    public JobStatus resume(AccessContext caller, String jobId) {
        Objects.requireNonNull(caller, "caller");
        BackgroundJobs jobs = background;
        if (jobs == null) {
            throw new IllegalStateException("Kein Hintergrund angeschlossen");
        }
        jobs.part(Objects.requireNonNull(jobId, "jobId"))
                .filter(status -> KIND.equals(status.parameter(BackgroundJobs.KIND)))
                .filter(status -> caller.jobOwner().equals(status.parameter(OWNER)))
                .ifPresent(status -> callers.put(status.id(), caller));
        return jobs.resumeJob(jobId);
    }

    /**
     * Die Angaben der Teile, die der Dienst-Planer für diese Art anlegt ({@link AnalysisService#parameters()}):
     * Eigentümer, Kennung des Aufrufers und der eingestellte Analysator, derselbe, den die Aussagenanalyse im selben
     * Start festlegt. Legt den Aufrufer für den ersten Lauf ab; nur beim Start aufrufen, nie für eine Vorschau.
     */
    public Map<String, String> plannerParameters(AccessContext caller) {
        Objects.requireNonNull(caller, "caller");
        String token = java.util.UUID.randomUUID().toString();
        callers.put(token, caller);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(ANALYZER, statements.selected().value());
        parameters.put(OWNER, caller.jobOwner());
        parameters.put(CALLER, token);
        return parameters;
    }

    /** Die Arbeit eines Teils, allein aus seinen Angaben: Seiten, Analysator, Eigentümer. */
    private JobTask work(BackgroundPart part) {
        String token = part.parameter(CALLER);
        AccessContext started = token == null ? null : callers.get(token);
        // Von Hand fortgesetzt: nur der Eigentümer steht unter der Kennung des Teils (resume).
        AccessContext starter = started != null ? started : callers.get(part.jobId());
        AccessContext fallback = restored;
        List<String> listed = listed(part);
        AnalyzerChoice analyzer = AnalyzerChoice.parse(part.parameter(ANALYZER));
        return JobTask.blocking(context -> {
            // Nie mit fremden Rechten: ohne bekannten Aufrufer nur, wenn der wiederhergestellte der Eigentümer ist.
            if (starter == null && (fallback == null || !fallback.jobOwner().equals(part.parameter(OWNER)))) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, RESUME_BY_HAND,
                        part.automatic() ? "Nach dem Neustart nicht von selbst fortgesetzt, weil ein anderer Aufrufer "
                                + "ihn gestartet hat; nur er kann ihn fortsetzen"
                                : "Ein anderer Aufrufer hat ihn gestartet; nur er kann ihn fortsetzen"));
                context.pause(PauseReason.RESUME_BY_HAND);
                return;
            }
            AccessContext caller = starter != null ? starter : fallback;
            context.discovered(listed.size());
            context.checkpoint();
            StatementAnalysisJobs.Coverage coverage = statements.coverage(caller,
                    PageSet.of(Map.of(part.sourceId(), listed)), analyzer);
            context.record(new JobEvent.Draft(coverage.complete() ? JobEvent.Severity.INFO
                    : JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, COVERAGE, message(coverage),
                    null, null, null));
            context.processed(listed.size());
            analysesChanged.run();
        });
    }

    /** Die Abdeckung für Menschen, nur Zahlen. */
    static String message(StatementAnalysisJobs.Coverage coverage) {
        StringBuilder text = new StringBuilder();
        text.append(coverage.current()).append(" von ").append(coverage.pages())
                .append(coverage.pages() == 1 ? " Seite" : " Seiten").append(" mit aktueller Analyse");
        if (coverage.stale() > 0) {
            text.append(", ").append(coverage.stale()).append(" ohne aktuelle Analyse");
        }
        if (coverage.toLoad() > 0) {
            text.append(", ").append(coverage.toLoad()).append(" erst laden");
        }
        if (coverage.noText() > 0) {
            text.append(", ").append(coverage.noText()).append(" ohne Lesetext ausgelassen");
        }
        if (coverage.notReleased() > 0) {
            text.append(", ").append(coverage.notReleased()).append(" ohne KI-Freigabe ausgelassen");
        }
        return text.toString();
    }

    /** Die Seiten des Teils ({@link AnalysisService#PAGES}); leer ohne. */
    private static List<String> listed(BackgroundPart part) {
        String value = part.parameter(AnalysisService.PAGES);
        return value == null ? List.of()
                : Arrays.stream(value.split("\n")).filter(id -> !id.isBlank()).distinct().toList();
    }
}
