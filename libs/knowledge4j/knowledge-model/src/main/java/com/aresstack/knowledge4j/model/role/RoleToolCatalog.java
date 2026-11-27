package com.aresstack.knowledge4j.model.role;

import com.aresstack.knowledge4j.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tool subset a {@link ModelRole} may use (least privilege per role). The role declares tool NAMES; the
 * concrete {@link ToolDefinition}s come from the host per session and per state, because visibility depends on
 * the live run. Binding the catalog to what the host offers right now yields the role's actual tool list: the
 * intersection, in the role's declared order. A declared tool the host does not offer is simply absent, it is
 * never synthesized, and a tool outside the catalog is never handed to the role.
 */
public final class RoleToolCatalog {

    private static final RoleToolCatalog NONE = new RoleToolCatalog(Collections.<String>emptyList());

    private final List<String> toolNames;

    private RoleToolCatalog(List<String> toolNames) {
        Set<String> unique = new LinkedHashSet<String>();
        for (String name : toolNames) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("tool names must not be blank");
            }
            unique.add(name.trim());
        }
        this.toolNames = Collections.unmodifiableList(new ArrayList<String>(unique));
    }

    /** A role without tools (a pure judgement or generation call). */
    public static RoleToolCatalog none() {
        return NONE;
    }

    public static RoleToolCatalog of(String... toolNames) {
        return toolNames.length == 0 ? NONE : new RoleToolCatalog(java.util.Arrays.asList(toolNames));
    }

    public static RoleToolCatalog of(List<String> toolNames) {
        return toolNames == null || toolNames.isEmpty() ? NONE : new RoleToolCatalog(toolNames);
    }

    /** The declared tool names, in order. */
    public List<String> getToolNames() {
        return toolNames;
    }

    public boolean isEmpty() {
        return toolNames.isEmpty();
    }

    public boolean allows(String toolName) {
        return toolName != null && toolNames.contains(toolName.trim());
    }

    /**
     * The role's tools among what the host offers now: the declared names that {@code offered} contains, in
     * the catalog's order. Never more than the catalog, never a tool the host did not offer.
     */
    /**
     * The tools the role may use now: the intersection of this catalog and the definitions the host offers in
     * this session, in catalog order. A tool outside the catalog is never handed to the role, a catalog name the
     * host does not offer is simply absent. The host maps its own tool registry (MCP, built-in, ...) to
     * {@link ToolDefinition}s; the role core never sees that registry.
     */
    public List<ToolDefinition> select(List<ToolDefinition> offered) {
        if (offered == null || offered.isEmpty() || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolDefinition> selected = new ArrayList<ToolDefinition>();
        for (String name : toolNames) {
            for (ToolDefinition definition : offered) {
                if (definition != null && name.equals(definition.getName())) {
                    selected.add(definition);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(selected);
    }

    @Override
    public String toString() {
        return toolNames.toString();
    }
}
