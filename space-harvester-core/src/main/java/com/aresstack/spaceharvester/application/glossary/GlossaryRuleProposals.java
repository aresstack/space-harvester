package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Macht aus der Begriffsableitung ({@link GlossaryDerivation}) die Vorschläge der Regel für das Glossar (ADR 0032,
 * FL5b). Rein: liest die übergebenen Seiten eines Geltungsbereichs, schreibt nichts; der Auftrag gibt das Ergebnis an
 * {@link Glossary#propose(com.aresstack.spaceharvester.application.terms.RelationOrigin, String,
 * com.aresstack.spaceharvester.application.terms.RelationScope, String, List, Set)} weiter.
 *
 * <p>Grenzen: Belege stammen nur aus Seiten, nicht aus Anhängen (Positionen eines Anhangs passen nicht auf
 * {@link RelationEvidence}). Sie dürfen von Seiten ohne KI-Freigabe stammen, denn die Regel ist kein Modell; dem
 * Modell (FL5c) zeigt der Auftrag nur Belege freigegebener Seiten. Belege kommen nur aus dem Original (Verhalten,
 * Eigenschaften, Ist-Beziehungen), nie aus der Vorstufe. Ein Begriff ohne Beleg wird nicht vorgeschlagen. Wichtigkeit
 * wird nicht gespeichert, sie ordnet nur, welche {@code maxTerms} Begriffe vorgeschlagen werden; die übrigen bleiben,
 * wie sie sind ({@link Batch#retained()}).
 */
public final class GlossaryRuleProposals {

    /** Standard für die Höchstzahl der Vorschläge je Geltungsbereich und Lauf. */
    public static final int DEFAULT_MAX_TERMS = GlossaryRuleSettings.DEFAULT_MAX_TERMS;

    private GlossaryRuleProposals() {
    }

    /**
     * Vorschläge eines Laufs.
     *
     * @param proposals  Vorschläge mit Belegen und Einordnung der Regel, nach Wichtigkeit
     * @param retained   Suchwerte der Begriffe über {@code maxTerms}: ihre Einträge bleiben, wie sie sind
     * @param derivation die zugrunde liegende Auswertung (für Zahlen im Ergebnis des Auftrags)
     */
    public record Batch(List<Glossary.Proposal> proposals, Set<String> retained,
                        GlossaryDerivation.Result derivation) {

        public Batch {
            proposals = List.copyOf(proposals);
            retained = Set.copyOf(retained);
        }
    }

    /**
     * Vorschläge für die Seiten eines Geltungsbereichs.
     *
     * @param pages     Seiten des Geltungsbereichs (Quelle, optional Space), bereits nach Leserecht des Auftrags
     * @param maxTerms  höchstens so viele Vorschläge, mindestens 1
     * @param cancelled Abbruch, je Seite und je Begriff geprüft
     * @throws CancellationException sobald {@code cancelled} zutrifft
     */
    public static Batch of(Collection<KnowledgeGraph.AnalysedPage> pages, int maxTerms, BooleanSupplier cancelled) {
        return of(pages, GlossaryDerivation.Rules.DEFAULT, maxTerms, cancelled);
    }

    /** Vorschläge mit den Regeln der Einstellung {@code space-harvester.glossary.rules.*}. */
    public static Batch of(Collection<KnowledgeGraph.AnalysedPage> pages, GlossaryRuleSettings settings,
                           BooleanSupplier cancelled) {
        return of(pages, GlossaryDerivation.Rules.of(settings), settings.maxTerms(), cancelled);
    }

    /** Wie {@link #of(Collection, int, BooleanSupplier)} mit diesen Wortlisten. */
    public static Batch of(Collection<KnowledgeGraph.AnalysedPage> pages, GlossaryDerivation.Rules rules,
                           int maxTerms, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        if (maxTerms < 1) {
            throw new IllegalArgumentException("maxTerms muss mindestens 1 sein");
        }
        GlossaryDerivation.Result derivation = GlossaryDerivation.of(pages, cancelled, rules);
        List<Glossary.Proposal> proposals = new ArrayList<>();
        Set<String> retained = new LinkedHashSet<>();
        for (GlossaryDerivation.Term term : derivation.terms()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            List<RelationEvidence> evidence = evidence(term);
            if (evidence.isEmpty()) {
                continue;
            }
            if (proposals.size() >= maxTerms) {
                retained.add(term.normalized());
                continue;
            }
            proposals.add(new Glossary.Proposal(term.term(), evidence, classification(term), null));
        }
        return new Batch(proposals, retained, derivation);
    }

    /**
     * Einordnung der Regel: Kategorie und Regel-Kennung als Grund ({@code TECHNICAL_HEAD}), ohne Grund bei
     * {@link GlossaryDerivation.Category#UNKLAR}.
     */
    static GlossaryEntry.Classification classification(GlossaryDerivation.Term term) {
        return new GlossaryEntry.Classification(term.category(), GlossaryEntry.ClassifiedBy.RULE,
                term.rule() == GlossaryDerivation.CategoryRule.NONE ? null : term.rule().name());
    }

    /** Bis zu {@link GlossaryDerivation#MAX_EVIDENCE} Seitenbelege aus dem Original, ohne doppelte Sätze. */
    static List<RelationEvidence> evidence(GlossaryDerivation.Term term) {
        Set<RelationEvidence> found = new LinkedHashSet<>();
        Stream.of(term.acts(), term.undergoes(), term.properties(), term.isA(), term.instances())
                .flatMap(List::stream)
                .filter(use -> use.origin() == ParsedStatements.Origin.ORIGINAL)
                .flatMap(use -> use.evidence().stream())
                .filter(item -> item.document().isPage())
                .map(item -> new RelationEvidence(item.document().page().sourceId(), item.document().page().pageId(),
                        item.start(), item.end(), item.inputSha256()))
                .forEach(item -> {
                    if (found.size() < GlossaryDerivation.MAX_EVIDENCE) {
                        found.add(item);
                    }
                });
        return List.copyOf(found);
    }
}
