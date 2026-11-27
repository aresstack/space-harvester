package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A role's tools are the intersection of its catalog and what the host offers now, never more. */
public class RoleToolCatalogTest {

    private static final McpToolHandler NOOP = new McpToolHandler() {
        public McpToolResult invoke(McpToolCall call) {
            return McpToolResult.ok("ok");
        }
    };

    private static McpToolContribution tool(String name, McpToolParameter... parameters) {
        return McpToolContribution.of(name, "does " + name, NOOP, parameters);
    }

    @Test
    public void selectionIsTheIntersectionInCatalogOrder() {
        RoleToolCatalog catalog = RoleToolCatalog.of("source_list", "document_read", "artifact_read");
        List<McpToolContribution> offered = Arrays.asList(
                tool("artifact_read"), tool("source_accept"), tool("source_list"));

        List<McpToolContribution> selected = catalog.select(offered);

        assertEquals(2, selected.size());
        assertEquals("source_list", selected.get(0).getName());
        assertEquals("artifact_read", selected.get(1).getName());
        assertTrue(catalog.allows("document_read"));
        assertFalse("a tool outside the catalog is never handed to the role", catalog.allows("source_accept"));
    }

    @Test
    public void anEmptyCatalogSelectsNothing() {
        assertTrue(RoleToolCatalog.none().select(Arrays.asList(tool("research_status"))).isEmpty());
        assertTrue(RoleToolCatalog.of().isEmpty());
    }

    @Test
    public void duplicateNamesCollapse() {
        assertEquals(Arrays.asList("a", "b"), RoleToolCatalog.of("a", "b", "a").getToolNames());
    }

    @Test
    public void toolDefinitionsCarryAJsonObjectSchemaOfTheFlatParameters() {
        RoleToolCatalog catalog = RoleToolCatalog.of("artifact_read");
        List<ToolDefinition> definitions = catalog.toolDefinitions(Arrays.asList(tool("artifact_read",
                McpToolParameter.string("name", true, "the \"artifact\" name"),
                McpToolParameter.integer("limit", false, ""),
                McpToolParameter.enumeration("mode", false, "", Arrays.asList("full", "head")))));

        assertEquals(1, definitions.size());
        assertEquals("artifact_read", definitions.get(0).getName());
        assertEquals("does artifact_read", definitions.get(0).getDescription());
        assertEquals("{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"the \\\"artifact\\\" name\"},"
                        + "\"limit\":{\"type\":\"integer\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"full\",\"head\"]}},"
                        + "\"required\":[\"name\"]}",
                definitions.get(0).getParametersSchemaJson());
    }
}
