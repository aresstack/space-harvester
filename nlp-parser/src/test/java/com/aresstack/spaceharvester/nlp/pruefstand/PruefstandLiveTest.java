package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.malt.MaltModelLoader;
import com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DependencyAnalysis;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Prüfstand mit der echten Kette (ADR 0032, FL0), nur mit {@code -PnlpModelDir=<Verzeichnis>} wie
 * {@code RealModelSmokeTest}. Prüft, dass jede gepinnte CoNLL-U genau der Ausgabe von OpenNLP + MaltParser für die
 * Eingabe der Variante entspricht; die tatsächlichen Ausgaben liegen in {@code build/reports/pruefstand/pins/} zum
 * Übernehmen, wenn sich die Vorverarbeitung bewusst ändert (der PR sagt das).
 *
 * <p>Mit {@code -PpruefstandSeiten=<Verzeichnis>} zusätzlich ein Lauf über echte Seiten ({@code *.txt}, {@code *.md}):
 * ausgegeben werden nur Zahlen (Seiten, Sätze, Aussagen je Satz, Anteile PLAUSIBLE/UNCERTAIN/INVALID), nie Inhalte.
 */
@EnabledIfSystemProperty(named = "nlp.parser.modelDir", matches = ".+")
class PruefstandLiveTest {

    private static DependencyAnalysis kette;

    @BeforeAll
    static void laden() throws IOException {
        kette = new DependencyAnalysis(OpenNlpGermanPreprocessor.fromClasspath(),
                MaltModelLoader.loadPinned(Path.of(System.getProperty("nlp.parser.modelDir"))));
    }

    @Test
    void pinsSindDieAusgabeDerEchtenKette() throws IOException {
        List<String> abweichungen = new ArrayList<>();
        Path ziel = Path.of("build", "reports", "pruefstand", "pins");
        for (Textsatz satz : Textsatz.alle()) {
            for (Textsatz.Variante variante : satz.varianten()) {
                for (Textsatz.Text text : satz.texte()) {
                    if (variante.zusammengesetzt() || variante.vorstufe() && satz.vorstufe(variante, text) == null) {
                        continue;
                    }
                    String pfad = satz.pinPfad(variante, text);
                    String eingabe = satz.eingabe(variante, text);
                    ParsedDocument document = kette.analyze(new DocumentRef("pruefstand", satz.name() + "/"
                            + text.name(), variante.name()), eingabe);
                    String actual = ConllU.write(document);
                    Path out = ziel.resolve(satz.name()).resolve(pfad);
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, actual, StandardCharsets.UTF_8);
                    String expected = Textsatz.resource(Textsatz.BASIS + satz.name() + "/" + pfad);
                    if (!analyseZeilen(expected).equals(analyseZeilen(actual))) {
                        abweichungen.add(satz.name() + "/" + pfad);
                    }
                    if (variante.vorstufe()) {
                        assertEquals(satz.vorstufe(variante, text).size(), document.sentences().size(),
                                satz.name() + "/" + pfad + ": je Vorstufen-Zeile genau ein Satz");
                    }
                }
            }
        }
        assertTrue(abweichungen.isEmpty(), "Pins weichen von der echten Kette ab (neue Ausgabe in " + ziel + "): "
                + abweichungen);
    }

    @Test
    @EnabledIfSystemProperty(named = "pruefstand.seiten", matches = ".+")
    void zahlenUeberEchteSeiten() throws IOException {
        Path dir = Path.of(System.getProperty("pruefstand.seiten"));
        List<Path> seiten;
        try (Stream<Path> files = Files.walk(dir)) {
            seiten = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith(".md")).sorted().toList();
        }
        ObjectCentricExtraction extraction = new ObjectCentricExtraction(ExtractionSettings.defaults());
        Map<StatementQuality.Level, Integer> stufen = new EnumMap<>(StatementQuality.Level.class);
        int saetze = 0;
        int aussagen = 0;
        int ohneAussage = 0;
        long started = System.nanoTime();
        for (Path seite : seiten) {
            ParsedDocument document = kette.analyze(new DocumentRef("seiten", "s", "1"), Files.readString(seite));
            ObjectCentricAnalysis analysis = extraction.analyze(document);
            saetze += document.sentences().size();
            aussagen += analysis.statements().size();
            boolean[] mitAussage = new boolean[document.sentences().size()];
            var mentions = StatementQuality.mentions(analysis);
            for (Statement statement : analysis.statements()) {
                mitAussage[statement.sentence()] = true;
                stufen.merge(StatementQuality.assess(mentions, analysis, statement).level(), 1, Integer::sum);
            }
            for (boolean b : mitAussage) {
                ohneAussage += b ? 0 : 1;
            }
        }
        StringBuilder out = new StringBuilder("Echte Seiten (nur Zahlen): ");
        out.append(seiten.size()).append(" Seiten, ").append(saetze).append(" Sätze, ").append(aussagen)
                .append(" Aussagen, ").append(String.format(java.util.Locale.GERMAN, "%.2f", saetze == 0 ? 0.0
                        : (double) aussagen / saetze)).append(" Aussagen je Satz, ").append(ohneAussage)
                .append(" Sätze ohne Aussage; ");
        for (StatementQuality.Level level : StatementQuality.Level.values()) {
            int n = stufen.getOrDefault(level, 0);
            out.append(level).append(' ').append(n).append(String.format(java.util.Locale.GERMAN, " (%.0f %%) ",
                    aussagen == 0 ? 0.0 : 100.0 * n / aussagen));
        }
        out.append("; ").append((System.nanoTime() - started) / 1_000_000).append(" ms");
        Path report = Path.of("build", "reports", "pruefstand", "seiten.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    /** Satztexte und Tokenzeilen, ohne Kommentare und Dokumentkennung. */
    private static List<String> analyseZeilen(String conllu) {
        return conllu.lines().filter(line -> line.startsWith("# text = ") || !line.isEmpty()
                && Character.isDigit(line.charAt(0))).toList();
    }
}
