package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.modelport.CentralModelDirectories;
import com.aresstack.askai.modelport.OllamaModelPort;
import com.aresstack.askai.research.runtime.role.ResearchRoles;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The scoping conversation's transport on the WIRE: the real {@link PortMainModelChat} over the real
 * {@link OllamaModelPort} (ollama4j) against a recording fake Ollama {@code /api/chat}. It pins what the
 * scoping regression analysis had to prove at protocol level instead of on Java signatures:
 * <ul>
 *   <li>the request is semantically the former {@code HttpMainModelChatClient} body (removed with ADR 0001
 *       lane C, 08de6d9): same model, same messages in the same order with the same roles and contents, the
 *       same {@code format} schema (key order included — Ollama builds its grammar from it), the same
 *       {@code options} (temperature, num_predict, NO num_ctx) and NO {@code think}; the only differences are
 *       {@code stream:true} and {@code keep_alive};</li>
 *   <li>the streamed answer is reassembled completely and in order from {@code message.content} only —
 *       {@code message.thinking} never leaks into the text the scoping parser reads;</li>
 *   <li>the reassembled answer parses into the structured conceptAction the concept loop executes.</li>
 * </ul>
 */
public class ScopingTurnWireTest {

    private static final String MODEL = "gemma4:e2b";

    private HttpServer server;
    private final List<String> chatBodies = Collections.synchronizedList(new ArrayList<String>());
    private volatile List<String> streamLines = Collections.emptyList();
    private OllamaModelPort port;

    @Before
    public void startFakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            chatBodies.add(readBody(exchange));
            StringBuilder ndjson = new StringBuilder();
            for (String line : streamLines) {
                ndjson.append(line).append('\n');
            }
            respond(exchange, "application/x-ndjson", ndjson.toString());
        });
        server.createContext("/api/ps", exchange -> respond(exchange, "application/json", "{\"models\":[]}"));
        server.start();
        port = CentralModelDirectories.fixedModelPort("http://127.0.0.1:" + server.getAddress().getPort(),
                MODEL, Duration.ofSeconds(30));
    }

    @After
    public void stop() {
        if (port != null) {
            port.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void theScopingRequestIsTheFormerClientsBodyPlusStreamAndKeepAlive() {
        String answer = "{\"assistantMessage\":\"Gern!\",\"conceptAction\":{\"type\":\"none\"}}";
        streamLines = stream(Collections.<String>emptyList(), Collections.singletonList(answer));
        String schema = new ScopingPhaseOutputContract(true).outputSchemaJson();
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.system(TeamAgentPlaybook.scopingSystemPrompt(true, true)),
                ChatMessage.assistant("{\"assistantMessage\":\"Worüber möchtest du recherchieren?\"}"),
                ChatMessage.user("hasenhaltung \"artgerecht\"\nmit Zeilenumbruch und Umlauten äöüß"));

        MainModelChatResult result = PortMainModelChat.forRole(port, ResearchRoles.CONVERSATION,
                Duration.ofSeconds(30)).completeJson(messages, 0.4, 4096, schema);

        assertTrue(result.getDetail(), result.isOk());
        assertEquals(1, chatBodies.size());
        Map<String, Object> sent = object(chatBodies.get(0));
        Map<String, Object> legacy = object(legacyBody(MODEL, messages, 0.4, 4096, schema));

        assertEquals("the port streams; the former client did not", Boolean.TRUE, sent.remove("stream"));
        assertEquals(Boolean.FALSE, legacy.remove("stream"));
        assertEquals("keep_alive is the platform's warm-keeping (default 5m)", "5m", sent.remove("keep_alive"));
        assertFalse("no think field while the model's capabilities are unknown", sent.containsKey("think"));
        assertFalse("no tools on a scoping turn", sent.containsKey("tools"));
        assertEquals("research calls refuse an oversized prompt instead of cutting it (PR 17)", Boolean.FALSE,
                sent.remove("truncate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sentOptions = (Map<String, Object>) sent.get("options");
        assertEquals("the model's context on every call (plan 4.1, rule 1)", 32768,
                ((Number) sentOptions.remove("num_ctx")).intValue());
        assertEquals("everything else is the former request, field by field", legacy, withoutNulls(sent));

        @SuppressWarnings("unchecked")
        Map<String, Object> sentProperties = (Map<String, Object>) ((Map<String, Object>) sent.get("format"))
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaProperties = (Map<String, Object>) object(schema).get("properties");
        assertEquals("the grammar's property ORDER survives the ollama4j round trip",
                new ArrayList<String>(schemaProperties.keySet()), new ArrayList<String>(sentProperties.keySet()));
    }

    @Test
    public void theStreamedAnswerIsReassembledFromContentOnlyInOrder() {
        String answer = "{\"assistantMessage\":\"Gern helfe ich dir bei der Meerschweinchenhaltung. Ich lege "
                + "die ersten Bereiche an.\",\"conceptAction\":{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung und Sozialverhalten\",\"Ernährung und Futter\"]}}";
        streamLines = stream(Arrays.asList("Der Nutzer will ", "add_cards: Haltung, Ernährung"),
                chunks(answer, 7));

        MainModelChatResult result = PortMainModelChat.forRole(port, ResearchRoles.CONVERSATION,
                Duration.ofSeconds(30)).completeJson(Arrays.asList(ChatMessage.system("s"),
                ChatMessage.user("Meerschweinchen")), 0.4, 4096,
                new ScopingPhaseOutputContract(true).outputSchemaJson());

        assertTrue(result.getDetail(), result.isOk());
        assertEquals("every content fragment, in order, and nothing of the reasoning", answer, result.getText());
        ScopingAssistantOutputParser.Result parsed = ScopingAssistantOutputParser.parse(result.getText());
        assertTrue(parsed.getError(), parsed.isOk());
        ConceptAction action = parsed.getOutput().getConceptAction();
        assertNotNull("the structured channel carries the action", action);
        assertEquals(ConceptAction.Type.ADD_CARDS, action.getType());
        assertEquals("[\"Haltung und Sozialverhalten\",\"Ernährung und Futter\"]", action.getNamesJson());
        assertEquals(null, VisibleAssistantMessageValidator.actionLeakIn(
                parsed.getOutput().getAssistantMessage()));
    }

    @Test
    public void aScopingTurnOfTheTeamAgentSendsThePromptHistoryAndTheSchema() {
        streamLines = stream(Collections.<String>emptyList(), Collections.singletonList(
                "{\"assistantMessage\":\"Worum genau geht es dir?\",\"conceptAction\":{\"type\":\"none\"}}"));
        ResearchTeamAgent agent = new ResearchTeamAgent(PortMainModelChat.forRole(port,
                ResearchRoles.CONVERSATION, Duration.ofSeconds(30)),
                PhaseAssistantProfileRegistry.defaults(true, true), new PhaseContextAssembler());

        TeamAgentResult turn = agent.respond("hasenhaltung",
                new TeamAgentStateView("scoping", "running", Collections.<String>emptyList()));

        assertEquals(TeamAgentResult.Status.OK, turn.getStatus());
        Map<String, Object> sent = object(chatBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Object> wireMessages = (List<Object>) sent.get("messages");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) wireMessages.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) wireMessages.get(wireMessages.size() - 1);
        assertEquals("system", first.get("role"));
        assertTrue("the scoping prompt with the concept tools",
                String.valueOf(first.get("content")).contains("THE CONCEPT (conceptAction)"));
        assertEquals("user", last.get("role"));
        assertEquals("hasenhaltung", last.get("content"));
        assertEquals("the scoping grammar travels as the format object",
                object(new ScopingPhaseOutputContract(true).outputSchemaJson()), sent.get("format"));
    }

    // ------------------------------------------------------------------ the former client's body

    /**
     * The request body of the former {@code HttpMainModelChatClient.buildChatBody} (08de6d9^), verbatim in
     * behaviour: the reference the new transport is compared against.
     */
    static String legacyBody(String model, List<ChatMessage> messages, double temperature, int maxOutputTokens,
                             String schemaJson) {
        String formatValue = schemaJson == null || schemaJson.trim().isEmpty() ? "\"json\"" : schemaJson;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":\"").append(escape(model)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"format\":").append(formatValue).append(',');
        sb.append("\"options\":{\"temperature\":").append(temperature);
        if (maxOutputTokens > 0) {
            sb.append(",\"num_predict\":").append(maxOutputTokens);
        }
        sb.append("},");
        sb.append("\"messages\":[");
        boolean first = true;
        for (ChatMessage message : messages) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"role\":\"").append(message.getRole().wire()).append("\",\"content\":\"")
                    .append(escape(message.getContent())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ fake Ollama

    /** An Ollama chat stream: thinking chunks, then content chunks, then the done chunk. */
    private static List<String> stream(List<String> thinking, List<String> content) {
        List<String> lines = new ArrayList<String>();
        for (String delta : thinking) {
            lines.add("{\"model\":\"" + MODEL + "\",\"message\":{\"role\":\"assistant\",\"content\":\"\","
                    + "\"thinking\":\"" + escape(delta) + "\"},\"done\":false}");
        }
        for (String delta : content) {
            lines.add("{\"model\":\"" + MODEL + "\",\"message\":{\"role\":\"assistant\",\"content\":\""
                    + escape(delta) + "\"},\"done\":false}");
        }
        lines.add("{\"model\":\"" + MODEL + "\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":"
                + content.size() + ",\"eval_duration\":1000}");
        return lines;
    }

    private static List<String> chunks(String text, int size) {
        List<String> parts = new ArrayList<String>();
        for (int start = 0; start < text.length(); start += size) {
            parts.add(text.substring(start, Math.min(text.length(), start + size)));
        }
        return parts;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String json) {
        return (Map<String, Object>) MiniJson.parse(json);
    }

    /** ollama4j writes absent message fields as JSON null; Ollama treats null and absent alike. */
    @SuppressWarnings("unchecked")
    private static Object withoutNulls(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (entry.getValue() != null) {
                    copy.put(entry.getKey(), withoutNulls(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object element : (List<Object>) value) {
                copy.add(withoutNulls(element));
            }
            return copy;
        }
        return value;
    }
}
