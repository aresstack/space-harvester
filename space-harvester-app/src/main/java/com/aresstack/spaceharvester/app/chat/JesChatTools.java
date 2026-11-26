package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tools.jackson.databind.JsonNode;

/** Expose only the permitted JES navigation actions in the active FTP view. */
public final class JesChatTools implements ChatToolset {
    public static final String SEARCH = "jobs_suchen", LIST = "jobs_auflisten",
            SELECT = "job_auswaehlen", SPOOLS = "spool_auflisten",
            OPEN = "spool_auswaehlen", READ = "spool_lesen";
    private static final String SWITCHED = "Abgelehnt: JES-Ansicht nicht mehr aktiv.";
    private final Access access;
    private final BooleanSupplier active;
    private volatile boolean cancelled;

    public interface Access {
        Snapshot chatSnapshot();
        CompletableFuture<Void> chatSearch(String owner, String name);
        CompletableFuture<Void> chatSelectJob(String id);
        CompletableFuture<Void> chatSelectSpool(String job, int id);
    }

    public record Snapshot(JobFilter input, JobFilter applied, String state, List<JesJob> jobs,
                           JesJob selected, List<SpoolFile> spools, SpoolFile selectedSpool,
                           SpoolFile loaded, boolean whole, String text) {
        public Snapshot {
            jobs = List.copyOf(jobs);
            spools = List.copyOf(spools);
        }

        String identity() {
            return (selected == null ? "" : selected.jobId()) + ":"
                    + (selectedSpool == null ? "" : selectedSpool.id()) + ":" + whole
                    + ":" + (loaded == null ? "" : loaded.id()) + ":" + text.hashCode();
        }
    }

    public JesChatTools(Access access, BooleanSupplier active) {
        this.access = access;
        this.active = active;
    }

    public static List<String> rules() {
        return List.of("Aktiv ist JES-Jobs, nicht Datasets. Nutze die aktuelle Auswahl statt früherer Dataset-Texte.",
                "Du darfst Suchfilter setzen, die Liste aktualisieren, sichtbare Jobs und ihre Spools auswählen und lesen.",
                "Nutze jobs_suchen, jobs_auflisten, job_auswaehlen, spool_auflisten, spool_auswaehlen und spool_lesen. "
                        + "Keine schreibenden Hostaktionen. Unterscheide Job-ID und Spool-ID.");
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return List.of(
                tool(SEARCH, "Setzt Besitzer und Jobname wie Enter und lädt die sichtbare Jobliste.",
                        "{\"besitzer\":{\"type\":\"string\"},\"jobname\":{\"type\":\"string\"}}", "[]"),
                tool(LIST, "Liest die angezeigte Jobliste ohne Hostabfrage.", "{\"ab\":{\"type\":\"integer\"}}", "[]"),
                tool(SELECT, "Wählt einen sichtbaren Job und lädt seine Details.",
                        "{\"job_id\":{\"type\":\"string\"}}", "[\"job_id\"]"),
                tool(SPOOLS, "Listet die Spool-Einträge des ausgewählten Jobs.",
                        "{\"ab\":{\"type\":\"integer\"}}", "[]"),
                tool(OPEN, "Öffnet einen Spool des ausgewählten Jobs.",
                        "{\"job_id\":{\"type\":\"string\"},\"spool_id\":{\"type\":\"integer\"}}",
                        "[\"job_id\",\"spool_id\"]"),
                tool(READ, "Liest den aktuell geladenen Spool abschnittsweise.",
                        "{\"job_id\":{\"type\":\"string\"},\"spool_id\":{\"type\":\"integer\"},\"ab\":{\"type\":\"integer\"}}", "[]"));
    }

    private static AiChat.Tool tool(String name, String description, String properties, String required) {
        return new AiChat.Tool(name, description, "{\"type\":\"object\",\"properties\":" + properties
                + ",\"required\":" + required + ",\"additionalProperties\":false}");
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case SEARCH -> "Sucht JES-Jobs";
            case LIST -> "Liest Jobliste";
            case SELECT -> "Wählt Job";
            case SPOOLS -> "Listet Spools";
            case OPEN -> "Öffnet Spool";
            case READ -> "Liest Spool";
            default -> "JES-Werkzeug";
        };
    }

    @Override
    public String viewContext() {
        try {
            return overview(onEdt(access::chatSnapshot));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "JES-Ansicht nicht verfügbar.";
        }
    }

    @Override
    public String viewIdentity() {
        try {
            return onEdt(access::chatSnapshot).identity();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "nicht verfügbar";
        }
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (!valid()) return SWITCHED;
        JsonNode args = DeckChatTools.arguments(call);
        int from = Math.max(0, args.path("ab").asInt(0));
        String job = args.path("job_id").asString("").strip();
        int spool = args.path("spool_id").asInt(-1);
        try {
            Snapshot current = onEdt(access::chatSnapshot);
            String result = switch (call.name()) {
                case SEARCH -> {
                    String owner = pattern(args.path("besitzer").asString(current.input().owner()));
                    String name = pattern(args.path("jobname").asString(current.input().jobName()));
                    await(onEdt(() -> access.chatSearch(owner, name)));
                    yield jobs(onEdt(access::chatSnapshot), 0);
                }
                case LIST -> jobs(current, from);
                case SELECT -> {
                    await(onEdt(() -> access.chatSelectJob(job)));
                    Snapshot chosen = onEdt(access::chatSnapshot);
                    requireJob(chosen, job);
                    yield spools(chosen, 0);
                }
                case SPOOLS -> spools(current, from);
                case OPEN -> {
                    await(onEdt(() -> access.chatSelectSpool(job, spool)));
                    yield read(onEdt(access::chatSnapshot), job, spool, 0);
                }
                case READ -> read(current, job, spool, from);
                default -> "Unbekanntes JES-Werkzeug.";
            };
            return valid() ? result : SWITCHED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "JES-Recherche abgebrochen.";
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return valid() ? "JES-Recherche nicht möglich: " + cause.getMessage() : SWITCHED;
        }
    }

    private static String pattern(String value) {
        String result = value.strip();
        if (!result.matches("[A-Za-z0-9@#$*?%_-]{0,32}")) {
            throw new IllegalArgumentException("Ungültiges JES-Suchmuster.");
        }
        return result;
    }

    private static void requireJob(Snapshot s, String job) {
        if (s.selected() == null || !s.selected().jobId().equalsIgnoreCase(job)) {
            throw new IllegalStateException("Der ausgewählte Job hat sich geändert.");
        }
    }


    private static String overview(Snapshot s) {
        return "Aktive FTP-Unteransicht: JES-Jobs\nFilterfelder: " + s.input()
                + "\nAngewandte Filter: " + (s.applied() == null ? "noch keine" : s.applied())
                + "\nJobliste: " + s.jobs().size() + " sichtbare Treffer\n" + selection(s) + "\n"
                + (s.whole() ? "Gesamtausgabe geöffnet" : s.selectedSpool() == null ? "Kein Spool ausgewählt"
                    : "Ausgewählter Spool: " + spoolLine(s.selectedSpool())
                    + (s.loaded() == null ? " (noch nicht geladen)" : " (geladen)"))
                + "\nZustand: " + s.state();
    }

    private static String selection(Snapshot s) {
        return s.selected() == null ? "Kein Job ausgewählt."
                : "Ausgewählter Job: " + jobLine(s.selected()) + " · Klasse " + s.selected().jobClass()
                    + "\n" + s.spools().size() + " Spool-Einträge";
    }

    private static String jobLine(JesJob job) {
        return job.jobId() + " · " + job.jobName() + " · " + job.owner() + " · " + job.status()
                + job.returnCode().map(rc -> " · RC " + rc).orElse("") + " · " + job.spoolFiles() + " Spool-Dateien";
    }

    private static String spoolLine(SpoolFile file) {
        return "#" + file.id() + " · " + file.ddName() + " · " + file.stepName() + " · " + file.procStep()
                + " · " + file.records() + " Zeilen";
    }

    private static String jobs(Snapshot s, int from) {
        return overview(s) + "\nSichtbare Jobliste:\n" + page(s.jobs(), from, JesChatTools::jobLine);
    }

    private static String spools(Snapshot s, int from) {
        return selection(s) + "\n" + s.state() + "\n" + page(s.spools(), from, JesChatTools::spoolLine);
    }

    private static <T> String page(List<T> values, int from, java.util.function.Function<T, String> format) {
        int start = Math.min(values.size(), from);
        int end = Math.min(values.size(), start + 100);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) out.append(format.apply(values.get(i))).append('\n');
        if (end < values.size()) out.append("Weiter mit ab=").append(end);
        return out.toString();
    }

    private static String read(Snapshot s, String job, int spool, int from) {
        if (s.selected() == null) return "Kein Job ausgewählt.";
        if (!job.isEmpty()) requireJob(s, job);
        String id = s.selected().jobId();
        if (s.whole() && spool < 0) return DeckChatTools.page(id + " Gesamtausgabe", s.text(), from);
        if (s.loaded() == null || s.selectedSpool() == null || s.loaded().id() != s.selectedSpool().id()
                || (spool >= 0 && s.loaded().id() != spool)) {
            return "Spool nicht geladen oder Auswahl geändert.";
        }
        return DeckChatTools.page(id + " " + spoolLine(s.loaded()), s.text(), from);
    }

    private boolean valid() {
        return !cancelled && active.getAsBoolean();
    }

    private <T> T onEdt(Supplier<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!valid()) throw new IllegalStateException(SWITCHED);
            return operation.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (result.isCancelled()) return;
            try {
                if (!valid()) throw new IllegalStateException(SWITCHED);
                result.complete(operation.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return await(result);
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        if (SwingUtilities.isEventDispatchThread() && !future.isDone()) {
            throw new IllegalStateException("Hostzugriff darf den UI-Thread nicht blockieren.");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        try {
            while (valid() && System.nanoTime() < deadline) {
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Check scope and cancellation before waiting again.
                }
            }
            future.cancel(false);
            throw new IllegalStateException(valid() ? "Zeitlimit bei JES-Recherche." : SWITCHED);
        } catch (InterruptedException e) {
            future.cancel(false);
            throw e;
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }
}
