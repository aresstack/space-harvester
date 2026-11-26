package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.program.NaturalProgramEnrichment;
import com.aresstack.spaceharvester.app.runtime.program.ProgramMcpTools;
import com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class StoredProgramChatToolsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path home;

    @Test
    void queriesTheStoredAnalysisDirectlyAndRespectsTurnPermissionsWithoutHttp() throws Exception {
        var program = snapshot("ndv", "DEMO.NSP", "CALLNAT 'BOOKING'\nEND\n");
        var secret = snapshot("secret", "SECRET.NSP", "CALLNAT 'HIDDEN'\nEND\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var enrichments = new Enrichments(new KnowledgeDocumentTextProjector(),
                    List.of(new NaturalProgramEnrichment(Set.of("ndv", "secret"))), store, Clock.systemUTC());
            for (var snapshot : List.of(program, secret)) {
                cache.save(snapshot);
                assertThat(enrichments.refresh(snapshot).computed()).isEqualTo(1);
            }
            var reader = new LucenePageCacheReader(cache);
            var allowed = new AtomicReference<>(Set.of("ndv"));
            var stopped = new AtomicBoolean();
            var denied = new AtomicBoolean();
            var released = new ArrayList<PageKey>();
            var searches = new AtomicInteger();
            var holdings = new ProgramMcpTools.Holdings() {
                @Override public Set<String> sources() { return Set.of("ndv", "secret"); }
                @Override public StoredProgramSearch.Snapshot search(Set<String> sources, BooleanSupplier cancelled) {
                    searches.incrementAndGet();
                    var builder = new StoredProgramSearch(this::page, this::analyses);
                    for (String source : sources) {
                        builder.addSource(source, true, visit -> reader.sourceHeaders(source, null, 50).forEach(visit), cancelled);
                    }
                    return builder.snapshot();
                }
                @Override public Optional<DeliveredPage> page(PageKey key) {
                    return denied.get() ? Optional.empty() : reader.page(key).map(DeliveredPage::cached);
                }
                @Override public List<Enrichments.View> analyses(DeliveredPage page) {
                    return enrichments.view(page.snapshot());
                }
                @Override public void authorize(DeliveredPage page) { released.add(page.header().key()); }
            };
            var tools = new StoredProgramChatTools(holdings, allowed::get, stopped::get);
            assertThat(tools.definitions()).extracting(AiChat.Tool::name)
                    .containsExactly("program_sources", "program_search", "program_analysis");
            assertThat(tools.run("program_sources", JSON.createObjectNode())).contains("ndv").doesNotContain("secret");
            assertThat(tools.run("program_search", JSON.readTree("{\"source\":\"secret\"}"))).doesNotContain("HIDDEN");
            assertThat(searches).hasValue(0);
            var response = JSON.readTree(tools.run("program_search", JSON.readTree(
                    "{\"language\":\"NATURAL\",\"mode\":\"CALLERS\",\"query\":\"BOOKING\"}")));
            assertThat(response.path("total").asInt()).isEqualTo(1);
            assertThat(released).containsExactly(program.key());
            var args = JSON.valueToTree(Map.of("source", "ndv", "pageId", program.key().pageId(),
                    "rawSha256", program.payload().rawSha256(), "section", "dependencies"));
            assertThat(tools.run("program_analysis", args)).contains("BOOKING").doesNotContain("HIDDEN");
            denied.set(true);
            assertThat(tools.run("program_analysis", args)).doesNotContain("BOOKING");
            denied.set(false);
            allowed.set(Set.of());
            assertThat(tools.definitions()).isEmpty();
            assertThat(tools.run("program_analysis", args)).doesNotContain("BOOKING");
            allowed.set(Set.of("ndv"));
            var delegateCancelled = new AtomicBoolean();
            ChatToolset delegate = new ChatToolset() {
                @Override public List<AiChat.Tool> definitions() { return List.of(new AiChat.Tool("other", "", "{}")); }
                @Override public String run(AiChat.ToolCall call) { return "other"; }
                @Override public String describe(AiChat.ToolCall call) { return "other"; }
                @Override public void cancel() { delegateCancelled.set(true); }
            };
            var attached = tools.attach(delegate, () -> true);
            assertThat(attached.definitions()).extracting(AiChat.Tool::name).contains("other", "program_search");
            assertThat(attached.run(new AiChat.ToolCall("1", "other", "{}"))).isEqualTo("other");
            assertThat(attached.run(new AiChat.ToolCall("2", "program_analysis", JSON.writeValueAsString(args)))).contains("BOOKING");
            attached.cancel();
            assertThat(delegateCancelled).isTrue();
            assertThat(attached.definitions()).extracting(AiChat.Tool::name).containsExactly("other");
            assertThat(attached.run(new AiChat.ToolCall("3", "program_analysis", JSON.writeValueAsString(args)))).doesNotContain("BOOKING");
        }
    }

    private static PageSnapshot snapshot(String source, String name, String text) {
        String resource = "ndv:host:2700/LIB/" + name;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        var metadata = new PageMetadata(id, resource, name, source, null, "date|size=20",
                null, null, PageMetadata.sorted(Map.of("ndv.library", "LIB")));
        var payload = new NativePayload(text.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider.SOURCE_TEXT_FORMAT);
        return PageSnapshot.of(source, metadata, payload, Instant.now());
    }
}
