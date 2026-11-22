package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * „Verbindung prüfen“ als Hintergrundteil (Fundstelle 17, Abnahme des Architekten): Die Prüfung einer Quelle läuft
 * beim Aufrufer (etwa die App nach dem Neuladen, Spaces live abfragen). Dauert sie länger als eine Sekunde, macht der
 * Aufrufer sie hier sichtbar ({@link #begin}); der Teil rechnet nichts, er endet mit der Prüfung. Endet die Prüfung
 * vorher, entsteht kein Teil. Ein Teil von vor einem Neustart hat keine Prüfung mehr und endet sofort.
 */
public final class ConnectionChecks {

    /** Kennung der Art. */
    public static final String KIND = "connection";
    /** Titel der Gruppe. */
    public static final String TITLE = "Verbindung prüfen";
    /** Fehlercode, wenn die Prüfung scheiterte. */
    public static final String FAILED = "connection-failed";
    /** Info beim Abschließen eines Teils von vor dem Neustart. */
    static final String AFTER_RESTART = "Prüfung von vor dem Neustart";
    /**
     * Beendet den Teil auf einem eigenen Thread: {@link Check#finish} kommt etwa vom EDT, und das Ende eines Auftrags
     * schreibt in die Auftragsablage.
     */
    private static final java.util.concurrent.Executor FINISHER = task -> Thread.ofVirtual()
            .name("verbindung-pruefen").start(task);

    private final BackgroundJobs background;
    private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** Eine sichtbare Prüfung; {@link #finish} beendet ihren Teil. */
    public interface Check {

        /** @param failure der Grundcode ({@link #reasonCode}), wenn die Prüfung scheiterte, sonst {@code null} */
        void finish(String failure);
    }

    /**
     * Der Grund einer gescheiterten Prüfung als Code, wie bei den übrigen Quellenfehlern: der
     * {@link SourceAccessException.Reason} aus der Ursachenkette, sonst die Klasse; nie die Nachricht, die Hostnamen
     * oder URL-Teile enthalten kann.
     */
    public static String reasonCode(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SourceAccessException source) {
                return source.reason().name();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return failure.getClass().getSimpleName();
    }

    public ConnectionChecks(BackgroundJobs background) {
        this.background = Objects.requireNonNull(background, "background");
        // Eigene Spur (Abnahme #1091): Der Teil rechnet nichts und belegt keinen Platz, sonst erschiene er bei vollen
        // Plätzen erst nach der Prüfung.
        background.register(new BackgroundKind(KIND, this::work, () -> false, false, true));
        // Teile von vor dem Neustart haben keine Prüfung mehr: abschließen statt als gescheitert stehen lassen.
        background.retireResumable(KIND, AFTER_RESTART);
    }

    /**
     * Zeigt eine laufende Prüfung der Quelle als Teil „{@value #TITLE}“; leer bei nur einem Platz unter „Vorbereitung“
     * (bisheriger Weg) oder wenn der Auftrag nicht angelegt werden kann.
     */
    public Optional<Check> begin(String sourceId) {
        if (!background.canRun()) {
            return Optional.empty();
        }
        String groupId = BackgroundJobs.newGroupId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        pending.put(groupId, done);
        try {
            background.start(groupId, KIND, TITLE, List.of(new BackgroundPart.Draft(sourceId, null, Map.of())));
        } catch (RuntimeException e) {
            pending.remove(groupId);
            return Optional.empty();
        }
        return Optional.of(failure -> {
            // Der Teil nimmt die Prüfung beim Start aus der Karte; endet sie vorher, sieht er sie schon beendet.
            if (failure == null) {
                done.complete(null);
            } else {
                done.completeExceptionally(new JobFailedException(FAILED, "Verbindung fehlgeschlagen (" + failure + ")"));
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private JobTask work(BackgroundPart part) {
        return context -> {
            CompletableFuture<Void> done = pending.remove(part.groupId());
            context.discovered(1);
            if (done == null) {
                // Von vor einem Neustart oder schon beendet: keine Prüfung mehr zu zeigen.
                context.processed(1);
                return CompletableFuture.completedFuture(null);
            }
            // Abbrechen oder Anhalten beendet nur die Anzeige, die Prüfung läuft zu Ende; der Teil endet dann als
            // abgebrochen bzw. angehalten, nicht grün (wie FillJobs).
            context.whenStopRequested(() -> {
                try {
                    context.checkpoint();
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
            return done.whenCompleteAsync((ignored, failure) -> {
                if (failure == null) {
                    context.processed(1);
                    context.written(1);
                } else if (unwrap(failure) instanceof JobFailedException) {
                    context.processed(1);
                    context.failed(1);
                }
            }, FINISHER);
        };
    }
}
