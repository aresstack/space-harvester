package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 15: a tool a phase change withdrew answers as a state change, not as "Unknown tool". */
public class ContributionToolInvokerStateChangeTest {

    private static McpToolContribution tool(String name) {
        return McpToolContribution.of(name, name, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("ok");
            }
        });
    }

    private static String failure(ContributionToolInvoker invoker, String name) throws Exception {
        try {
            invoker.call(name, Collections.<String, Object>emptyMap());
        } catch (ToolInvoker.ToolFailure expected) {
            return expected.getMessage();
        }
        fail(name + " must fail");
        return null;
    }

    @Test
    public void aWithdrawnToolIsAStateChangeAndANeverOfferedOneStaysUnknown() throws Exception {
        ContributionToolInvoker invoker = new ContributionToolInvoker(Collections.singletonList(
                tool("request_research")));
        Map<String, Object> none = Collections.emptyMap();
        assertEquals("ok", invoker.call("request_research", none));

        invoker.setTools(Collections.singletonList(tool("research_status"))); // the phase changed
        String withdrawn = failure(invoker, "request_research");
        assertTrue(withdrawn, withdrawn.startsWith(ContributionToolInvoker.STATE_CHANGED_PREFIX));
        assertTrue(withdrawn, withdrawn.contains("Not allowed in the current state"));
        assertEquals("Unknown tool: invented_tool", failure(invoker, "invented_tool"));
    }
}
