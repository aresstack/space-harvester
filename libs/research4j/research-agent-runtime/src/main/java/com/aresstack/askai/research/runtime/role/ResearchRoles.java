package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.research.runtime.acquire.ModelPageReadinessJudge;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.scope.MainModelScopeAdviceChooser;
import com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfile;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The research agent's model roles (ADR 0001, 1.3 and 4.1). The conversation role is one role per phase
 * profile ({@link PhaseAssistantProfileRegistry}); the worker roles are declared next to the code that runs
 * them, so each prompt has exactly one home and stays byte for byte what it was before the role interface.
 * The narrator is not listed: it streams over the host's {@code AgentInferencePort}, not over a role chat.
 */
public final class ResearchRoles {

    /**
     * The neutral conversation role (the registry's fallback profile, bound to Concept). The session re-binds
     * it per turn to the live phase's slot ({@code phase.<id>}, plan 5.1) with
     * {@link ResearchRole#withSlot}; a profile's role declares the prompt, tools and output of its phase.
     */
    public static final ResearchRole CONVERSATION = PhaseAssistantProfileRegistry.defaults().fallback().getRole();

    /** The scope sweep's probe generator. */
    public static final ResearchRole SCOPE_PROBE_GENERATOR = MainModelScopeProbeGenerator.ROLE;

    /** The scope sweep's advice chooser. */
    public static final ResearchRole SCOPE_ADVICE_CHOOSER = MainModelScopeAdviceChooser.ROLE;

    /** The acquisition runner's page readiness judge. */
    public static final ResearchRole PAGE_READINESS_JUDGE = ModelPageReadinessJudge.ROLE;

    /** The book pipeline's concept query planner (plan PR 13) on {@code phase.research.planner}. */
    public static final ResearchRole QUERY_PLANNER =
            com.aresstack.askai.research.runtime.orchestrator.QueryPlanner.CONCEPT_ROLE;

    /**
     * The model-backed SERP layout repair. Its prompts are built per repair ticket by
     * {@code browser-search-analysis} (another module's contract), hence {@code CALLER}; the answer is a JSON
     * decision validated by that module's codec.
     */
    public static final ResearchRole SERP_LAYOUT_REPAIR = ResearchRole.builder("serp-layout-repair",
                    ResearchModelSlots.LAYOUT_REPAIR)
            .purpose("repairs the result layout of a search page the extractor could not read")
            .promptFromCaller()
            .output(RoleOutput.json("SearchLayoutRepairJson"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    private ResearchRoles() {
    }

    /** The worker roles in a fixed order (everything but the conversation). */
    public static List<ResearchRole> workers() {
        List<ResearchRole> roles = new ArrayList<ResearchRole>();
        roles.add(SCOPE_PROBE_GENERATOR);
        roles.add(SCOPE_ADVICE_CHOOSER);
        roles.add(PAGE_READINESS_JUDGE);
        roles.add(SERP_LAYOUT_REPAIR);
        roles.add(QUERY_PLANNER);
        return Collections.unmodifiableList(roles);
    }

    /**
     * The roles of the orchestrated run (ADR 0001, 4.2, lane F): the orchestrator and the worker roles it
     * delegates to. Kept apart from {@link #workers()}, which lists the single agent's tool-free roles.
     */
    public static List<ResearchRole> orchestration() {
        List<ResearchRole> roles = new ArrayList<ResearchRole>();
        roles.add(com.aresstack.askai.research.runtime.orchestrator.ResearchOrchestrator.ROLE);
        roles.add(com.aresstack.askai.research.runtime.orchestrator.WebResearchWorker.ROLE);
        // Lane G: the new worker roles (each can be switched off in the settings).
        roles.add(com.aresstack.askai.research.runtime.orchestrator.SearchPlannerWorker.ROLE);
        roles.add(com.aresstack.askai.research.runtime.orchestrator.SourceVerifierWorker.ROLE);
        roles.add(com.aresstack.askai.research.runtime.orchestrator.WriterWorker.ROLE);
        return Collections.unmodifiableList(roles);
    }

    /**
     * Every role of a session: one conversation role per phase profile of {@code profiles}, then the workers,
     * then the orchestration roles.
     */
    public static List<ResearchRole> all(PhaseAssistantProfileRegistry profiles) {
        List<ResearchRole> roles = new ArrayList<ResearchRole>();
        for (PhaseAssistantProfile profile : profiles.all()) {
            roles.add(profile.getRole());
        }
        roles.addAll(workers());
        roles.addAll(orchestration());
        return Collections.unmodifiableList(roles);
    }
}
