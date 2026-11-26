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
