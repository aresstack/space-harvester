package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Optional;

/**
 * Beleg einer fachlichen Aussage (ADR 0032, Fortschreibung vom 26.11.2026): die gemeinsame Abstraktion über
 * {@link TextEvidence Textbeleg} und {@link SourceLocationEvidence Quellstellenbeleg}.
 */
public sealed interface Evidence permits TextEvidence, SourceLocationEvidence {

    /**
     * Der Beleg einer gespeicherten Aussage: bei einer Aussage aus der Programmanalyse ihr Quellstellenbeleg, sonst der
     * Textbeleg ihres Originalsatzes (auch bei Vorstufe und Korrektur: Ein vereinfachter Satz ist nie Beleg).
     */
    static Evidence of(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement) {
        SourceLocationEvidence source = statement.derivation().source();
        return source != null ? source : TextEvidence.of(page, statement);
    }

    /** Art des Belegs. */
    Kind kind();

    /** Das Dokument, aus dem die Aussage stammt: die Seite des Satzes oder das Programm. */
    DocumentKey document();

    /** Revision dieses Dokuments, an der der Beleg gilt (Eingabe-Hash der Analyse bzw. Inhalts-Hash des Programms). */
    String revision();

    /**
     * Ob der Beleg selbst festlegt, dass er Ist oder Soll belegt. Ein Quellstellenbeleg belegt immer
     * {@link Bearing#IST}. Ein Textbeleg legt nichts fest: Ob ein Satz eine Vorschrift (Soll) oder einen Zustand (Ist)
     * beschreibt, folgt aus Aussage und Quelle, nicht aus der Belegart.
     */
    Optional<Bearing> bearing();

    /** Die Belegarten. */
    enum Kind {
        /** Originalsatz einer Seite; Prüfung durch Wortabgleich (ADR 0032). */
        TEXT,
        /** Codestellen eines Programms; Prüfung technisch und durch einen Menschen. */
        SOURCE_LOCATION
    }
}
