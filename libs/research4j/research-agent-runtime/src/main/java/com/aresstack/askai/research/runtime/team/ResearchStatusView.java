package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the research-control {@code research_status} MCP result — the host's authoritative
 * {@code "phaseId/stateId rev=N"} status line (possibly wrapped in a {@code ToolResult} rendering) — into the
 * {@link TeamAgentStateView} the {@link ResearchTeamAgent} is given each turn. The host stays the only state
 * authority; this is a read-only mirror. The allowed-command set is carried separately once the status line is
 * extended to publish it (until then it is empty, so the model is told "(none)" and proposes no command).
 */
public final class ResearchStatusView {

    /** The first {@code word/word} pair in the status line is {@code phaseId/stateId}. */
    private static final Pattern PHASE_STATE =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)");
    /** The optional {@code cmds=A,B,C} field lists the live allowed workflow commands. */
    private static final Pattern COMMANDS = Pattern.compile("cmds=([A-Za-z0-9_,]+)");

    private ResearchStatusView() {
    }

    /** Neutral view for when the status could not be read (unreachable endpoint). */
    public static TeamAgentStateView empty() {
        return new TeamAgentStateView("", "", Collections.<String>emptyList());
    }

    /** Parse phase/state AND the {@code cmds=...} allowed set (if present) from the status text. */
    public static TeamAgentStateView parse(String statusText) {
        if (statusText == null) {
            return empty();
        }
        List<String> commands = new ArrayList<String>();
        Matcher commandMatcher = COMMANDS.matcher(statusText);
        if (commandMatcher.find()) {
            for (String command : commandMatcher.group(1).split(",")) {
                if (!command.isEmpty()) {
                    commands.add(command);
                }
            }
        }
        return parse(statusText, commands);
    }

    /** Parse phase/state and attach an explicitly-known allowed-command set. */
    public static TeamAgentStateView parse(String statusText, List<String> allowedCommands) {
        if (statusText == null) {
            return empty();
        }
        Matcher matcher = PHASE_STATE.matcher(statusText);
        if (!matcher.find()) {
            return new TeamAgentStateView("", "", copy(allowedCommands));
        }
        return new TeamAgentStateView(matcher.group(1), matcher.group(2), copy(allowedCommands));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList() : new ArrayList<String>(values);
    }
}
