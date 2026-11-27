package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;

/**
 * THE seam that answers "is this search fachlich fertig?" — and nothing else. It is deliberately separate
 * from the two other kinds of ending, which are NOT its business:
 *
 * <pre>
 * CompletionPolicy      "Haben wir unser normales Suchziel erreicht?"        (this interface)
 * SafetyLimits          "Müssen wir wegen Ressourcen/Fehlern abbrechen?"     (ResearchRunBudget)
 * TraversalExhaustion   "Gibt es technisch noch etwas abzuarbeiten?"         (frontier state)
 * </pre>
 *
 * Historically the {@code WebSearchApplicationService} hard-wired a "genug?" heuristic
 * ({@code sufficientOr}: minimum sources AND minimum hosts) into every exhaustion/traversal ending, so a
 * budget stop could masquerade as SUFFICIENT_EVIDENCE and the completion semantics were untestable in
 * isolation. Implementations of this port make the choice explicit and swappable.
 */
public interface SearchCompletionPolicy {

    /**
     * Whether the run has reached its NORMAL, configured end. Checked at the run's central gate; when true
     * the run stops with {@link ResearchStopReason#SUFFICIENT_EVIDENCE} — the ONE "normal done" reason.
     */
    boolean isComplete(ResearchRunProgress progress);

    /**
     * Label an ending that was NOT the normal completion (budget exhausted, frontier dry). The default is
     * the honest identity: the fallback reason stays exactly what it says. Only the legacy autonomous
     * policy overrides this (its historical relabeling), and only until it gets its own slice.
     */
    default ResearchStopReason labelExhaustion(ResearchStopReason fallback,
                                               ResearchRunProgress progress) {
        return fallback;
    }
}
