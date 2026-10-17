package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Handgeschriebene Gold-Analysen aus den Testressourcen; sie stehen für Parserausgaben, damit Regeln ohne Modell prüfbar sind. */
final class Gold {

    static final DocumentRef REF = new DocumentRef("test", "gold", "1");

    private Gold() {
    }

    static ParsedDocument load(String name) {
        try (InputStream input = Gold.class.getResourceAsStream(name)) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return ConllU.read(REF, text, List.of(Component.gold("dependencies", name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectCentricAnalysis analyze(String name) {
        return new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(load(name));
    }

    /** Wortlaut der Erwähnung eines Beteiligten. */
    static String text(ObjectCentricAnalysis analysis, Argument argument) {
        return argument == null ? null : analysis.mention(argument.mention()).text();
    }
}
