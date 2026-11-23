package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.JobStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Die Werkzeuge des Bereichs FTP (Command Deck): Datasets und Members der gewählten Sitzung auflisten und lesen,
 * JES-Jobs auflisten und ihren Spool lesen. Nur lesen: Schreiben, Löschen, Absenden gibt es hier nicht. Gebunden an
 * die Sitzung, die beim Senden gewählt war.
 */
public final class FtpChatTools implements ChatToolset {

    public static final String LIST = "datasets_auflisten";
    public static final String READ = "dataset_lesen";
    public static final String JOBS = "jobs_auflisten";
    public static final String SPOOL = "spool_lesen";

    static final int MAX_ENTRIES = 300;
    static final long TIMEOUT_SECONDS = 120;
    static final String NO_SESSION = "Im FTP-Bereich ist keine verbundene Sitzung gewählt. Bitte den Benutzer, eine "
            + "Verbindung zu öffnen.";

    private final FtpSession session;
    private final Supplier<FtpSession> active;
    private final Map<String, String> read = new ConcurrentHashMap<>();

    public FtpChatTools(FtpSession session, Supplier<FtpSession> active) {
        this.session = session;
        this.active = active == null ? () -> session : active;
    }

    /** Die Regeln für die Systemnachricht. */
    public static List<String> rules() {
        return List.of("Mit " + LIST + " und " + READ + " listest und liest du Datasets und Members (MVS-Namen wie "
                + "USER.SOURCE oder USER.SOURCE(PGM1), Muster mit *), mit " + JOBS + " und " + SPOOL
                + " JES-Jobs und ihren Spool.");
    }

    @Override
    public List<AiChat.Tool> definitions() {
        return List.of(
                new AiChat.Tool(LIST, "Listet Datasets nach Muster (etwa USER.*) oder die Members eines "
                        + "partitionierten Datasets.", "{\"type\":\"object\",\"properties\":{\"pfad\":{\"type\":"
                                + "\"string\",\"description\":\"Dataset, Muster oder Verzeichnis\"}},"
                                + "\"required\":[\"pfad\"]}"),
                new AiChat.Tool(READ, "Liest ein sequentielles Dataset oder ein Member als Text, höchstens "
                        + DeckChatTools.TEXT_CHARS + " Zeichen je Aufruf, mit ab weiter.",
                        "{\"type\":\"object\",\"properties\":{\"pfad\":{\"type\":\"string\",\"description\":"
                                + "\"etwa USER.SOURCE(PGM1)\"},\"ab\":{\"type\":\"integer\"}},"
                                + "\"required\":[\"pfad\"]}"),
                new AiChat.Tool(JOBS, "Listet JES-Jobs: ohne Angaben die eigenen.", "{\"type\":\"object\","
                        + "\"properties\":{\"besitzer\":{\"type\":\"string\"},\"jobname\":{\"type\":\"string\","
                        + "\"description\":\"Muster mit *\"}}}"),
                new AiChat.Tool(SPOOL, "Liest den ganzen Spool eines Jobs, höchstens " + DeckChatTools.TEXT_CHARS
                        + " Zeichen je Aufruf, mit ab weiter.", "{\"type\":\"object\",\"properties\":{"
                                + "\"job_id\":{\"type\":\"string\",\"description\":\"etwa JOB01234\"},"
                                + "\"ab\":{\"type\":\"integer\"}},\"required\":[\"job_id\"]}"));
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case LIST -> "Listet Datasets";
            case READ -> "Liest ein Dataset";
            case JOBS -> "Listet JES-Jobs";
            case SPOOL -> "Liest einen Spool";
            default -> "Werkzeug";
        };
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (session == null) {
            return NO_SESSION;
        }
        if (active.get() != session) {
            return "Der Benutzer hat inzwischen eine andere FTP-Sitzung gewählt; er soll die Frage dort neu stellen.";
        }
        JsonNode arguments = DeckChatTools.arguments(call);
        String path = arguments.path("pfad").asString("").strip();
        int from = arguments.path("ab").asInt(0);
        try {
            return switch (call.name()) {
                case LIST -> path.isEmpty() ? "Abgelehnt: pfad fehlt." : list(path);
                case READ -> path.isEmpty() ? "Abgelehnt: pfad fehlt." : DeckChatTools.page(path,
                        cached("ds:" + path, () -> await(session.files(c -> c.read(path).text()))), from);
                case JOBS -> jobs(arguments.path("besitzer").asString(""), arguments.path("jobname").asString("*"));
                case SPOOL -> {
                    String job = arguments.path("job_id").asString("").strip();
                    yield job.isEmpty() ? "Abgelehnt: job_id fehlt." : DeckChatTools.page("Spool " + job,
                            cached("spool:" + job, () -> await(session.jobs(c -> c.allSpool(job)))), from);
                }
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Der Host meldet einen Fehler: " + FtpSession.describe(e);
        }
    }

    private String list(String path) throws Exception {
        List<RemoteEntry> entries = await(session.files(c -> c.list(path)));
        if (entries.isEmpty()) {
            return "Nichts gefunden zu " + path + ".";
        }
        StringBuilder b = new StringBuilder(path).append(" (").append(entries.size()).append(" Einträge):\n");
        for (RemoteEntry entry : entries.subList(0, Math.min(entries.size(), MAX_ENTRIES))) {
            b.append(entry.directory() ? "[PDS/Ordner] " : "").append(entry.name());
            String format = entry.details().format();
            if (!format.isEmpty()) {
                b.append(" · ").append(format);
            }
            b.append('\n');
        }
        if (entries.size() > MAX_ENTRIES) {
            b.append("… ").append(entries.size() - MAX_ENTRIES).append(" weitere nicht gezeigt\n");
        }
        return b.toString();
    }

    private String jobs(String owner, String name) throws Exception {
        List<JesJob> jobs = await(session.jobs(c -> c.jobs(new JobFilter(owner, name, JobStatus.ALL))));
        if (jobs.isEmpty()) {
            return "Keine Jobs gefunden.";
        }
        StringBuilder b = new StringBuilder("Jobs (").append(jobs.size()).append("):\n");
        for (JesJob job : jobs.subList(0, Math.min(jobs.size(), MAX_ENTRIES))) {
            b.append(job.jobId()).append(" · ").append(job.jobName()).append(" · ").append(job.owner()).append(" · ")
                    .append(job.status());
            job.returnCode().ifPresent(rc -> b.append(" · RC ").append(rc));
            b.append(" · ").append(job.spoolFiles()).append(" Spool-Dateien\n");
        }
        return b.toString();
    }

    private interface Load {
        String get() throws Exception;
    }

    private String cached(String key, Load load) throws Exception {
        String text = read.get(key);
        if (text == null) {
            text = load.get();
            read.put(key, text == null ? "" : text);
        }
        return text;
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new java.io.IOException("Keine Antwort vom Host in " + TIMEOUT_SECONDS + " s");
        }
    }
}
