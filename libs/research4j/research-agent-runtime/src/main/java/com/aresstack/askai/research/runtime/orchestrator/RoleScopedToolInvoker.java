package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool lane restricted to one role's {@link RoleToolCatalog} (ADR 0001, 4.2: permissions per role). A call
 * outside the catalog fails as a tool failure before it reaches the host, and the lane advertises only the
 * permitted names. The host's own gates (phase, run state, scope fence) still apply behind it.
 */
final class RoleScopedToolInvoker implements ToolInvoker {

    private final ToolInvoker lane;
    private final RoleToolCatalog catalog;

    RoleScopedToolInvoker(ToolInvoker lane, RoleToolCatalog catalog) {
        this.lane = lane;
        this.catalog = catalog;
    }

    @Override
    public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
        if (!catalog.allows(toolName)) {
            throw new ToolFailure("tool " + toolName + " is not permitted for this role " + catalog);
        }
        if (lane == null) {
            throw new EndpointUnavailable("no tool lane");
        }
        return lane.call(toolName, args);
    }

    @Override
    public List<String> toolNames() {
        List<String> permitted = new ArrayList<String>();
        if (lane != null) {
            for (String name : lane.toolNames()) {
                if (catalog.allows(name)) {
                    permitted.add(name);
                }
            }
        }
        return permitted;
    }
}
