package com.aresstack.knowledge4j.model.role;

import com.aresstack.knowledge4j.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A role's tools are the intersection of its catalog and what the host offers now, never more. */
public class RoleToolCatalogTest {

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, "does " + name, "{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    public void selectionIsTheIntersectionInCatalogOrder() {
        RoleToolCatalog catalog = RoleToolCatalog.of("source_list", "document_read", "artifact_read");
        List<ToolDefinition> offered = Arrays.asList(
                tool("artifact_read"), tool("source_accept"), tool("source_list"));

        List<ToolDefinition> selected = catalog.select(offered);

        assertEquals(2, selected.size());
        assertEquals("source_list", selected.get(0).getName());
        assertEquals("artifact_read", selected.get(1).getName());
        assertTrue(catalog.allows("document_read"));
        assertFalse(catalog.allows("source_accept"), "a tool outside the catalog is never handed to the role");
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
}
