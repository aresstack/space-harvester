package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wendet die Regeln in fester Reihenfolge auf eine UD-Analyse an: Erwähnungen ({@link Mentions}), Kernrelation
 * ({@link PrimaryRelationExtraction}), ergänzende Rollen ({@link SupplementaryRoles}), optional der Kasus-Rückfall
 * ({@link CaseFallback}) und nominale Bezüge ({@link NominalAttachments}). Zustandslos und threadsicher.
 */
public final class ObjectCentricExtraction {

    private final ExtractionSettings settings;

    public ObjectCentricExtraction(ExtractionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ObjectCentricAnalysis analyze(ParsedDocument document) {
        Mentions mentions = Mentions.of(document);
        List<Statement> statements = new ArrayList<>();
        for (Statement statement : PrimaryRelationExtraction.extract(document, mentions)) {
            Statement extended = SupplementaryRoles.add(document, mentions, statement);
            statements.add(settings.caseFallback() ? CaseFallback.apply(document, mentions, extended) : extended);
        }
        List<NominalAttachment> attachments = NominalAttachments.extract(document, mentions);
        List<Mention> ordered = new ArrayList<>(mentions.all());
        ordered.sort(Comparator.comparingInt(Mention::sentence).thenComparingInt(Mention::head));
        return new ObjectCentricAnalysis(document, ordered, statements, attachments);
    }
}
