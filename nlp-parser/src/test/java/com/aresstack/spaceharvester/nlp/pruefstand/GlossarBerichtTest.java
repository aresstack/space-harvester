package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.TermText;
import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Resolution;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Begriffe aus den Aussagen (ADR 0032, FL5a) über die Originaltexte des Prüfstands: die Aussagen der Kette („heute“)
 * gehen wie gespeicherte Analysen in {@link GlossaryDerivation}; gemessen gegen die von Hand vorab festgelegten
 * Erwartungen {@code begriffe.tsv} (Einordnung) und {@code beziehungen.tsv} (Ist-Beziehungen) je Textsatz. Bericht in
 * {@code build/reports/pruefstand/glossar.md}.
 *
 * <p>Einordnung: nur Bericht. Ist-Beziehungen mit Art (Begleiter des Rollennomens): je Entwicklungssatz genau
 * festgehalten in {@code beziehungen-stand.tsv} (schlechter = Rückschritt, besser = anheben); „falsche Art“ und
 * „KEINE verletzt“ sind Fehler, „nicht gefunden“ ist eine Lücke. Beim Kontrollsatz nur Bericht mit Zahlen und
 * Kennungen, nie Ableitung oder Parse, nie festgehalten. Zeilen zu Texten, die im Prüfstand noch fehlen, zählen
 * getrennt.
 */
class GlossarBerichtTest {

    /** Zahlen der Ist-Beziehungen eines Textsatzes. */
    record Beziehungen(int erwartet, int richtig, int falscheArt, int nichtGefunden, int keine, int keineVerletzt,
                       int nichtVorgegeben) {

        int[] werte() {
            return new int[] {erwartet, richtig, falscheArt, nichtGefunden, keine, keineVerletzt, nichtVorgegeben};
        }
    }

    @Test
    void begriffeUndIstBeziehungenGegenDieErwartung() throws IOException {
        Map<String, int[]> stand = stand();
        List<String> verstoesse = new ArrayList<>();
        int festgehalten = 0;
        StringBuilder bericht = new StringBuilder("# Begriffe aus dem Prüfstand (heute, FL5a)\n\n");
        int gemessen = 0;
        for (Textsatz satz : Textsatz.alle()) {
            String begriffe = optional(Textsatz.BASIS + satz.name() + "/begriffe.tsv");
            if (begriffe == null) {
                continue;
            }
            List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
            Set<String> texte = new HashSet<>();
            for (Text text : satz.texte()) {
                ObjectCentricAnalysis analysis = new ObjectCentricExtraction(ExtractionSettings.defaults())
                        .analyze(satz.analyse(satz.varianten().get(0), text));
                pages.add(page(satz.name(), text.name(), analysis));
                texte.add(text.name());
            }
            GlossaryDerivation.Result result = GlossaryDerivation.of(pages, () -> false);
            boolean kontrolle = "kontrolle".equals(satz.rolle());
            bericht.append("## ").append(satz.name()).append(" (").append(satz.rolle()).append(")\n\n")
                    .append(result.pages()).append(" Texte, ").append(result.terms().size()).append(" Begriffe, ")
                    .append(result.statements()).append(" gezählte Aussagen; Zustandsaussagen ohne Ist-Zustand: ")
                    .append(result.notPresent()).append(".\n\n");
            einordnung(bericht, result, Textsatz.tsv(begriffe), kontrolle);
            if (!kontrolle) {
                bericht.append("Alle Begriffe der Ableitung (Seiten/Erwähnungen): ").append(result.terms().stream()
                        .map(t -> t.term() + " " + t.importance().pages() + "/" + t.importance().mentions())
                        .collect(Collectors.joining(", "))).append("\n\n");
            }
            String beziehungen = optional(Textsatz.BASIS + satz.name() + "/beziehungen.tsv");
            if (beziehungen != null) {
                Beziehungen ist = beziehungen(bericht, result, Textsatz.tsv(beziehungen), texte, kontrolle);
                if (!kontrolle) {
                    int[] soll = stand.get(satz.name());
                    if (soll == null) {
                        verstoesse.add("kein Stand für " + satz.name() + " in beziehungen-stand.tsv, ist "
                                + java.util.Arrays.toString(ist.werte()));
                    } else {
                        festgehalten++;
                        if (!java.util.Arrays.equals(soll, ist.werte())) {
                            verstoesse.add(satz.name() + ": soll " + java.util.Arrays.toString(soll) + ", ist "
                                    + java.util.Arrays.toString(ist.werte()));
                        }
                    }
                }
            }
            gemessen++;
        }
        assertTrue(gemessen > 0, "keine Erwartung gefunden");
        Path report = Path.of("build", "reports", "pruefstand", "glossar.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.toString(), StandardCharsets.UTF_8);
        System.out.println(bericht);
        assertTrue(verstoesse.isEmpty(), "Ist-Beziehungen weichen von beziehungen-stand.tsv ab (schlechter = "
                + "Rückschritt, besser = anheben):\n" + String.join("\n", verstoesse));
        assertEquals(stand.size(), festgehalten, "jede Zeile in beziehungen-stand.tsv gehört zu einem Entwicklungssatz");
    }

    /**
     * {@code beziehungen-stand.tsv}: Textsatz, erwartet, richtig, falsche Art, nicht gefunden, KEINE, verletzt, nicht
     * vorgegeben.
     */
    private static Map<String, int[]> stand() {
        Map<String, int[]> result = new java.util.LinkedHashMap<>();
        for (String[] f : Textsatz.tsv(Textsatz.resource(Textsatz.BASIS + "beziehungen-stand.tsv"))) {
            int[] werte = new int[7];
            for (int i = 0; i < werte.length; i++) {
                werte[i] = Integer.parseInt(f[i + 1].strip());
            }
            result.put(f[0].strip(), werte);
        }
        return result;
    }

    private static void einordnung(StringBuilder bericht, GlossaryDerivation.Result result, List<String[]> zeilen,
                                   boolean kontrolle) {
        int richtig = 0;
        int unklar = 0;
        int nichtGefunden = 0;
        List<String> falsch = new ArrayList<>();
        List<String> fehlend = new ArrayList<>();
        StringBuilder einzeln = new StringBuilder();
        for (String[] zeile : zeilen) {
            GlossaryDerivation.Term term = result.term(zeile[0]);
            String erwartet = zeile[1].strip();
            if (term == null) {
                nichtGefunden++;
                fehlend.add(zeile[0]);
                continue;
            }
            String ist = term.category().name();
            if (ist.equals(erwartet)) {
                richtig++;
            } else if (term.category() == GlossaryDerivation.Category.UNKLAR) {
                unklar++;
            } else {
                falsch.add(zeile[0]);
            }
            if (!kontrolle) {
                einzeln.append("| ").append(zeile[0]).append(" | ").append(erwartet).append(" | ").append(ist)
                        .append(" | ").append(term.rule()).append(" |\n");
            }
        }
        bericht.append("**Einordnung:** ").append(zeilen.size()).append(" erwartet, richtig ").append(richtig)
                .append(", UNKLAR gelassen ").append(unklar).append(", **FALSCH ").append(falsch.size())
                .append("**, nicht gefunden ").append(nichtGefunden).append(".\n\n");
        if (!falsch.isEmpty()) {
            bericht.append("FALSCH eingeordnet: ").append(String.join(", ", falsch)).append("\n\n");
        }
        if (!fehlend.isEmpty()) {
            bericht.append("Nicht gefunden: ").append(String.join(", ", fehlend)).append("\n\n");
        }
        if (!kontrolle) {
            bericht.append("| Begriff | erwartet | abgeleitet | Regel |\n|---|---|---|---|\n").append(einzeln)
                    .append('\n');
        }
    }

    private static Beziehungen beziehungen(StringBuilder bericht, GlossaryDerivation.Result result,
                                           List<String[]> zeilen, Set<String> texte, boolean kontrolle) {
        int richtig = 0;
        int erwartet = 0;
        int keineEingehalten = 0;
        int keine = 0;
        int ohneText = 0;
        List<String> fehlt = new ArrayList<>();
        List<String> falscheArt = new ArrayList<>();
        List<String> verletzt = new ArrayList<>();
        Set<String> vorgegeben = new HashSet<>();
        for (String[] zeile : zeilen) {
            String kennung = zeile[0].strip();
            if (!texte.contains(kennung.substring(0, 1))) {
                ohneText++;
                continue;
            }
            String nomen = zeile[2].strip();
            String art = zeile[3].strip();
            List<GlossaryDerivation.Use> ist = new ArrayList<>();
            boolean eigenschaft = false;
            for (String traeger : zeile[1].split("\\|")) {
                GlossaryDerivation.Term term = result.term(traeger.strip());
                if (term == null) {
                    continue;
                }
                term.isA().stream().filter(use -> TermText.normalize(use.lemma()).equals(TermText.normalize(nomen)))
                        .forEach(ist::add);
                eigenschaft |= term.properties().stream().anyMatch(use -> use.lemma().equalsIgnoreCase(nomen));
                vorgegeben.add(term.normalized() + "\u0000" + TermText.normalize(nomen));
            }
            if ("KEINE".equals(art)) {
                keine++;
                if (!ist.isEmpty() || eigenschaft) {
                    verletzt.add(kennung);
                } else {
                    keineEingehalten++;
                }
                continue;
            }
            erwartet++;
            if (ist.isEmpty()) {
                fehlt.add(kennung);
            } else if (ist.stream().allMatch(use -> use.relation() != null && use.relation().name().equals(art))) {
                richtig++;
            } else {
                falscheArt.add(kennung);
            }
        }
        int zusaetzlich = 0;
        for (GlossaryDerivation.Term term : result.terms()) {
            for (GlossaryDerivation.Use use : term.isA()) {
                if (!vorgegeben.contains(term.normalized() + "\u0000" + TermText.normalize(use.lemma()))) {
                    zusaetzlich++;
                }
            }
        }
        bericht.append("**Ist-Beziehungen:** ").append(erwartet).append(" erwartet, richtig ").append(richtig)
                .append(", **falsche Art ").append(falscheArt.size()).append("** (Fehler), nicht gefunden ")
                .append(fehlt.size()).append(" (Lücke); KEINE ").append(keine).append(", eingehalten ")
                .append(keineEingehalten).append(", **verletzt ").append(verletzt.size())
                .append("** (Fehler); nicht vorgegebene Ist-Beziehungen ").append(zusaetzlich)
                .append(ohneText > 0 ? "; Zeilen zu fehlenden Texten " + ohneText : "").append(".\n\n");
        if (!falscheArt.isEmpty()) {
            bericht.append("Falsche Art: ").append(String.join(", ", falscheArt)).append("\n\n");
        }
        if (!verletzt.isEmpty()) {
            bericht.append("KEINE verletzt: ").append(String.join(", ", verletzt)).append("\n\n");
        }
        if (!fehlt.isEmpty()) {
            bericht.append("Nicht gefunden: ").append(String.join(", ", fehlt)).append("\n\n");
        }
        if (!kontrolle) {
            bericht.append("Ist-Beziehungen der Ableitung: ").append(result.terms().stream()
                    .flatMap(t -> t.isA().stream().map(u -> t.term() + " → " + u.lemma() + " (" + u.relation() + ")"))
                    .collect(Collectors.joining(", "))).append("\n\n");
        }
        return new Beziehungen(erwartet, richtig, falscheArt.size(), fehlt.size(), keine, verletzt.size(),
                zusaetzlich);
    }

    private static String optional(String path) throws IOException {
        try (InputStream input = GlossarBerichtTest.class.getResourceAsStream(path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Die Analyse wie gespeichert: Sätze, Erwähnungen, Aussagen mit Stufe und Merkmalen. */
    private static KnowledgeGraph.AnalysedPage page(String satz, String text, ObjectCentricAnalysis analysis) {
        List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        for (Sentence sentence : analysis.document().sentences()) {
            sentences.add(new ParsedStatements.Sentence(sentence.index(), sentence.start(), sentence.end()));
        }
        List<ParsedStatements.Mention> mentions = new ArrayList<>();
        for (Mention mention : analysis.mentions()) {
            mentions.add(new ParsedStatements.Mention(mention.id(), mention.sentence(), mention.start(), mention.end(),
                    mention.text(), mention.headForm(), mention.lemma(),
                    ParsedStatements.MentionKind.valueOf(mention.kind().name()),
                    mention.resolution() == Resolution.UNRESOLVED, mention.cases()));
        }
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        for (Statement statement : analysis.statements()) {
            List<ParsedStatements.Argument> arguments = new ArrayList<>();
            for (Argument argument : statement.arguments()) {
                arguments.add(new ParsedStatements.Argument(ParsedStatements.Role.valueOf(argument.role().name()),
                        argument.mention(), argument.relation(), argument.marker(),
                        ParsedStatements.Priority.valueOf(argument.priority().name()),
                        ParsedStatements.Basis.valueOf(argument.basis().name()), argument.certain()));
            }
            String lemma = statement.predicate().lemma() == null ? statement.predicate().text()
                    : statement.predicate().lemma();
            StatementFeatures.Features features = StatementFeatures.assess(analysis, statement);
            statements.add(new ParsedStatements.Statement(statement.id(), statement.sentence(), 0, 1,
                    statement.predicate().text(), lemma, ParsedStatements.Voice.valueOf(statement.voice().name()),
                    ParsedStatements.Clause.valueOf(statement.clause().name()), true, arguments,
                    statement.findings().stream().map(Enum::name).collect(Collectors.toSet()), 0,
                    ParsedStatements.Quality.valueOf(StatementQuality.assess(analysis, statement).level().name()),
                    List.of(), ParsedStatements.Review.UNREVIEWED, new ParsedStatements.Features(
                    features.negated() ? ParsedStatements.Negation.NEGATED : ParsedStatements.Negation.AFFIRMED,
                    ParsedStatements.Modality.valueOf(features.modality().name()),
                    ParsedStatements.Temporality.valueOf(features.temporality().name()),
                    features.article() == null ? null : ParsedStatements.Article.valueOf(features.article().name()))));
        }
        PageKey key = new PageKey(satz, text);
        PageStatementAnalysis stored = new PageStatementAnalysis(key, null, text, "1", "sha256:" + "0".repeat(64),
                "pruefstand", "1", Instant.EPOCH, new ParsedStatements(sentences, mentions, statements, List.of(),
                List.of(), null));
        return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, null, text,
                PageStatements.State.CURRENT, null), stored);
    }
}
