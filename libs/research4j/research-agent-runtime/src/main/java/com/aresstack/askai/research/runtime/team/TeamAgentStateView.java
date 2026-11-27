package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The host's live research state as the TeamAgent sees it for one turn: the current phase and run-state ids
 * (mirrored from the research-control MCP {@code research_status}, never owned here) and the set of command
 * names the host's state machine currently allows. The model is TOLD this so it can propose only a legal next
 * step; the engine still validates any proposed command against this allowed set before it may run — the model
 * never invents a state or a command.
 */
public final class TeamAgentStateView {

    private final String phaseId;
    private final String stateId;
    private final Set<String> allowedCommands;
    /** The accepted research sources the host reported this turn (best-effort context), or "" when none. */
    private final String sourcesSummary;

    public TeamAgentStateView(String phaseId, String stateId, List<String> allowedCommands) {
        this(phaseId, stateId, allowedCommands, "");
    }

    public TeamAgentStateView(String phaseId, String stateId, List<String> allowedCommands,
                              String sourcesSummary) {
        this.phaseId = phaseId == null ? "" : phaseId;
        this.stateId = stateId == null ? "" : stateId;
        Set<String> commands = new LinkedHashSet<String>();
        if (allowedCommands != null) {
            for (String command : allowedCommands) {
                if (command != null && !command.trim().isEmpty()) {
                    commands.add(command.trim());
                }
            }
        }
        this.allowedCommands = Collections.unmodifiableSet(commands);
        this.sourcesSummary = sourcesSummary == null ? "" : sourcesSummary.trim();
    }

    /** The accepted-sources summary the model sees this turn (source ids + titles), or "" when none. */
    public String getSourcesSummary() {
        return sourcesSummary;
    }

    /** An immutable copy carrying the accepted-sources summary for the model's per-turn context. */
    public TeamAgentStateView withSources(String sources) {
        return new TeamAgentStateView(phaseId, stateId, new ArrayList<String>(allowedCommands), sources);
    }

    public String getPhaseId() {
        return phaseId;
    }

    public String getStateId() {
        return stateId;
    }

    public Set<String> getAllowedCommands() {
        return allowedCommands;
    }

    public boolean allows(String command) {
        return command != null && allowedCommands.contains(command.trim());
    }

    /** A stable, comma-separated rendering of the allowed commands for the model's per-turn context. */
    public String allowedCommandsLine() {
        if (allowedCommands.isEmpty()) {
            return "(none)";
        }
        List<String> ordered = new ArrayList<String>(allowedCommands);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ordered.get(i));
        }
        return sb.toString();
    }
}
