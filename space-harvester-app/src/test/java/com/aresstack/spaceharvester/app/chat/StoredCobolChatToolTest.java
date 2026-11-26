package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredCobolChatToolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOURCE = "ftp";
    private static final String CALL = "{\"quelle\":\"ftp\",\"art\":\"CALLERS_HIERARCHY\",\"suchtext\":\"B\"}";

    @Test
    void releasesAllDisplayedHierarchyEvidenceIncludingCopybooksAndNeverSearchesForbiddenSources() {
        var holdings = new Holdings();
        var allowed = new AtomicReference<>(Set.of(SOURCE));
        var released = new ArrayList<PageKey>();
        var tool = new StoredCobolChatTool(holdings, allowed::get, () -> false, (space, key) -> released.add(key));
        assertThat(tool.run(JSON.readTree("{}"))).contains("ftp").doesNotContain("secret");
        assertThat(tool.run(JSON.readTree("{\"quelle\":\"secret\"}"))).doesNotContain("APP.COBOL");
        assertThat(holdings.searches).isZero();
        assertThat(released).isEmpty();
        assertThat(tool.run(JSON.readTree(CALL))).contains("APP.COBOL(A)", "APP.COBOL(B)", "RESOLVED_IN_SNAPSHOT");
        assertThat(released).containsExactlyInAnyOrder(holdings.a.key(), holdings.b.key(), holdings.copy.key());
        allowed.set(Set.of());
        assertThat(tool.run(JSON.readTree(CALL))).doesNotContain("APP.COBOL");
        assertThat(holdings.searches).isEqualTo(1);
    }

    @Test
    void rejectsStaleEvidenceBeforeAnyReleaseAndSuppressesResultsWhenReleaseFails() {
        var holdings = new Holdings();
        var released = new ArrayList<PageKey>();
        var tool = new StoredCobolChatTool(holdings, () -> Set.of(SOURCE), () -> false,
                (space, key) -> released.add(key));
        holdings.pages.remove(holdings.copy.key());
        assertThat(tool.run(JSON.readTree(CALL))).doesNotContain("APP.COBOL");
        assertThat(released).isEmpty();
        holdings.pages.put(holdings.copy.key(), delivered(holdings.copy));
        var failing = new StoredCobolChatTool(holdings, () -> Set.of(SOURCE), () -> false,
                (space, key) -> { throw new IllegalStateException("Release failed"); });
        assertThatThrownBy(() -> failing.run(JSON.readTree(CALL))).isInstanceOf(IllegalStateException.class);
        ChatToolset wrapped = failing.attach(new EmptyTools(), () -> true);
        assertThat(wrapped.run(new AiChat.ToolCall("1", StoredCobolChatTool.NAME, CALL))).doesNotContain("APP.COBOL");
        wrapped.cancel();
        int searches = holdings.searches;
        assertThat(wrapped.run(new AiChat.ToolCall("2", StoredCobolChatTool.NAME, CALL))).doesNotContain("APP.COBOL");
        assertThat(holdings.searches).isEqualTo(searches);
    }

    private static final class Holdings implements StoredCobolChatTool.Holdings {
        private final PageSnapshot a = snapshot("a", "APP.COBOL(A)");
        private final PageSnapshot b = snapshot("b", "APP.COBOL(B)");
        private final PageSnapshot copy = snapshot("copy", "APP.CPY(CUSTOMER)");
        private final Map<PageKey, DeliveredPage> pages = new LinkedHashMap<>();
        private int searches;
        private Holdings() {
            for (var snapshot : List.of(a, b, copy)) pages.put(snapshot.key(), delivered(snapshot));
        }
        @Override public Set<String> sources() { return Set.of(SOURCE, "secret"); }
        @Override public Optional<DeliveredPage> page(PageKey key) { return Optional.ofNullable(pages.get(key)); }
        @Override public StoredCobolSearch.Snapshot search(String source, BooleanSupplier cancelled) {
            searches++;
            var aFact = fact(CobolSearch.Kind.PROGRAM, "A", "A", a);
            var bFact = fact(CobolSearch.Kind.PROGRAM, "B", "B", b);
            var call = fact(CobolSearch.Kind.CALL, "B", "A", a);
            List<StoredCobolSearch.Origin> aEvidence = List.of(origin(a), origin(copy));
            List<StoredCobolSearch.Origin> bEvidence = List.of(origin(b));
            return new StoredCobolSearch.Snapshot(new CobolSearch(List.of(aFact, bFact, call)), SOURCE, 2, 0, 0, 0, 0,
                    Map.of(aFact.member(), aEvidence, bFact.member(), bEvidence,
                            aFact.member() + "#A@2", aEvidence, bFact.member() + "#B@2", bEvidence));
        }
    }

    private static CobolSearch.Fact fact(CobolSearch.Kind kind, String name, String program, PageSnapshot snapshot) {
        String path = snapshot.metadata().title();
        return new CobolSearch.Fact(kind, name, program, path, path, 2, "revision",
                Map.of("SOURCE_ID", SOURCE, "PAGE_ID", snapshot.key().pageId()), false);
    }
    private static StoredCobolSearch.Origin origin(PageSnapshot snapshot) {
        return new StoredCobolSearch.Origin(snapshot.key(), snapshot.payload().rawSha256());
    }
    private static DeliveredPage delivered(PageSnapshot snapshot) {
        return new DeliveredPage(snapshot.header(), snapshot, null, false, null, null);
    }
    private static PageSnapshot snapshot(String id, String path) {
        var metadata = new PageMetadata(id, "ftp:host/" + path, path, "partition", null, "revision",
                null, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(SOURCE, metadata, new NativePayload(path.getBytes(StandardCharsets.UTF_8),
                "text/plain", NativePayload.DOCUMENT_TEXT), Instant.now());
    }

    private static final class EmptyTools implements ChatToolset {
        @Override public List<AiChat.Tool> definitions() { return List.of(); }
        @Override public String run(AiChat.ToolCall call) { return ""; }
        @Override public String describe(AiChat.ToolCall call) { return ""; }
    }
}
