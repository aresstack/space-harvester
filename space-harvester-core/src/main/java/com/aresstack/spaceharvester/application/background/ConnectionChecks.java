package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
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

    private final BackgroundJobs background;
    private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** Eine sichtbare Prüfung; {@link #finish} beendet ihren Teil. */
    public interface Check {

        /** @param failure der Grund, wenn die Prüfung scheiterte, sonst {@code null} */
        void finish(String failure);
    }

    public ConnectionChecks(BackgroundJobs background) {
        this.background = Objects.requireNonNull(background, "background");
        background.register(new BackgroundKind(KIND, this::work, () -> false));
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
                done.completeExceptionally(new JobFailedException(FAILED, "Verbindung fehlgeschlagen: " + failure));
            }
        });
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
            context.whenStopRequested(() -> done.complete(null));
            return done.whenComplete((ignored, failure) -> {
                context.processed(1);
                if (failure == null) {
                    context.written(1);
                } else {
                    context.failed(1);
                }
            });
        };
    }
}
