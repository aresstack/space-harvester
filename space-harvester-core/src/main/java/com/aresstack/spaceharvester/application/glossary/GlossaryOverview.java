package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Teilzahlen der Begriffsableitung für die Anzeige des Fachglossars (ADR 0032, FL5b): je Aufrufer gerechnet, nichts
 * gespeichert. Die Liste liefert je Begriff eines Geltungsbereichs die Wichtigkeit ohne Belege, damit der Reiter nach
 * Wichtigkeit sortieren kann; die Einzelheiten je Begriff liefern Verhalten, Eigenschaften und Ist-Beziehungen mit
 * Belegen. Gerechnet wird nur über die Seiten mit Aussagenanalyse, die der Aufrufer lesen darf; jeder Beleg ist also für
 * ihn lesbar.
 *
 * <p>Verknüpft wird über den Suchwert: {@link GlossaryDerivation.Term#normalized()} ist
 * {@link GlossaryEntry#normalized()} desselben Begriffs.
 */
public final class GlossaryOverview {

    private final Function<AccessContext, Collection<KnowledgeGraph.AnalysedPage>> analysed;
    private final Supplier<GlossaryRuleSettings> settings;

    /**
     * @param analysed die Seiten mit Aussagenanalyse, die ein Aufrufer lesen darf ({@code KnowledgeGraph#analysed})
     * @param settings Regeln der Begriffsableitung ({@code space-harvester.glossary.rules.*}), je Aufruf gelesen
     */
    public GlossaryOverview(Function<AccessContext, Collection<KnowledgeGraph.AnalysedPage>> analysed,
                            Supplier<GlossaryRuleSettings> settings) {
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Ein Begriff der Liste, ohne Belege.
     *
     * @param term       Wortlaut (häufigste Schreibweise)
     * @param normalized Suchwert, gleich {@link GlossaryEntry#normalized()}
     * @param importance Teilzahlen der Wichtigkeit
     * @param category   Einordnung nach Regeln
     * @param rule       Regel der Einordnung
     */
    public record Row(String term, String normalized, GlossaryDerivation.Importance importance,
                      GlossaryDerivation.Category category, GlossaryDerivation.CategoryRule rule) {
    }

    /**
     * Die Liste eines Geltungsbereichs, nach Wichtigkeit ({@link GlossaryDerivation.Importance#ORDER}) absteigend.
     *
     * @param rows       Begriffe
     * @param pages      ausgewertete Seiten und Dokumente
     * @param stalePages übersprungene veraltete Analysen
     */
    public record Overview(List<Row> rows, int pages, int stalePages) {

        public Overview {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Teilzahlen aller Begriffe eines Geltungsbereichs.
     *
     * @param sourceId Quelle oder {@code null} für alle lesbaren Seiten
     * @param spaceKey Space der Quelle oder {@code null}
     * @throws CancellationException sobald {@code cancelled} zutrifft
     */
    public Overview importance(AccessContext caller, String sourceId, String spaceKey, BooleanSupplier cancelled) {
        GlossaryDerivation.Result result = derive(caller, sourceId, spaceKey, cancelled);
        return new Overview(result.terms().stream().map(term -> new Row(term.term(), term.normalized(),
                term.importance(), term.category(), term.rule())).toList(), result.pages(), result.stalePages());
    }

    /**
     * Einzelheiten eines Begriffs im Geltungsbereich mit Belegen, die der Aufrufer lesen darf, oder leer, wenn die
     * Ableitung den Begriff dort nicht kennt.
     *
     * @param term Wortlaut oder Suchwert
     */
    public Optional<GlossaryDerivation.Term> term(AccessContext caller, String sourceId, String spaceKey, String term,
                                                  BooleanSupplier cancelled) {
        Objects.requireNonNull(term, "term");
        return Optional.ofNullable(derive(caller, sourceId, spaceKey, cancelled).term(TermText.normalize(term)));
    }

    private GlossaryDerivation.Result derive(AccessContext caller, String sourceId, String spaceKey,
                                             BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        if (sourceId == null && spaceKey != null) {
            throw new IllegalArgumentException("spaceKey nur mit sourceId");
        }
        return GlossaryDerivation.of(inScope(analysed.apply(caller), sourceId, spaceKey), cancelled,
                GlossaryDerivation.Rules.of(settings.get()));
    }

    /** Die Seiten eines Geltungsbereichs: Quelle ({@code null} alle) und optional Space. */
    static List<KnowledgeGraph.AnalysedPage> inScope(Collection<KnowledgeGraph.AnalysedPage> pages, String sourceId,
                                                     String spaceKey) {
        return pages.stream()
                .filter(page -> sourceId == null || page.page().key().sourceId().equals(sourceId))
                .filter(page -> spaceKey == null || spaceKey.equals(page.page().spaceKey()))
                .toList();
    }
}
