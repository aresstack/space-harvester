package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolCall;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolParameter;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult;
import com.aresstack.spaceharvester.ai.AiChat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserChatToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void allBrowserToolsOfTheEnterpriseAiClientBecomeChatToolsWithValidSchemas() {
        BrowserChatTools tools = new BrowserChatTools(BrowserMcpTools.unbound());
        List<AiChat.Tool> definitions = tools.definitions();
        assertThat(definitions).extracting(AiChat.Tool::name).contains(BrowserMcpTools.NAVIGATE,
                BrowserMcpTools.READ, BrowserMcpTools.OPEN_MANY, BrowserMcpTools.ACT, BrowserMcpTools.CLOSE);
        for (AiChat.Tool tool : definitions) {
            assertThat(JSON.readTree(tool.parametersSchemaJson()).path("type").asString()).isEqualTo("object");
            assertThat(BrowserChatTools.describe(tool.name())).isNotEqualTo("Browser");
        }
        assertThat(tools.handles(BrowserMcpTools.NAVIGATE)).isTrue();
        assertThat(tools.handles("seite_lesen")).isFalse();
        assertThat(tools.run(BrowserMcpTools.READ, JSON.createObjectNode())).startsWith("Fehler: ");
    }

    @Test
    void argumentsArePassedAsTextAndListsLikeInTheEnterpriseAiClient() {
        AtomicReference<McpToolCall> seen = new AtomicReference<>();
        BrowserChatTools tools = new BrowserChatTools(List.of(McpToolContribution.of("browser_open_many", "",
                call -> {
                    seen.set(call);
                    return McpToolResult.ok("2 Seiten");
                }, McpToolParameter.stringArray("urls", false, "Adressen"),
                McpToolParameter.integer("max_chars", false, "Zeichen"))));
        String result = tools.run("browser_open_many",
                JSON.readTree("{\"urls\":[\"https://a.example\",\"https://b.example\"],\"max_chars\":500}"));
        assertThat(result).isEqualTo("2 Seiten");
        assertThat(seen.get().getStrings("urls")).containsExactly("https://a.example", "https://b.example");
        assertThat(seen.get().getInteger("max_chars", 0)).isEqualTo(500);
        assertThat(BrowserChatTools.schema(McpToolContribution.of("x", "", call -> McpToolResult.ok(""),
                McpToolParameter.stringArray("urls", true, "A \"B\""))))
                .contains("\"items\":{\"type\":\"string\"}").contains("\"required\":[\"urls\"]")
                .contains("A \\\"B\\\"");
    }
}
