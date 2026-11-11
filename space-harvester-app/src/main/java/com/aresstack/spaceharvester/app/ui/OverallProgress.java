package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.event.JobProgressEvent;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Gesamtstand mehrerer Aufträge (Angelo 11.11.2026): der Balken der Statuszeile über alle Aufträge und der Balken
 * hinter einer laufenden Export-Gruppe in der Liste.
 *
 * <p>Jeder Auftrag zählt mit seinem Anteil an erledigten Elementen, gewichtet mit seiner geschätzten Gesamtdauer, damit
 * ein großer, langsamer Auftrag mehr wiegt als ein kleiner, schneller; läuft nur einer, steht der Balken bei seinem
 * eigenen Anteil. Die Gesamtdauer ist das Erledigte mal den Sekunden je Element bisher plus das Offene mal dem Tempo der
 * letzten Minuten ({@link #WINDOW}); dasselbe Offene ergibt die Restzeit. Das jüngste Tempo zählt, weil sich das Tempo
 * eines Auftrags unterwegs ändert, etwa wenn Bilder- und Anhängeaufträge die Verbindungen neu unter sich aufteilen
 * (Strang Q, PR #659) oder ein Export vom Abrufen zum schnellen Exportieren wechselt.
 *
 * <p>Die Sekunden je Element bisher beginnen mit einem Startwert und folgen dem eigenen Tempo des Auftrags, je mehr er
 * erledigt hat, desto mehr ({@link #CONFIDENCE}). Bilder und Anhänge beginnen mit dem gemessenen Durchschnitt je Datei
 * aus dem Core und messen ihr Tempo erst ab dem Laden, weil Vorbereiten und Warten auf einen Platz nichts laden;
 * mehrere Verbindungen je Auftrag gehen so von selbst ein. Alle anderen messen ab ihrem Start.
 *
 * <p>Ist die Größe eines Auftrags noch unbekannt (Inventur, wartend, Bilder in der Vorbereitung), zählt er mit der
 * zuletzt gesehenen Größe desselben Auftrags, sonst mit dem Mittel der bekannten Aufträge seiner Art, sonst mit einem
 * Startwert. Stellt sich die echte Größe heraus, springt der Balken einmal entsprechend.
 *
 * <p>Der Balken der Statuszeile umfasst einen Durchgang: alle Aufträge, die seit dem letzten Ende offen waren
 * (wartend, laufend, angehalten). Fertige zählen darin voll, bis keiner mehr offen ist; dann zeigt die Statuszeile das
 * Ende, und der nächste Auftrag beginnt einen neuen Durchgang bei 0 %.
 */
final class OverallProgress {

    /** Exportieren aus dem Cache ist lokal und schnell: ein Zehntel einer abgerufenen Seite. */
    static final double EXPORT_SHARE = 0.1;
    /** So viele Elemente zählt der Startwert, bevor das eigene Tempo eines Auftrags überwiegt. */
    static final int CONFIDENCE = 10;
    /** Auflösung des Balkens; laufend nie ganz voll, damit die Statuszeile ihn nicht wie „Fertig“ ausblendet. */
    static final int SCALE = 1000;
    /** So weit reicht der Blick zurück für das jüngste Tempo, mit dem das Offene rechnet. */
    static final Duration WINDOW = Duration.ofMinutes(2);
    /** Das jüngste Tempo gilt erst ab so vielen erledigten Elementen im Fenster … */
    static final int WINDOW_UNITS = 5;
    /** … und so vielen Sekunden Beobachtung; vorher rechnet das Offene mit dem Tempo bisher. */
    static final int WINDOW_SECONDS = 20;

    /** Geschätzter Stand: Anteil 0 bis 1, unbestimmt (nichts Messbares) und Restzeit, sofern belastbar. */
    record Estimate(double fraction, boolean indeterminate, Duration remaining) {
    }

    /**
     * Ein Auftrag im Gesamtstand: {@code total} seine geschätzte Gesamtdauer in Sekunden, {@code done} der Teil davon,
     * der seinem Anteil erledigter Elemente entspricht, {@code open} die geschätzten Sekunden bis zu seinem Ende;
     * {@code assumed}, wenn seine Größe angenommen ist.
     */
    record Part(double done, double total, boolean assumed, double open) {
    }

    private record Loading(Instant since, long processed) {
    }

    private record Sample(Instant at, double units) {
    }

    /** Bilder und Anhänge, die gerade laden: seit wann und mit wie vielen erledigten Dateien. */
    private final Map<String, Loading> loading = new HashMap<>();
    /** Zuletzt gemessene Sekunden je Element; gilt, solange ein Auftrag nicht misst, etwa nach Fortsetzen. */
    private final Map<String, Double> paces = new HashMap<>();
    /** Erledigte Elemente je Abruf der letzten Minuten, solange ein Auftrag misst; für das jüngste Tempo. */
    private final Map<String, ArrayDeque<Sample>> samples = new HashMap<>();
    /** Größte gesehene Gesamtzahl je Auftrag; ein fortgesetzter beginnt mit leeren Zählern. */
    private final Map<String, Double> sizes = new HashMap<>();
    /** Mittlere bekannte Gesamtzahl je Art, über alle Aufträge der Liste. */
    private Map<JobType, Double> typicalSizes = Map.of();
    /** Der laufende Durchgang. */
    private final Set<String> batch = new LinkedHashSet<>();
    private boolean ended = true;
    /** Alle Aufträge des letzten Abrufs, um neue zu erkennen. */
    private final Set<String> seen = new HashSet<>();
    /** Zeitpunkt des letzten Abrufs; {@code null} vor dem ersten. */
    private Instant lastObserved;
    /**
     * So lange vor dem letzten Abruf darf ein neu gesehener, schon beendeter Auftrag angelegt sein, um noch zum
     * Durchgang zu zählen: er lief zwischen zwei Abrufen ab. Ältere Ergebnisse gehören zu früheren Durchgängen.
     */
    static final Duration FRESH = Duration.ofSeconds(10);

    /**
     * Nimmt den neuen Stand aller Aufträge der Liste auf: Tempo, Größen und den Durchgang. Vor {@link #event} und
     * {@link #estimate} aufrufen, einmal je Abruf.
     */
    void observe(List<JobStatus> jobs, Instant now, Function<JobType, Duration> averages) {
        Set<String> ids = new HashSet<>();
        Map<JobType, double[]> sums = new EnumMap<>(JobType.class);
        for (JobStatus job : jobs) {
            ids.add(job.id());
            double total = units(job)[1];
            if (total > 0 && job.stage() != JobStage.PREPARING) {
                sizes.merge(job.id(), total, Math::max);
                double[] sum = sums.computeIfAbsent(job.type(), type -> new double[2]);
                sum[0] += total;
                sum[1]++;
            }
            if (job.type().download()) {
                boolean busy = job.state() == JobState.RUNNING && job.stage() == JobStage.DOWNLOADING;
                if (busy && !loading.containsKey(job.id())) {
                    loading.put(job.id(), new Loading(now, job.progress().processed()));
                } else if (!busy) {
                    loading.remove(job.id());
                }
                if (busy) {
                    paces.put(job.id(), perUnit(job, now, averages));
                }
            } else if (job.startedAt() != null && units(job)[0] > 0) {
                paces.put(job.id(), perUnit(job, now, averages));
            }
            if (measuring(job)) {
                sample(job.id(), now, units(job)[0]);
            } else {
                samples.remove(job.id());
            }
        }
        loading.keySet().retainAll(ids);
        paces.keySet().retainAll(ids);
        samples.keySet().retainAll(ids);
        sizes.keySet().retainAll(ids);
        Map<JobType, Double> typical = new EnumMap<>(JobType.class);
        sums.forEach((type, sum) -> typical.put(type, sum[0] / sum[1]));
        typicalSizes = typical;
        Map<String, JobStatus> byId = index(jobs);
        batch.retainAll(ids);
        List<String> admitted = new ArrayList<>();
        for (JobStatus job : jobs) {
            // Offen, oder seit dem letzten Abruf angelegt und schon beendet (etwa sofort gescheitert).
            if (open(job, byId) || lastObserved != null && !seen.contains(job.id())
                    && job.createdAt().isAfter(lastObserved.minus(FRESH))) {
                admitted.add(job.id());
            }
        }
        seen.clear();
        seen.addAll(ids);
        lastObserved = now;
        if (ended && !admitted.isEmpty()) {
            batch.clear();
            ended = false;
        }
        batch.addAll(admitted);
        if (!ended && batch.stream().map(byId::get).noneMatch(job -> open(job, byId))) {
            ended = true;
        }
    }

    /** Die Aufträge des Durchgangs in der Reihenfolge der Liste. */
    List<JobStatus> batch(List<JobStatus> jobs) {
        return jobs.stream().filter(job -> batch.contains(job.id())).toList();
    }

    /**
     * Der Stand für die Statuszeile: Zahl der laufenden, wartenden und angehaltenen Aufträge, Anteil und Restzeit des
     * Durchgangs; ohne offenen Auftrag sein Ende („Fertig“), ohne Durchgang „Kein Auftrag läuft“.
     *
     * @param parallelDownloads wie viele Bilder- und Anhängeaufträge laden dürfen; bei 0 keine Restzeit, solange einer
     *                          davon wartet
     */
    JobProgressEvent event(List<JobStatus> jobs, Instant now, Function<JobType, Duration> averages,
                           int parallelDownloads) {
        List<JobStatus> members = batch(jobs);
        if (members.isEmpty()) {
            return JobProgressEvent.none();
        }
        Map<String, JobStatus> byId = index(jobs);
        int running = 0;
        int waiting = 0;
        int paused = 0;
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        boolean stalled = false;
        for (JobStatus job : members) {
            switch (job.state()) {
                case RUNNING -> {
                    if (job.stage() == JobStage.PREPARED) {
                        waiting++;
                        stalled |= parallelDownloads <= 0;
                    } else {
                        running++;
                    }
                }
                case QUEUED -> {
                    if (open(job, byId)) {
                        waiting++;
                        stalled |= job.type().download() && parallelDownloads <= 0;
                    }
                }
                case PAUSED -> paused++;
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (running > 0) {
            parts.add(running + (running == 1 ? " läuft" : " laufen"));
        }
        if (waiting > 0) {
            parts.add(waiting + (waiting == 1 ? " wartet" : " warten"));
        }
        if (paused > 0) {
            parts.add(paused + " angehalten");
        }
        if (running + waiting > 0) {
            Estimate estimate = estimate(members, now, averages, byId);
            Duration remaining = stalled ? null : estimate.remaining();
            String text = "Aufträge: " + String.join(", ", parts)
                    + (estimate.indeterminate() ? "" : " · " + percent(estimate.fraction()) + " %")
                    + (remaining == null ? "" : " · noch " + ActivitiesView.remainingText(remaining));
            return progress(text, estimate, JobProgressEvent.Outcome.RUNNING);
        }
        if (paused > 0) {
            Estimate estimate = estimate(members, now, averages, byId);
            return progress("Aufträge: " + String.join(", ", parts) + " · " + percent(estimate.fraction()) + " %",
                    new Estimate(estimate.fraction(), false, null), JobProgressEvent.Outcome.PAUSED);
        }
        List<String> ends = new ArrayList<>();
        if (completed > 0) {
            ends.add(completed + " fertig");
        }
        if (failed > 0) {
            ends.add(failed + " fehlgeschlagen");
        }
        if (cancelled > 0) {
            ends.add(cancelled + " abgebrochen");
        }
        JobProgressEvent.Outcome outcome = failed > 0 ? JobProgressEvent.Outcome.FAILED
                : cancelled > 0 ? JobProgressEvent.Outcome.CANCELLED
                : JobProgressEvent.Outcome.COMPLETED;
        return new JobProgressEvent("Aufträge: " + String.join(", ", ends), 0, 0, false, outcome);
    }

    private static JobProgressEvent progress(String text, Estimate estimate, JobProgressEvent.Outcome outcome) {
        if (estimate.indeterminate()) {
            return new JobProgressEvent(text, 0, 0, true, outcome);
        }
        long done = Math.min(SCALE - 1, Math.round(estimate.fraction() * SCALE));
        return new JobProgressEvent(text, done, SCALE, false, outcome);
    }

    /** Ganze Prozent, abgerundet und laufend höchstens 99, damit „100 %“ nur heißt: fertig. */
    static int percent(double fraction) {
        return (int) Math.min(99, Math.floor(fraction * 100));
    }

    /** Wie {@link #estimate(Collection, Instant, Function, Map)} für eine Gruppe, die ihren Export selbst enthält. */
    Estimate estimate(Collection<JobStatus> jobs, Instant now, Function<JobType, Duration> averages) {
        return estimate(jobs, now, averages, index(jobs));
    }

    /**
     * Anteil des Erledigten dieser Aufträge, jeder mit seiner geschätzten Gesamtdauer gewichtet ({@link #part}). Die
     * Restzeit nimmt das Größere aus der längsten offenen Dauer und der Summe offener Dauern geteilt durch die Zahl der
     * gleichzeitig laufenden, beide im jüngsten Tempo; sie fehlt,
     * solange eine offene Größe nur angenommen ist oder noch kein laufender Auftrag etwas erledigt hat. Angehaltene
     * und Bilder hinter einem angehaltenen Export zählen nicht zur Restzeit.
     *
     * @param byId alle Aufträge der Liste, damit Bilder und Anhänge ihren Export finden
     */
    Estimate estimate(Collection<JobStatus> jobs, Instant now, Function<JobType, Duration> averages,
                      Map<String, JobStatus> byId) {
        double done = 0;
        double total = 0;
        boolean assumed = false;
        double longest = 0;
        double open = 0;
        int parallel = 0;
        boolean progressed = false;
        for (JobStatus job : jobs) {
            Part part = part(job, now, averages);
            done += part.done();
            total += part.total();
            if (job.state() == JobState.PAUSED || !open(job, byId)) {
                continue;
            }
            assumed |= part.assumed();
            longest = Math.max(longest, part.open());
            open += part.open();
            if (job.state() == JobState.RUNNING && job.stage() != JobStage.PREPARED) {
                parallel++;
                progressed |= units(job)[0] > 0;
            }
        }
        if (total <= 0) {
            return new Estimate(0, assumed, null);
        }
        Duration remaining = assumed || !progressed ? null
                : Duration.ofSeconds(Math.round(Math.max(longest, open / Math.max(1, parallel))));
        return new Estimate(Math.min(1, done / total), done <= 0 && assumed, remaining);
    }

    /**
     * Ein Auftrag im Gesamtstand. Fertige zählen voll mit ihrer echten Größe, gescheiterte und abgebrochene nur mit dem
     * Erledigten (sie tun nichts mehr), alle anderen mit ihrer erwarteten Größe: Erledigtes mal dem Tempo bisher, Offenes
     * mal dem jüngsten Tempo, solange er läuft.
     */
    Part part(JobStatus job, Instant now, Function<JobType, Duration> averages) {
        double[] units = units(job);
        double perUnit = perUnit(job, now, averages);
        if (job.state() == JobState.COMPLETED) {
            return new Part(units[1] * perUnit, units[1] * perUnit, false, 0);
        }
        if (job.state() == JobState.FAILED || job.state() == JobState.CANCELLED) {
            return new Part(units[0] * perUnit, units[0] * perUnit, false, 0);
        }
        boolean assumed = units[1] <= 0 || job.stage() == JobStage.PREPARING;
        double size = assumed ? Math.max(units[1], assumedSize(job)) : units[1];
        double done = Math.min(units[0], size);
        Double recent = recentPace(job.id());
        double open = (size - done) * (recent != null ? recent : perUnit);
        double total = done * perUnit + open;
        return new Part(size <= 0 ? 0 : total * done / size, total, assumed, open);
    }

    /** Ob ein Auftrag gerade Elemente erledigt: Bilder und Anhänge beim Laden, alle anderen, solange sie laufen. */
    private static boolean measuring(JobStatus job) {
        return job.state() == JobState.RUNNING
                && (job.type().download() ? job.stage() == JobStage.DOWNLOADING : job.startedAt() != null);
    }

    /**
     * Merkt sich den Stand eines Abrufs. Behält genau einen Abruf am oder vor dem Beginn des Fensters, damit es ganz
     * gefüllt ist; beginnt neu, wenn die Zähler kleiner werden (fortgesetzt mit leeren Zählern).
     */
    private void sample(String id, Instant now, double done) {
        ArrayDeque<Sample> recent = samples.computeIfAbsent(id, key -> new ArrayDeque<>());
        if (!recent.isEmpty() && done < recent.peekLast().units()) {
            recent.clear();
        }
        recent.addLast(new Sample(now, done));
        Instant start = now.minus(WINDOW);
        while (recent.size() > 1) {
            java.util.Iterator<Sample> oldest = recent.iterator();
            oldest.next();
            if (oldest.next().at().isAfter(start)) {
                break;
            }
            recent.removeFirst();
        }
    }

    /** Sekunden je Element im jüngsten Fenster, oder {@code null}, solange es zu wenig zeigt. */
    Double recentPace(String id) {
        ArrayDeque<Sample> recent = samples.get(id);
        if (recent == null || recent.size() < 2) {
            return null;
        }
        double units = recent.peekLast().units() - recent.peekFirst().units();
        double seconds = seconds(recent.peekFirst().at(), recent.peekLast().at());
        return units < WINDOW_UNITS || seconds < WINDOW_SECONDS ? null : seconds / units;
    }

    /** Größe eines Auftrags, dessen Zähler sie noch nicht kennen. */
    private double assumedSize(JobStatus job) {
        Double seen = sizes.get(job.id());
        if (seen != null) {
            return seen;
        }
        Double typical = typicalSizes.get(job.type());
        if (typical != null) {
            return typical;
        }
        return priorSize(job.type()) * (job.type() == JobType.EXPORT ? 1 + EXPORT_SHARE : 1);
    }

    /**
     * Sekunden je Element: Startwert, gemischt mit dem eigenen Tempo. Bilder und Anhänge messen ab dem Laden, alle
     * anderen ab ihrem Start; misst ein Auftrag gerade nicht, gilt sein zuletzt gemessenes Tempo.
     */
    double perUnit(JobStatus job, Instant now, Function<JobType, Duration> averages) {
        double prior = priorSeconds(job.type(), averages);
        if (job.type().download()) {
            Loading since = loading.get(job.id());
            if (since == null) {
                return paces.getOrDefault(job.id(), prior);
            }
            double files = Math.max(0, job.progress().processed() - since.processed());
            return blend(prior, seconds(since.since(), now), files);
        }
        if (job.startedAt() == null) {
            return paces.getOrDefault(job.id(), prior);
        }
        Instant end = job.finishedAt() != null ? job.finishedAt() : now;
        return blend(prior, seconds(job.startedAt(), end), units(job)[0]);
    }

    private static double blend(double prior, double seconds, double done) {
        return (prior * CONFIDENCE + seconds) / (CONFIDENCE + done);
    }

    private static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1000.0);
    }

    /**
     * Erledigte und gesamte Elemente; gesamt 0 heißt: noch unbekannt. Ein Export ruft erst seine Seiten ab
     * (verarbeitet) und exportiert sie dann (geschrieben oder fehlend), was viel schneller geht ({@link #EXPORT_SHARE}).
     */
    static double[] units(JobStatus job) {
        JobProgress p = job.progress();
        if (job.type() == JobType.EXPORT) {
            return new double[] {p.processed() + EXPORT_SHARE * (p.written() + p.failed()),
                    p.discovered() * (1 + EXPORT_SHARE)};
        }
        return new double[] {p.processed(), p.discovered()};
    }

    /**
     * Startwert in Sekunden je Element: für Bilder und Anhänge der gemessene Durchschnitt je Datei aus dem Core, sonst
     * ein grober Wert je Art, den das eigene Tempo des Auftrags schnell ersetzt.
     */
    static double priorSeconds(JobType type, Function<JobType, Duration> averages) {
        if (type.download()) {
            Duration average = averages.apply(type);
            if (average != null && average.toMillis() > 0) {
                return average.toMillis() / 1000.0;
            }
            return type == JobType.ATTACHMENTS ? 3 : 1;
        }
        return switch (type) {
            case ENRICHMENT, KNOWLEDGE_GRAPH_EXPORT -> 0.05;
            case PURGE -> 0.2;
            case STATEMENT_ANALYSIS -> 1;
            case GLOSSARY -> 2;
            default -> 0.5;
        };
    }

    /** Angenommene Zahl der Elemente, solange weder der Auftrag noch einer seiner Art sie kennt. */
    static long priorSize(JobType type) {
        return switch (type) {
            case ATTACHMENTS -> 50;
            case ENRICHMENT -> 2000;
            case GLOSSARY -> 100;
            case PURGE -> 10;
            default -> 200;
        };
    }

    /**
     * Ob ein Auftrag zum Durchgang zählt: angehalten, laufend oder wartend. Bilder und Anhänge, die auf ihren Export
     * warten, nur solange dieser selbst läuft oder wartet.
     */
    static boolean open(JobStatus job, Map<String, JobStatus> byId) {
        if (job.state() == JobState.PAUSED) {
            return true;
        }
        if (job.state().terminal()) {
            return false;
        }
        if (job.stage() != JobStage.AWAITING) {
            return true;
        }
        JobStatus export = byId.get(job.parameter(ExportAssetCollection.EXPORT_ID));
        return export != null && !export.state().terminal();
    }

    /** Ob in dieser Gruppe gerade etwas läuft (nicht nur wartet oder angehalten ist). */
    static boolean running(Collection<JobStatus> group) {
        return group.stream().anyMatch(job -> job.state() == JobState.RUNNING && job.stage() != JobStage.PREPARED);
    }

    private static Map<String, JobStatus> index(Collection<JobStatus> jobs) {
        Map<String, JobStatus> byId = new HashMap<>();
        for (JobStatus job : jobs) {
            byId.put(job.id(), job);
        }
        return byId;
    }
}
