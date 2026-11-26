package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CobolSearchToolsTest {
    @Test
    void exposesMultiHopHierarchyFromTheSameReleasedSnapshot() {
        var first = hierarchyFact(CobolSearch.Kind.PROGRAM, "A", "LIB(A)", 1);
        var second = hierarchyFact(CobolSearch.Kind.PROGRAM, "B", "LIB(B)", 1);
        var third = hierarchyFact(CobolSearch.Kind.PROGRAM, "C", "LIB(C)", 1);
        var search = new CobolSearch(List.of(first, second, third,
                hierarchyFact(CobolSearch.Kind.CALL, "B", "LIB(A)", 5),
                hierarchyFact(CobolSearch.Kind.CALL, "C", "LIB(B)", 7)));
        var current = new CobolSearchTools.Snapshot(search, "3/3 Member");
        var tools = new CobolSearchTools(new FtpChatTools(null, null),
                () -> Optional.of(current), () -> true);
        var call = new AiChat.ToolCall("2", CobolSearchTools.SEARCH,
                "{\"art\":\"CALLEES_HIERARCHY\",\"suchtext\":\"A\"}");
        assertThat(tools.run(call)).contains("Fundstellen: 3", "LIB(A):5", "LIB(B):7",
                "HIERARCHY_DEPTH=2", "RESOLVED_IN_SNAPSHOT");
    }

    private static CobolSearch.Fact hierarchyFact(CobolSearch.Kind kind, String name, String member, int line) {
        return new CobolSearch.Fact(kind, name, member, member, member, line, "r", Map.of(), false);
    }

    @Test
    void searchesTheSameSnapshotAndHonorsRevocation() {
        var fact = new CobolSearch.Fact(CobolSearch.Kind.COPY_USAGE, "CUST", "DEMO", "USER.COBOL(DEMO)",
                "USER.COBOL(DEMO)", 5, "r", Map.of("RESOLVED_SOURCE", "USER.COPY(CUST)"), false);
        var search = new CobolSearch(List.of(fact));
        var current = new AtomicReference<>(new CobolSearchTools.Snapshot(search, "1/1 Member"));
        var allowed = new AtomicBoolean(true);
        var tools = new CobolSearchTools(new FtpChatTools(null, null),
                () -> Optional.ofNullable(current.get()), allowed::get);
        var call = new AiChat.ToolCall("1", CobolSearchTools.SEARCH,
                "{\"art\":\"COPY_USAGE\",\"suchtext\":\"cust\"}");
        assertThat(search.search(CobolSearch.Mode.COPY_USAGE, "cust", 25, 0).hits()).containsExactly(fact);
        assertThat(tools.definitions()).extracting(AiChat.Tool::name)
                .contains(CobolSearchTools.SEARCH, FtpChatTools.READ);
        assertThat(tools.run(call)).contains("1/1 Member", "Fundstellen: 1", "USER.COBOL(DEMO):5", "USER.COPY(CUST)");
        allowed.set(false);
        assertThat(tools.run(call)).doesNotContain("USER.COPY").contains("nicht mehr aktiv");
        allowed.set(true);
        current.set(null);
        assertThat(tools.run(call)).contains("Keine freigegebene").doesNotContain("USER.COPY");
    }
}
