package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpTestTools;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.mcp.solon.SolonMcpToolClientFactory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * ADR 0001, section 3.3: how a tool call is executed is a transport question. The SAME contributions run once
 * in-process through {@link ContributionToolInvoker} (our own roles) and once over the Solon loopback server
 * with the restored {@link McpToolClient} (external clients, a future remote agent); both must give the same
 * result: the same text on success, a tool failure on the same calls, an unavailable endpoint once it is gone.
 * Solon is a process-global singleton, so the whole matrix runs in one test against one server.
 */
public class ToolTransportParityTest {

    /** One call's outcome, transport-neutral: OK with the text, or the failure kind. */
    private static String outcome(ToolInvoker invoker, String tool, Map<String, Object> args) {
        return outcome(invoker, tool, args, new ArrayList<String>());
    }

    /** As above; a tool failure's message is added to {@code failures}. */
    private static String outcome(ToolInvoker invoker, String tool, Map<String, Object> args, List<String> failures) {
        try {
            return "OK " + invoker.call(tool, args);
        } catch (ToolInvoker.ToolFailure failure) {
            failures.add(failure.getMessage());
            return "TOOL_FAILURE";
        } catch (ToolInvoker.EndpointUnavailable gone) {
            return "ENDPOINT_UNAVAILABLE";
        }
    }

    /** The external-client lane as the loop would see it: the neutral client behind the loop's own port. */
    private static ToolInvoker overClient(final McpToolClient client) {
        return new ToolInvoker() {
            public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
                try {
                    return client.callTool(toolName, args);
                } catch (McpToolClient.McpToolCallException ex) {
                    if (ex.isEndpointUnavailable()) {
                        throw new EndpointUnavailable(ex.getMessage());
                    }
                    throw new ToolFailure(ex.getMessage());
                }
            }
        };
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return args;
    }

    private static List<McpToolContribution> contributions() {
        List<McpToolContribution> tools = new ArrayList<McpToolContribution>();
        tools.add(McpTestTools.ping());
        tools.add(McpTestTools.echo());
        // Typed arguments: integers and booleans must decode the same after a JSON round-trip.
        tools.add(McpToolContribution.of("page_window", "typed arguments", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("offset=" + call.getInteger("offset", -1)
                        + " limit=" + call.getInteger("limit", -1)
                        + " raw=" + call.getBoolean("raw", false));
            }
        }, McpToolParameter.integer("offset", true, "o"), McpToolParameter.integer("limit", false, "l"),
                McpToolParameter.bool("raw", false, "r")));
        // A multi-line result with quotes, markup characters and non-ASCII text, as findings and pages carry it.
        tools.add(McpToolContribution.of("page_text", "rich text", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("URL: https://example.org/a?b=1&c=<2> title=\"Grüße\"\n"
                        + "Zeile 2: Straße, café, 東京, 😀\n\tindented");
            }
        }));
        tools.add(McpToolContribution.of("empty", "empty result", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("");
            }
        }));
        // The three failure shapes of the contract: an error result, a policy refusal, a throwing handler.
        tools.add(McpToolContribution.of("source_accept", "error result", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.error("capture " + call.getString("capture_id") + " is unknown");
            }
        }, McpToolParameter.string("capture_id", true, "c")));
        tools.add(McpToolContribution.of("phase_advance", "policy refusal", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("Not allowed: the user owns phase transitions");
            }
        }));
        tools.add(McpToolContribution.of("broken", "throwing handler", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                throw new IllegalStateException("boom");
            }
        }));
        return tools;
    }

    @Test
    public void inProcessAndLoopbackDispatchGiveIdenticalResults() {
        List<McpToolContribution> tools = contributions();
        ContributionToolInvoker inProcess = new ContributionToolInvoker(tools);

        SolonMcpServerRuntime runtime = new SolonMcpServerRuntime();
        McpEndpointHandle handle = runtime.registerEndpoint(new McpEndpointDefinition("parity", "Parity"));
        runtime.updateTools(handle, tools);
        McpToolClient client = new SolonMcpToolClientFactory().connect(runtime.endpointUrl(handle), "streamable");
        ToolInvoker loopback = overClient(client);
        try {
            List<Object[]> calls = Arrays.asList(
                    new Object[]{"ping", args()},
                    new Object[]{"echo", args("text", "hello parity")},
                    new Object[]{"echo", args("text", "")},
                    new Object[]{"page_window", args("offset", 40, "limit", 10, "raw", true)},
                    new Object[]{"page_window", args("offset", 0)},
                    new Object[]{"page_text", args()},
                    new Object[]{"empty", args()},
                    new Object[]{"source_accept", args("capture_id", "cap-9")},
                    new Object[]{"phase_advance", args()},
                    new Object[]{"broken", args()});
            List<String> expected = new ArrayList<String>();
            List<String> actual = new ArrayList<String>();
            List<String> inProcessFailures = new ArrayList<String>();
            List<String> loopbackFailures = new ArrayList<String>();
            for (Object[] call : calls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) call[1];
                expected.add(call[0] + " -> " + outcome(inProcess, (String) call[0], arguments, inProcessFailures));
                actual.add(call[0] + " -> " + outcome(loopback, (String) call[0], arguments, loopbackFailures));
            }
            assertEquals(expected, actual);
            // Failure texts: each lane frames them its own way (in-process "Tool failed: boom", Solon's wire
            // "Error: boom"), but the tool's own message reaches the caller unchanged on both.
            assertEquals(Arrays.asList("capture cap-9 is unknown", "Not allowed: the user owns phase transitions",
                    "Tool failed: boom"), inProcessFailures);
            assertEquals(Arrays.asList("Error: capture cap-9 is unknown",
                    "Not allowed: the user owns phase transitions", "Error: boom"), loopbackFailures);
            // Pin the in-process side too, so "identical" cannot mean "identically wrong".
            assertEquals(Arrays.asList(
                    "ping -> OK pong",
                    "echo -> OK hello parity",
                    "echo -> OK ",
                    "page_window -> OK offset=40 limit=10 raw=true",
                    "page_window -> OK offset=0 limit=-1 raw=false",
                    "page_text -> OK URL: https://example.org/a?b=1&c=<2> title=\"Grüße\"\n"
                            + "Zeile 2: Straße, café, 東京, 😀\n\tindented",
                    "empty -> OK ",
                    "source_accept -> TOOL_FAILURE",
                    "phase_advance -> TOOL_FAILURE",
                    "broken -> TOOL_FAILURE"), expected);

            // The endpoint goes away with its session: both lanes report it as unavailable, not as a tool error.
            inProcess.close();
            runtime.unregisterEndpoint(handle);
            assertEquals(outcome(inProcess, "ping", Collections.<String, Object>emptyMap()),
                    outcome(loopback, "ping", Collections.<String, Object>emptyMap()));
        } finally {
            client.close();
            runtime.shutdown();
        }
    }
}
