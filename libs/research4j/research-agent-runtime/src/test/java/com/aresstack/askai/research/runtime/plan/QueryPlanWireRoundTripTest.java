package com.aresstack.askai.research.runtime.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommand;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * Plan PR 13: pins the plan_queries wire across the process boundary — the HOST encoders (ui-plugin classes on this
 * test classpath) against the RUNTIME parser, and the RUNTIME renderer against the HOST decoder.
 */
public class QueryPlanWireRoundTripTest {

    static ConceptQueryPlan.Request sample() {
        return new ConceptQueryPlan.Request("de", "Plugins \"isolieren\" in Java",
                Arrays.asList(
                        new ConceptQueryPlan.Node("root", "Plugin-Isolation", Collections.singletonList(
                                "Plugin-Isolation"), "", "Java Plugin Isolation"),
                        new ConceptQueryPlan.Node("n1", "Classloader & Module", Arrays.asList("Plugin-Isolation",
                                "Classloader & Module"), "root", "Classloader Module Java")),
                Arrays.asList("java plugin isolation", "jpms layer"),
                Collections.singletonList("Spring Boot"), 3);
    }

    @Test
    public void aHostRequestSurvivesTheEnvelopeIntoTheRuntime() {
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire.planQueries("req-7",
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(sample()));
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_PLAN_QUERIES, command.getType());
        assertEquals("req-7", command.getRequestId());

        ConceptQueryPlan.Request parsed = QueryPlanWire.parseRequest(command.getRequest());
        assertEquals("de", parsed.getLanguage());
        assertEquals("Plugins \"isolieren\" in Java", parsed.getBookTitle());
        assertEquals(3, parsed.getMaxVariantsPerNode());
        assertEquals(Arrays.asList("java plugin isolation", "jpms layer"), parsed.getQueriesRun());
        assertEquals(Collections.singletonList("Spring Boot"), parsed.getBlacklist());
        assertEquals(2, parsed.getNodes().size());
        ConceptQueryPlan.Node child = parsed.getNodes().get(1);
        assertEquals("n1", child.getId());
        assertEquals("root", child.getParentId());
        assertEquals("Classloader & Module", child.getLabel());
        assertEquals(Arrays.asList("Plugin-Isolation", "Classloader & Module"), child.getPath());
        assertEquals("Classloader Module Java", child.getBaseQuery());
        assertEquals("", parsed.getNodes().get(0).getParentId());
    }

    @Test
    public void aLaterCycleRequestSurvivesWithItsCycleAndDomains() {
        ConceptQueryPlan.Request cycle = new ConceptQueryPlan.Request("de", "Plugins", Collections.singletonList(
                new ConceptQueryPlan.Node("n1", "Classloader", Arrays.asList("Plugins", "Classloader"), "root",
                        "Classloader Java", Arrays.asList("amazon.de", "ebay.de"))),
                Collections.singletonList("Classloader Java"), Collections.<String>emptyList(), 3, 3);
        ConceptQueryPlan.Request parsed = QueryPlanWire.parseRequest(
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(cycle));
        assertEquals(3, parsed.getCycle());
        assertEquals(Arrays.asList("amazon.de", "ebay.de"), parsed.getNodes().get(0).getFoundDomains());
        assertEquals(1, QueryPlanWire.parseRequest(
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(sample())).getCycle());
    }

    @Test
    public void aRuntimePlanSurvivesIntoTheHost() {
        ConceptQueryPlan.Result runtime = new ConceptQueryPlan.Result(Arrays.asList(
                new ConceptQueryPlan.NodePlan("root", "Java Plugin Isolation",
                        Arrays.asList("Plugin Sandbox Java", "Classloader Leck"), ""),
                new ConceptQueryPlan.NodePlan("n1", "Classloader Module Java", Collections.<String>emptyList(),
                        "invalid planner answer, base query only: no usable new query")));
        String line = com.aresstack.askai.research.runtime.loop.ResearchRunWire.queryPlan("req-7",
                QueryPlanWire.renderResult(runtime));
        assertEquals(com.aresstack.askai.research.agentwire.ResearchRunWire.TYPE_QUERY_PLAN,
                com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));

        ConceptQueryPlan.Result host = com.aresstack.askai.research.plan.QueryPlanWireCodec.decodeResult(
                QueryPlanWire.renderResult(runtime));
        assertEquals(2, host.getNodes().size());
        assertEquals(Arrays.asList("Java Plugin Isolation", "Plugin Sandbox Java", "Classloader Leck"),
                host.getNodes().get(0).queries());
        assertEquals(Collections.singletonList("Classloader Module Java"), host.getNodes().get(1).queries());
        assertEquals("invalid planner answer, base query only: no usable new query",
                host.getNodes().get(1).getNote());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMalformedRequestIsRefusedTyped() {
        QueryPlanWire.parseRequest("{\"nodes\":7}");
    }
}
