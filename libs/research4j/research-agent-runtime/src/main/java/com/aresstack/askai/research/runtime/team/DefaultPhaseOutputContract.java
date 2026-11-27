package com.aresstack.askai.research.runtime.team;

/** The generic phase contract: the existing {@link TeamAgentTurnParser} producing a {@link TeamAgentTurn}. */
public final class DefaultPhaseOutputContract implements PhaseOutputContract {

    public PhaseParseResult parse(String rawModelText) {
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse(rawModelText);
        return result.isOk() ? PhaseParseResult.ok(result.getTurn()) : PhaseParseResult.fail(result.getError());
    }
}
