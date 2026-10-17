package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.extraction.Statement.Clause;
import com.aresstack.spaceharvester.nlp.extraction.Statement.Voice;
import java.util.ArrayList;
import java.util.List;

/**
 * Priorisierte Sicht für eine spätere KI-Schicht: zuerst die Kernrelationen, dann die übrigen Aussagen.
 * <p>
 * Kern ist eine Aussage nur, wenn sie aktiv ist, im Haupt- oder koordinierten Satz steht und Subjekt und
 * Akkusativobjekt aus eigenen Parserkanten hat, ohne Kasuskonflikt. Passiv, Teilsätze, übernommene oder heuristische
 * Beteiligte und Aussagen ohne Objekt bleiben nachrangig, gehen aber nicht verloren.
 */
public final class SalienceProjection {

    private SalienceProjection() {
    }

    public static Projection project(ObjectCentricAnalysis analysis) {
        List<CoreRelation> core = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        for (Statement statement : analysis.statements()) {
            Argument subject = statement.subject();
            Argument object = statement.object();
            if (statement.voice() == Voice.ACTIVE && statement.clause() != Clause.SUBORDINATE
                    && parserCertain(subject) && parserCertain(object)) {
                Mention subjectMention = analysis.mention(subject.mention());
                Mention objectMention = analysis.mention(object.mention());
                core.add(new CoreRelation(statement.id(), subjectMention.id(), subjectMention.text(),
                        statement.predicate().text(), statement.predicate().lemma(), objectMention.id(),
                        objectMention.text(), subjectMention.resolution() == Resolution.UNRESOLVED
                        || objectMention.resolution() == Resolution.UNRESOLVED));
            } else {
                secondary.add(statement.id());
            }
        }
        return new Projection(core, secondary);
    }

    private static boolean parserCertain(Argument argument) {
        return argument != null && argument.basis() == Basis.PARSER && argument.certain();
    }

    /**
     * @param core      Kernrelationen in Textreihenfolge
     * @param secondary IDs der übrigen Aussagen in Textreihenfolge
     */
    public record Projection(List<CoreRelation> core, List<String> secondary) {

        public Projection {
            core = List.copyOf(core);
            secondary = List.copyOf(secondary);
        }
    }
}
