package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalyzer;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Zahlen;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.ChatStepModel;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Echttest des zweiten Analysators (Fahrplan Paket A2) gegen einen Ollama-Server, nur über die HTTP-API und nur mit
 * {@code -PstatementModelLiveOllama=http://localhost:11434 [-PstatementModelLiveModel=gemma4:12b]
 * -PstatementModelLiveMissing=true]} (nur Texte ohne Pin); in der CI
 * übersprungen. Derselbe Weg wie im Betrieb ({@link ChatStepModel}, Temperatur 0, Denken aus, {@link
 * ModelStatementAnalyzer} mit Anweisung v1) über alle Texte aller Textsätze. Die Antworten landen als Pins unter
 * {@code build/reports/pruefstand/pins/<Textsatz>/varianten/ki-<Modell>/<Text>.antwort} zum Übernehmen; ausgegeben
 * werden nur Zahlen.
 */
@EnabledIfSystemProperty(named = "statement.model.live.ollama", matches = "https?://.+")
class PruefstandKiLiveTest {

    @Test
    void zweiterAnalysatorGegenOllama() throws IOException {
        String model = System.getProperty("statement.model.live.model", "gemma4:12b");
        AiServiceSettings settings = new AiServiceSettings(URI.create(System.getProperty(
                "statement.model.live.ollama")), null, model, null, null, Duration.ofMinutes(10), null, Set.of(),
                null, null, null, AiProvider.OLLAMA, null, null, null, null, null);
        StepModel chat = new ChatStepModel(ModelStatementAnalyzer.STEP, () -> settings, () -> null,
                ConfiguredSourceCatalog.of(List.of(), "space-harvester-pruefstand"), TrustBundles.NONE,
                "space-harvester-pruefstand");
        String variante = "ki-" + model.replace(':', '-').replace('/', '-');
        // Nur Texte ohne Pin (neuer Prüftext): die übrigen bleiben, wie sie gepinnt sind.
        boolean nurFehlende = Boolean.parseBoolean(System.getProperty("statement.model.live.missing", "false"));
        Path ziel = Path.of("build", "reports", "pruefstand", "pins");
        StringBuilder out = new StringBuilder("Zweiter Analysator gegen Ollama (" + model + "), nur Zahlen:\n");
        for (Textsatz satz : Textsatz.alle()) {
            Zahlen summe = new Zahlen();
            StatementAnalyzer.Costs kosten = StatementAnalyzer.Costs.NONE;
            long started = System.nanoTime();
            for (Textsatz.Text text : satz.texte()) {
                if (nurFehlende && satz.kiAntworten(new Textsatz.Variante(variante, ""), text) != null) {
                    continue;
                }
                List<String> antworten = new ArrayList<>();
                StepModel aufzeichnend = new StepModel() {
                    @Override
                    public String version() {
                        return chat.version();
                    }

                    @Override
                    public boolean available() {
                        return chat.available();
                    }

                    @Override
                    public String complete(String instructions, String input) {
                        String antwort = chat.complete(instructions, input);
                        antworten.add(antwort);
                        return antwort;
                    }
                };
                StatementAnalyzer.Costs[] costs = new StatementAnalyzer.Costs[1];
                ParsedStatements result = new ModelStatementAnalyzer(aufzeichnend, KiAnalyse.ZEILEN)
                        .analyze("pruefstand", KiAnalyse.eingabe(satz, text), () -> false, c -> costs[0] = c);
                kosten = kosten.plus(costs[0]);
                StringBuilder pin = new StringBuilder();
                for (String antwort : antworten) {
                    pin.append(Textsatz.AUFRUF).append('\n').append(antwort.strip()).append('\n');
                }
                Path file = ziel.resolve(satz.name()).resolve("varianten").resolve(variante)
                        .resolve(text.name() + ".antwort");
                Files.createDirectories(file.getParent());
                Files.writeString(file, pin.toString(), StandardCharsets.UTF_8);
                summe.add(Bewertung.zaehlen(satz, new Textsatz.Variante(variante, ""), text,
                        KiAnalyse.befunde(result)));
            }
            out.append(satz.name()).append(": vollständig richtig ").append(summe.richtig).append(" von ")
                    .append(summe.erwartet).append(", streng ").append(summe.streng).append(", falsch trotz PLAUSIBLE ")
                    .append(summe.plausibelFalsch).append(" von ").append(summe.plausibel).append(", Merkmale richtig ")
                    .append(summe.merkmaleRichtig()).append(", fälschlich ").append(summe.merkmaleFaelschlich())
                    .append("; ").append(kosten.sentences()).append(" Sätze, ").append(kosten.modelCalls())
                    .append(" Aufrufe, ").append(kosten.rejectedLines()).append(" Zeilen verworfen, ")
                    .append(kosten.rejectedStatements()).append(" Aussagen verworfen, ").append(kosten.noStatement())
                    .append(" Sätze ohne Aussage, ").append((System.nanoTime() - started) / 1_000_000_000)
                    .append(" s\n");
        }
        Path report = Path.of("build", "reports", "pruefstand", "ki-live.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
        assertTrue(out.length() > 0);
    }
}
