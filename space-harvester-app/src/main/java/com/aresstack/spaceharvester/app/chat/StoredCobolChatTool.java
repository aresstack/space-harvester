package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Query the same local parser facts as the viewer, and release evidence before returning it. */
public final class StoredCobolChatTool {
    public static final String NAME = "cobol_suchen";
    private static final String UNAVAILABLE = "Keine freigegebene FTP-Wissensquelle oder Fundstellen nicht mehr aktuell.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Holdings holdings;

    interface Holdings {
        Set<String> sources();
        StoredCobolSearch.Snapshot search(String source, BooleanSupplier cancelled);
        java.util.Optional<DeliveredPage> page(PageKey key);
    }
    private final Supplier<Set<String>> allowedSources;
    private final BooleanSupplier cancelled;
    private final BiConsumer<String, PageKey> release;

    public StoredCobolChatTool(Workspace workspace, Supplier<Set<String>> allowedSources,
                               BooleanSupplier cancelled, BiConsumer<String, PageKey> release) {
        this(new Holdings() {
            @Override public Set<String> sources() {
                var result = new java.util.TreeSet<String>();
                for (var source : workspace.sources()) {
                    if ("ftp".equalsIgnoreCase(source.type()) && workspace.sourceEnabled(source.id())
                            && !workspace.sourceDataBeingDeleted(source.id())) result.add(source.id());
                }
                return result;
            }
            @Override public StoredCobolSearch.Snapshot search(String source, BooleanSupplier cancelled) {
                return workspace.storedCobolSearch(source, cancelled);
            }
            @Override public java.util.Optional<DeliveredPage> page(PageKey key) {
                return workspace.cachedPage(key.sourceId(), key.pageId());
            }
        }, allowedSources, cancelled, release);
    }

    StoredCobolChatTool(Holdings holdings, Supplier<Set<String>> allowedSources,
                        BooleanSupplier cancelled, BiConsumer<String, PageKey> release) {
        this.holdings = holdings;
        this.allowedSources = allowedSources;
        this.cancelled = cancelled;
        this.release = release;
    }

    public static AiChat.Tool definition() {
        return new AiChat.Tool(NAME, "Durchsucht aktuelle lokal gespeicherte COBOL-Analysen einer explizit "
                + "für KI freigegebenen FTP-Wissensquelle. Ohne quelle werden nur die verfügbaren Quellen genannt. "
                + "PROGRAM: Programme, COPYBOOK: Copybooks/SYSLIB-Kandidaten, COPY_USAGE: COPY-Verwendungen, "
                + "DATA: Datenfelder, CALLERS/CALLEES: Aufrufer/Aufrufe, "
                + "CALLERS_HIERARCHY/CALLEES_HIERARCHY: mehrstufige statische Beziehungen. "
                + "Bei mehrdeutiger Wurzel den ROOT_SELECTOR verwenden. Keine Live-Abfrage oder Laufzeitaussage.",
                """
                {"type":"object","properties":{"quelle":{"type":"string"},"art":{"type":"string","enum":["ALL","PROGRAM","COPYBOOK","COPY_USAGE","DATA","CALLERS","CALLEES","CALLERS_HIERARCHY","CALLEES_HIERARCHY"]},"suchtext":{"type":"string"},"ab":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":50}}}
                """);
    }

    public String run(JsonNode arguments) {
        return run(arguments, () -> false);
    }

    private String run(JsonNode arguments, BooleanSupplier stopped) {
        BooleanSupplier interrupted = () -> cancelled.getAsBoolean() || stopped.getAsBoolean()
                || Thread.currentThread().isInterrupted();
        if (interrupted.getAsBoolean()) return "Abgebrochen.";
        Set<String> available = available();
        String source = arguments.path("quelle").asString("").strip();
        if (source.isEmpty()) return available.isEmpty() ? UNAVAILABLE : "FTP-Wissensquellen: " + available;
        if (!available.contains(source)) return UNAVAILABLE;
        var mode = CobolSearch.Mode.valueOf(arguments.path("art").asString("ALL").toUpperCase(Locale.ROOT));
        String query = arguments.path("suchtext").asString("");
        if (query.length() > 256) return "Suchtext zu lang.";
        int offset = arguments.path("ab").asInt(0);
        int limit = Math.min(50, arguments.path("limit").asInt(25));
        var snapshot = holdings.search(source, () -> interrupted.getAsBoolean()
                || !allowedSources.get().contains(source));
        var result = snapshot.search().search(mode, query, limit, offset);
        Set<StoredCobolSearch.Origin> evidence = new LinkedHashSet<>();
        StringBuilder answer = new StringBuilder(snapshot.coverage()).append('\n')
                .append("Fundstellen: ").append(result.total()).append('\n');
        int displayed = 0;
        for (var fact : result.hits()) {
            String row = format(fact);
            if (answer.length() + row.length() > 18000) break;
            var origins = snapshot.evidenceFor(fact);
            if (origins.isEmpty()) return UNAVAILABLE;
            evidence.addAll(origins);
            answer.append(row);
            displayed++;
        }
        if (offset < result.total() && displayed < result.total() - offset) {
            answer.append("Weitere Fundstellen mit ab=").append(offset + displayed);
        }
        if (interrupted.getAsBoolean() || !available().contains(source)) return UNAVAILABLE;
        List<DeliveredPage> verified = new ArrayList<>();
        for (var origin : evidence) {
            if (interrupted.getAsBoolean() || !allowedSources.get().contains(source)) return UNAVAILABLE;
            if (!source.equals(origin.key().sourceId())) return UNAVAILABLE;
            var page = holdings.page(origin.key()).orElse(null);
            if (page == null || !origin.rawSha256().equals(page.snapshot().payload().rawSha256())) return UNAVAILABLE;
            verified.add(page);
        }
        for (var page : verified) {
            if (interrupted.getAsBoolean() || !allowedSources.get().contains(source)) return UNAVAILABLE;
            release.accept(page.header().metadata().partitionKey(), page.header().key());
        }
        return !interrupted.getAsBoolean() && available().contains(source) ? answer.toString() : UNAVAILABLE;
    }

    public boolean availableForChat() {
        return !available().isEmpty();
    }

    private Set<String> available() {
        var allowed = allowedSources.get();
        if (allowed.isEmpty()) return Set.of();
        var available = new java.util.TreeSet<>(holdings.sources());
        available.retainAll(allowed);
        return available;
    }

    private static String format(CobolSearch.Fact fact) {
        var attributes = new java.util.LinkedHashMap<>(fact.attributes());
        String source = attributes.remove("SOURCE_ID");
        String page = attributes.remove("PAGE_ID");
        attributes.remove("SOURCE_RAW_SHA256");
        attributes.remove("PROGRAM_PAGE_ID");
        attributes.remove("PROGRAM_RAW_SHA256");
        String details = attributes.toString();
        if (details.length() > 1200) details = details.substring(0, 1200) + " …";
        return fact.kind() + " | " + fact.name() + " | Programm: " + fact.program()
                + " | Member: " + fact.member() + " | Fundstelle: " + fact.source() + ":" + fact.line()
                + " | Kennung: " + (source == null || page == null ? "unbekannt" : source + "/" + page)
                + " | Programmrevision: " + fact.revision() + " | " + details
                + (fact.partial() ? " | Teilanalyse" : "") + "\n";
    }

    /** Add the knowledge tool to a deck turn without changing its existing host tools. */
    public ChatToolset attach(ChatToolset delegate, BooleanSupplier stillAllowed) {
        return new ChatToolset() {
            private volatile boolean stopped;
            @Override public List<AiChat.Tool> definitions() {
                var result = new ArrayList<>(delegate.definitions());
                if (!stopped && stillAllowed.getAsBoolean() && availableForChat()) result.add(definition());
                return List.copyOf(result);
            }
            @Override public String run(AiChat.ToolCall call) {
                if (!NAME.equals(call.name())) return delegate.run(call);
                if (stopped || !stillAllowed.getAsBoolean()) return UNAVAILABLE;
                try {
                    String result = StoredCobolChatTool.this.run(JSON.readTree(call.argumentsJson()),
                            () -> stopped || !stillAllowed.getAsBoolean());
                    return stopped || !stillAllowed.getAsBoolean() ? UNAVAILABLE : result;
                } catch (RuntimeException error) {
                    return "COBOL-Suche nicht verfügbar.";
                }
            }
            @Override public String describe(AiChat.ToolCall call) {
                return NAME.equals(call.name()) ? "Durchsucht gespeicherte COBOL-Analysen" : delegate.describe(call);
            }
            @Override public String viewContext() { return delegate.viewContext(); }
            @Override public String viewIdentity() { return delegate.viewIdentity(); }
            @Override public void modelSelected(String model) { delegate.modelSelected(model); }
            @Override public void cancel() { stopped = true; delegate.cancel(); }
        };
    }
}
