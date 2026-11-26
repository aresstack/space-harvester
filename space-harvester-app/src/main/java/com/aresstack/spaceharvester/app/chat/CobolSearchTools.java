package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Expose the same read-only COBOL query as the search dialog; never acquire content from a tool call. */
public final class CobolSearchTools implements ChatToolset {
    public static final String SEARCH = "cobol_suchen";
    public record Snapshot(CobolSearch search, String coverage) { }

    private final ChatToolset delegate;
    private final Supplier<Optional<Snapshot>> snapshot;
    private final BooleanSupplier allowed;

    public CobolSearchTools(ChatToolset delegate, Supplier<Optional<Snapshot>> snapshot, BooleanSupplier allowed) {
        this.delegate = delegate;
        this.snapshot = snapshot;
        this.allowed = allowed;
    }

    @Override
    public List<AiChat.Tool> definitions() {
        var tools = new ArrayList<>(delegate.definitions());
        tools.add(new AiChat.Tool(SEARCH,
                "Durchsucht die vom Benutzer analysierte COBOL-Momentaufnahme der FTP-Sitzung. "
                        + "COPYBOOK: aufgelöste Copybooks und SYSLIB-Kandidaten; COPY_USAGE: COPY-Verwendungen samt aufgelöstem Pfad; "
                        + "DATA: Datenfelder; PROGRAM: PROGRAM-ID; CALLERS: statische Aufrufer eines Namens; "
                        + "CALLEES: Aufrufe eines Programms einschließlich dynamischer Ziele. "
                        + "CALLERS_HIERARCHY / CALLEES_HIERARCHY: mehrstufige statische Beziehungen, exakte PROGRAM-ID oder Member; "
                        + "bei mehreren Wurzeln den ROOT_SELECTOR verwenden. ALREADY_VISITED kennzeichnet erneut erreichte Knoten. "
                        + "Keine Live-Suche und keine Laufzeitaussage. Teilanalysen beachten.",
                """
                {"type":"object","properties":{"art":{"type":"string","enum":["ALL","PROGRAM","COPYBOOK","COPY_USAGE","DATA","CALLERS","CALLEES","CALLERS_HIERARCHY","CALLEES_HIERARCHY"]},"suchtext":{"type":"string"},"ab":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":50}},"required":["art","suchtext"]}
                """));
        return List.copyOf(tools);
    }

    @Override
    public String describe(AiChat.ToolCall call) {
        return SEARCH.equals(call.name()) ? "Durchsucht COBOL-Analyse" : delegate.describe(call);
    }

    @Override
    public String run(AiChat.ToolCall call) {
        if (!allowed.getAsBoolean()) return "FTP-Analysebereich nicht mehr aktiv.";
        if (!SEARCH.equals(call.name())) return delegate.run(call);
        try {
            var current = snapshot.get().orElse(null);
            if (current == null) return "Keine freigegebene COBOL-Momentaufnahme. Benutzer muss den Bestand analysieren.";
            var args = DeckChatTools.arguments(call);
            var mode = CobolSearch.Mode.valueOf(args.path("art").asString("").toUpperCase(Locale.ROOT));
            String query = args.path("suchtext").asString("");
            if (query.length() > 256) return "Suchtext zu lang.";
            int offset = args.path("ab").asInt(0);
            int limit = Math.min(50, args.path("limit").asInt(25));
            var results = current.search().search(mode, query, limit, offset);
            StringBuilder answer = new StringBuilder(current.coverage()).append("\n")
                    .append("Fundstellen: ").append(results.total()).append("\n");
            int displayed = 0;
            for (var hit : results.hits()) {
                String row = format(hit);
                if (answer.length() + row.length() > 18000) break;
                answer.append(row);
                displayed++;
            }
            if (offset < results.total() && displayed < results.total() - offset) {
                answer.append("Weitere Fundstellen mit ab=").append(offset + displayed);
            }
            return allowed.getAsBoolean() && current.equals(snapshot.get().orElse(null))
                    ? answer.toString() : "COBOL-Momentaufnahme wurde zwischenzeitlich verworfen.";
        } catch (IllegalArgumentException invalid) {
            return "Ungültige Suchart oder Seitengrenze.";
        }
    }

    private String format(CobolSearch.Fact hit) {
        String attributes = hit.attributes().toString();
        if (attributes.length() > 1200) attributes = attributes.substring(0, 1200) + " …";
        return hit.kind() + " | " + hit.name() + " | Programm: " + hit.program()
                + " | Member: " + hit.member() + " | Fundstelle: " + hit.source() + ":" + hit.line()
                + " | Programmrevision: " + hit.revision() + " | " + attributes
                + (hit.partial() ? " | Teilanalyse" : "") + "\n";
    }

    @Override public void modelSelected(String model) { delegate.modelSelected(model); }
    @Override public void cancel() { delegate.cancel(); }
    @Override public String viewIdentity() { return delegate.viewIdentity(); }
    @Override public String viewContext() { return delegate.viewContext(); }
}
