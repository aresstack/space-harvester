package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.runtime.program.ProgramMcpTools;
import com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adapt the common read-only program tools directly to an internal chat turn. */
public final class StoredProgramChatTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RESPONSE_CHARS = 18000;
    private static final String UNAVAILABLE = "Keine aktuelle freigegebene Programmanalyse verfügbar.";
    public static final String SOURCES = "program_sources";
    public static final String SEARCH = "program_search";
    public static final String ANALYSIS = "program_analysis";
    private static final Set<String> NAMES = Set.of(SOURCES, SEARCH, ANALYSIS);

    private final ProgramMcpTools tools;
    private final Supplier<Set<String>> allowed;
    private final BooleanSupplier cancelled;

    public StoredProgramChatTools(Workspace workspace, Supplier<Set<String>> allowedSources,
                                  BooleanSupplier cancelled, BiConsumer<String, PageKey> release) {
        this(new ProgramMcpTools.Holdings() {
            @Override public Set<String> sources() { return workspace.programChatSources(); }
            @Override public StoredProgramSearch.Snapshot search(Set<String> sources, BooleanSupplier stopped) {
                return workspace.storedProgramSearch(sources, stopped);
            }
            @Override public Optional<DeliveredPage> page(PageKey key) {
                return workspace.cachedPage(key.sourceId(), key.pageId());
            }
            @Override public List<Enrichments.View> analyses(DeliveredPage page) {
                return workspace.enrichments(page);
            }
            @Override public void authorize(DeliveredPage page) {
                release.accept(page.header().metadata().partitionKey(), page.header().key());
            }
        }, allowedSources, cancelled);
    }

    StoredProgramChatTools(ProgramMcpTools.Holdings holdings, Supplier<Set<String>> allowedSources,
                           BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        allowed = () -> {
            var sources = new LinkedHashSet<>(holdings.sources());
            sources.retainAll(allowedSources.get());
            return Set.copyOf(sources);
        };
        var selected = allowed.get();
        tools = new ProgramMcpTools(new ProgramMcpTools.Holdings() {
            @Override public Set<String> sources() { return allowed.get(); }
            @Override public StoredProgramSearch.Snapshot search(Set<String> sources, BooleanSupplier stopped) {
                return holdings.search(sources, stopped);
            }
            @Override public Optional<DeliveredPage> page(PageKey key) { return holdings.page(key); }
            @Override public List<Enrichments.View> analyses(DeliveredPage page) { return holdings.analyses(page); }
            @Override public void authorize(DeliveredPage page) { holdings.authorize(page); }
        }, selected);
    }

    public List<AiChat.Tool> definitions() {
        if (cancelled.getAsBoolean() || allowed.get().isEmpty()) return List.of();
        return tools.definitions().stream().map(definition -> new AiChat.Tool(
                (String) definition.get("name"), (String) definition.get("description"),
                JSON.writeValueAsString(definition.get("inputSchema")))).toList();
    }

    public String run(String name, JsonNode arguments) {
        return run(name, arguments, () -> false);
    }

    private String run(String name, JsonNode arguments, BooleanSupplier stopped) {
        BooleanSupplier interrupted = () -> cancelled.getAsBoolean() || stopped.getAsBoolean()
                || Thread.currentThread().isInterrupted();
        if (interrupted.getAsBoolean()) return "Abgebrochen.";
        try {
            String answer = JSON.writeValueAsString(tools.call(name, arguments, interrupted));
            if (interrupted.getAsBoolean()) return "Abgebrochen.";
            if (answer.length() > RESPONSE_CHARS) {
                return "Antwort zu umfangreich. Kleinere Seite mit limit anfordern.";
            }
            return answer;
        } catch (CancellationException stoppedException) {
            return "Abgebrochen oder Freigabe entzogen.";
        } catch (IllegalArgumentException invalid) {
            return "Ungültige Argumente oder nicht verfügbarer Analyseabschnitt.";
        } catch (RuntimeException unavailable) {
            return UNAVAILABLE;
        }
    }

    public static boolean handles(String name) { return NAMES.contains(name); }

    public static String describe(String name) {
        return switch (name) {
            case SOURCES -> "Ermittelt freigegebene Programmquellen";
            case SEARCH -> "Durchsucht gespeicherte Programmanalysen";
            case ANALYSIS -> "Liest gespeicherte technische Programmanalyse";
            default -> "Werkzeug";
        };
    }

    /** Preserve the tools of the current Command Deck perspective. */
    public ChatToolset attach(ChatToolset delegate, BooleanSupplier stillAllowed) {
        return new ChatToolset() {
            private volatile boolean stopped;
            @Override public List<AiChat.Tool> definitions() {
                var definitions = new ArrayList<>(delegate.definitions());
                if (!stopped && stillAllowed.getAsBoolean()) definitions.addAll(StoredProgramChatTools.this.definitions());
                return List.copyOf(definitions);
            }
            @Override public String run(AiChat.ToolCall call) {
                if (!handles(call.name())) return delegate.run(call);
                if (stopped || !stillAllowed.getAsBoolean()) return UNAVAILABLE;
                try {
                    return StoredProgramChatTools.this.run(call.name(), JSON.readTree(call.argumentsJson()),
                            () -> stopped || !stillAllowed.getAsBoolean());
                } catch (RuntimeException invalid) {
                    return UNAVAILABLE;
                }
            }
            @Override public String describe(AiChat.ToolCall call) {
                return handles(call.name()) ? StoredProgramChatTools.describe(call.name()) : delegate.describe(call);
            }
            @Override public String viewContext() { return delegate.viewContext(); }
            @Override public String viewIdentity() { return delegate.viewIdentity(); }
            @Override public void modelSelected(String model) { delegate.modelSelected(model); }
            @Override public void cancel() { stopped = true; delegate.cancel(); }
        };
    }
}
