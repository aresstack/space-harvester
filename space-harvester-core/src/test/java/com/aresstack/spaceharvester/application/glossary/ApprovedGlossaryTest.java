package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermText;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Geprüfte Glossare (Angelo 23.11.2026): mitgelieferte Begriffe kommen als bestätigte, manuelle, globale Einträge ins
 * vorhandene Fachglossar, mit Quelle; Entscheidungen der Bearbeiter gehen vor; KI und Regel legen keinen zweiten
 * ungeprüften Eintrag desselben Begriffs an.
 */
class ApprovedGlossaryTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-23T00:00:00Z");
    private static final RelationScope WIKI = new RelationScope("wiki", null);
    private static final ApprovedGlossary SMALL = new ApprovedGlossary("test-glossar", "Erfundenes Glossar",
            "https://example.org/glossar.pdf", List.of(
            new ApprovedGlossary.Entry("Zahlstelle", null, "Erfundene Erläuterung.", 3, List.of(), List.of()),
            new ApprovedGlossary.Entry("Annahmebeauftragte", "Annahmebeauftragte (pl.)", "Erfunden.", 4, List.of(),
                    List.of()),
            new ApprovedGlossary.Entry("Datenhaus", null, "Aus dem Glossar.", 5, List.of(), List.of())));

    @TempDir
    Path directory;

    private GlossaryStore store;
    private Glossary glossary;

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source());
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source()) : Optional.empty();
        }
    };

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    private void open() {
        store = new FileGlossaryStore(directory);
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.empty(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private GlossaryEntry global(String term) {
        return store.find(GlossaryEntry.idOf(term, RelationScope.GLOBAL)).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        open();
    }

    @Test
    void bundledGlossaryIsReadWithSourceAndVerbatimWording() {
        List<ApprovedGlossary> bundled = ApprovedGlossary.bundled();

        assertThat(bundled).hasSize(1);
        ApprovedGlossary anlage = bundled.get(0);
        assertThat(anlage.url())
                .isEqualTo("https://zrb.bund.de/fileadmin/downloads/vorschriften/vv-bho/rds_buchf_zv/Anlage_1_Glossar.pdf");
        assertThat(anlage.entries()).hasSize(146);
        assertThat(anlage.entries()).allSatisfy(entry -> {
            assertThat(entry.explanation()).isNotBlank();
            assertThat(entry.page()).isBetween(1, 20);
            assertThat(entry.term()).doesNotContain("(");
        });
        assertThat(anlage.reference(TermText.normalize("Annahmebeauftragte"))).get()
                .extracting(ApprovedGlossary.Reference::wording, ApprovedGlossary.Reference::page)
                .containsExactly("Annahmebeauftragte (pl.)", 2);
        assertThat(anlage.entries()).filteredOn(entry -> entry.term().equals("Anordnung")).singleElement()
                .extracting(ApprovedGlossary.Entry::explanation).asString()
                .startsWith("Mit einer Anordnung autorisiert eine bewirtschaftende Stelle")
                .endsWith("Der Begriff Kassenanordnung wird synonym verwendet.");
    }

    @Test
    void adoptingCreatesConfirmedManualGlobalEntriesOnceAndSurvivesARestart() {
        Glossary.Adoption first = glossary.adopt(SMALL);

        assertThat(first.added()).isEqualTo(3);
        GlossaryEntry zahlstelle = global("Zahlstelle");
        assertThat(zahlstelle.origin()).isEqualTo(RelationOrigin.MANUAL);
        assertThat(zahlstelle.status()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(zahlstelle.explanation()).isEqualTo("Erfundene Erläuterung.");
        assertThat(zahlstelle.createdBy()).isEqualTo("glossary:test-glossar");
        assertThat(zahlstelle.classification().category()).isEqualTo(GlossaryDerivation.Category.FACHLICH);
        assertThat(zahlstelle.classification().by()).isEqualTo(GlossaryEntry.ClassifiedBy.MANUAL);
        assertThat(ApprovedGlossary.reference(List.of(SMALL), zahlstelle)).get()
                .extracting(ApprovedGlossary.Reference::url, ApprovedGlossary.Reference::page)
                .containsExactly("https://example.org/glossar.pdf", 3);
        assertThat(glossary.confirmedTerms(WIKI)).as("global gilt in jeder Quelle").contains("zahlstelle");

        open();
        Glossary.Adoption again = glossary.adopt(SMALL);

        assertThat(again).isEqualTo(new Glossary.Adoption(0, 0, 3));
        assertThat(global("Zahlstelle")).isEqualTo(zahlstelle);
    }

    @Test
    void editorDecisionsWin() {
        glossary.adopt(SMALL);
        GlossaryEntry rejected = glossary.reject(EDITOR, global("Zahlstelle").id(), null);
        glossary.remove(EDITOR, global("Annahmebeauftragte").id(), null);
        glossary.explain(EDITOR, global("Datenhaus").id(), "Eigene Erklärung", null);

        Glossary.Adoption again = glossary.adopt(SMALL);

        assertThat(again.added()).isZero();
        assertThat(global("Zahlstelle").status()).as("abgelehnt bleibt abgelehnt").isEqualTo(ReviewStatus.REJECTED);
        assertThat(global("Zahlstelle").revision()).isEqualTo(rejected.revision());
        assertThat(store.find(GlossaryEntry.idOf("Annahmebeauftragte", RelationScope.GLOBAL)))
                .as("entfernt kommt nicht wieder").isEmpty();
        assertThat(global("Datenhaus").explanation()).isEqualTo("Eigene Erklärung");
    }

    @Test
    void anExistingEntryIsConfirmedKeepsItsOriginAndGetsTheExplanationOnlyIfItHasNone() {
        glossary.add(EDITOR, new Glossary.NewEntry("Datenhaus", RelationScope.GLOBAL, null, List.of()));

        Glossary.Adoption adoption = glossary.adopt(SMALL);

        assertThat(adoption.added()).isEqualTo(2);
        assertThat(adoption.updated()).isEqualTo(1);
        GlossaryEntry datenhaus = global("Datenhaus");
        assertThat(datenhaus.createdBy()).isEqualTo(EDITOR.jobOwner());
        assertThat(datenhaus.explanation()).isEqualTo("Aus dem Glossar.");
        assertThat(datenhaus.classification().category()).isEqualTo(GlossaryDerivation.Category.FACHLICH);
    }

    @Test
    void runsDoNotProposeAnApprovedTermAgainInASource() {
        glossary.adopt(SMALL);
        RelationEvidence evidence = new RelationEvidence("wiki", "1", 0, 10, "sha256:" + "c".repeat(64));

        Glossary.ProposalRun run = glossary.propose(RelationOrigin.AI, "ki", WIKI, "fake:model@1",
                List.of(new Glossary.Proposal("Zahlstelle", List.of(evidence)),
                        new Glossary.Proposal("Förderantrag", List.of(evidence))), Set.of());

        assertThat(run.added()).isEqualTo(1);
        assertThat(run.suppressed()).isEqualTo(1);
        assertThat(store.find(GlossaryEntry.idOf("Zahlstelle", WIKI))).isEmpty();
        assertThat(store.find(GlossaryEntry.idOf("Förderantrag", WIKI))).isPresent();
    }
}
