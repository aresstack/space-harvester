package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.background.services.AnalysisService;
import com.aresstack.spaceharvester.application.background.services.ServicePlanner;
import com.aresstack.spaceharvester.application.background.services.ServiceStart;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** D1: die angemeldeten Analysedienste und ihre Starttypen ändern am heutigen Verhalten nichts. */
class HarvesterServicesTest {

    @Test
    void theFiveServicesAreDescribedWithTodaysStartTypes() {
        AtomicBoolean imagesAutomatic = new AtomicBoolean(false);
        AtomicBoolean glossaryAutomatic = new AtomicBoolean(false);
        var jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), Runnable::run,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                java.time.Clock.systemUTC());
        ServicePlanner planner = Harvester.services(
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app"), imagesAutomatic::get,
                glossaryAutomatic::get, null, key -> Optional.empty(), null, null, null);

        assertThat(planner.services()).extracting(AnalysisService::id).containsExactlyInAnyOrder("index", "images",
                "statements", "glossary", "knowledge-graph");
        assertThat(planner.services()).as("ohne Bilder-Lauf nur Beschreibungen")
                .allSatisfy(service -> assertThat(service.kind()).isNull());
        assertThat(planner.service("statements").orElseThrow().start().get()).isEqualTo(ServiceStart.MANUAL);
        assertThat(planner.service("glossary").orElseThrow().start().get()).as("terms.glossary.automatic aus")
                .isEqualTo(ServiceStart.MANUAL);
        glossaryAutomatic.set(true);
        assertThat(planner.service("glossary").orElseThrow().start().get()).isEqualTo(ServiceStart.AUTOMATIC);
        assertThat(planner.service("images").orElseThrow().start().get()).isEqualTo(ServiceStart.MANUAL);
        imagesAutomatic.set(true);
        assertThat(planner.service("images").orElseThrow().start().get()).isEqualTo(ServiceStart.AUTOMATIC);
        assertThat(planner.service("knowledge-graph").orElseThrow().prerequisites())
                .containsExactly("statements", "images");
        jobs.close();
    }

    @Test
    void theMissingPagesOfTheStatementAnalysisAreGroupedPerSpace() {
        var missing = new com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.Missing(
                com.aresstack.spaceharvester.application.snapshot.PageSet.of(java.util.Map.of("wiki",
                        java.util.Set.of("a1", "a2", "b1"))), 4);

        var grouped = Harvester.missingPerSpace(missing, key -> Optional.of(key.pageId().startsWith("a")
                ? "ALPHA" : "BETA"));

        assertThat(grouped.pages()).containsOnly(
                java.util.Map.entry(new com.aresstack.spaceharvester.application.background.services.MissingPages
                        .Scope("wiki", "ALPHA"), java.util.Set.of("a1", "a2")),
                java.util.Map.entry(new com.aresstack.spaceharvester.application.background.services.MissingPages
                        .Scope("wiki", "BETA"), java.util.Set.of("b1")));
        assertThat(grouped.inaccessible()).as("„zu laden“ ist kein fehlender Zugriff").isZero();
        assertThat(grouped.toLoad()).as("„erst laden“ geht an den Planer weiter (W5)").isEqualTo(4);
    }

    @Test
    void withTheClosingPartTheKnowledgeGraphHasItsKindAndFixesItsParametersAtStart() {
        var jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), Runnable::run,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                java.time.Clock.systemUTC());
        var statements = org.mockito.Mockito.mock(
                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.class);
        org.mockito.Mockito.when(statements.selected())
                .thenReturn(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL);
        var graph = new com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs(statements, () -> { });

        ServicePlanner planner = Harvester.services(
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app"), () -> false,
                () -> false, null, key -> Optional.empty(), null, null, graph);

        AnalysisService knowledgeGraph = planner.service("knowledge-graph").orElseThrow();
        assertThat(knowledgeGraph.kind())
                .isEqualTo(com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND);
        assertThat(knowledgeGraph.prerequisites()).containsExactly("statements", "images");
        assertThat(knowledgeGraph.parameters().of(com.aresstack.spaceharvester.application.access.AccessContext
                .technicalClient("app"))).containsEntry("owner", "TECHNICAL_CLIENT:app")
                .containsEntry("analyzer", "model").containsKey("caller");
        jobs.close();
    }
}
