package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.run.ProgressListener;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.research4j.basket.BasketResearch;
import com.aresstack.research4j.basket.SourceKeeper;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobTask;

import java.util.Objects;

/**
 * „Korb füllen“ als Auftrag: Research4j füllt einen Favoritenordner über den Bestand. Läuft nur auf Klick, nie beim
 * Start, und lässt sich nach einem Neustart nicht fortsetzen (ein neuer Klick startet neu; was im Ordner liegt, bleibt
 * und wird nicht noch einmal gelesen). Zähler: Arbeitsschritte (Planen, Suchen, Lesen, Prüfen), erledigte, aufgenommene
 * Quellen, übersprungene. Abbrechen
 * wirkt am nächsten Schritt; der Ordner behält, was bis dahin gefunden wurde.
 */
public final class BasketResearchJob {

    private static final System.Logger LOG = System.getLogger(BasketResearchJob.class.getName());

    private BasketResearchJob() {
    }

    /**
     * @param folderName   Name des Ordners für die Auftragsliste
     * @param hitsPerQuery Treffer je Anfrage, eine Einstellung
     */
    public static JobTask task(MainModelChat chat, PageStock stock, FavoritesBasket basket, String folderName,
                               Fence fence, int hitsPerQuery, BasketResearch.Settings settings) {
        Objects.requireNonNull(fence, "fence");
        return JobTask.blocking(context -> {
            context.rootTitle(folderName);
            context.phase("Korb füllen", "Start");
            SourceKeeper counted = content -> keepCounted(basket, content, context);
            ProgressListener progress = progress(context);
            BasketResearch.Result result;
            try (AutoCloseable stop = context.whenStopRequested(chat::cancelInFlight)) {
                result = HostResearch.research(chat, stock, counted, hitsPerQuery, settings)
                        .run(fence, null, basket.basket(), () -> stopped(context), progress);
            }
            context.failed(result.skipped().size());
            // Nur Zahlen, nie Titel oder Text (Protokolle tragen keine Inhalte).
            long outside = result.profiles().stream().filter(p -> p.judged() && !p.fit().belongsInBasket()).count();
            long unjudged = result.profiles().stream().filter(p -> !p.judged()).count();
            LOG.log(System.Logger.Level.INFO, "Korb füllen: {0} Runden, Ende {1}, {2} Quellen im Korb, {3} außerhalb "
                            + "des Weidezauns, {4} ohne Urteil, {5} übersprungen, {6} Suchanfragen, {7} Modellaufrufe "
                            + "({8} gescheitert)", result.rounds(), result.stop(), result.basket().sources().size(),
                    outside, unjudged, result.skipped().size(), result.searches().size(), result.modelCalls(),
                    result.modelFailures());
            if (result.stop() == BasketResearch.Stop.ABGEBROCHEN) {
                context.checkpoint();
            }
        });
    }

    /** Fortschritt über alle Schritte jeder Runde ({@link StepProgress}): Planen, Suchen, Lesen, Prüfen. */
    static ProgressListener progress(JobContext context) {
        return new StepProgress(context, "Korb füllen", BasketResearchJob::phaseOf)::step;
    }

    static String phaseOf(String step) {
        return switch (step) {
            case BasketResearch.STEP_PLAN -> "Suchanfragen planen";
            case BasketResearch.STEP_SEARCH -> "Suchen";
            case BasketResearch.STEP_FETCH -> "Seiten lesen";
            case BasketResearch.STEP_JUDGE -> "Am Weidezaun prüfen";
            default -> step;
        };
    }

    private static SourceRef keepCounted(FavoritesBasket basket, FetchedContent content, JobContext context)
            throws SourceAccessException {
        SourceRef kept = basket.keep(content);
        context.written(1);
        return kept;
    }

    private static boolean stopped(JobContext context) {
        try {
            context.checkpoint();
            return false;
        } catch (JobCancelledException cancelled) {
            return true;
        }
    }
}
