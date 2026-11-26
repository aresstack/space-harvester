package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Der externe KI-Dienst unter {@code space-harvester.ai} (ADR 0011), dieselben Namen wie in der Desktop-App
 * ({@link AiServiceSettings}). Den API-Key liefert der Betrieb aus einem Secret-Speicher (Umgebung, Config-Server,
 * Key Vault), nie im Klartext in einer eingecheckten Datei.
 *
 * @param embeddingChunkChars höchstens so viele Zeichen je eingebettetem Stück; 0 (Standard) ohne Zeichengrenze wie
 *                            im Enterprise AI Client, sonst mindestens 100
 * @param embeddingChunkTokens Budget je Stück in Tokens (#181, wie {@code knowledge.chunk.maxTokens}), mindestens 8
 * @param embeddingChunkOverlapSentences so viele Schlusssätze wiederholt das nächste Stück (wie
 *                                       {@code knowledge.chunk.overlapSentences}); 0 ohne Überlappung
 * @param rerankModel         Rerank-Modell des Dienstes ({@code POST /rerank}, #181) oder leer
 * @param visionModel         Vision-Modell für Bildbeschreibungen (ADR 0021) oder leer
 * @param chatMaxTokens       Ausgabelimit einer Antwort des Chat-Modells in Tokens ({@code max_tokens}), 1 bis 1000000
 * @param provider            {@code openai-compatible} (leer) oder {@code ollama}; bei Ollama ist {@code base-url} der
 *                            Server, etwa {@code http://localhost:11434}
 * @param contextTokens       Kontextlänge je Anfrage an Ollama ({@code num_ctx}); leer: 8192
 * @param loadTimeout         so lange darf Ollama ein Modell laden; leer: 10 min
 * @param retries             Wiederholungen einer fehlgeschlagenen Bildbeschreibung; leer: 2
 * @param loadPollInterval    Abstand der Frage an Ollama, ob ein angestoßenes Modell läuft; leer: 2 s
 * @param stepThinking        Nachdenken bei Analyseschritten (none, low, medium, high); leer: none
 * @param parallel            gleichzeitige Anfragen an den KI-Dienst; leer: 4
 * @param visionMaxTokens     Ausgabelimit einer Antwort des Vision-Modells in Tokens; leer: 8192
 * @param imageModel          Modell der Bildgenerierung (SVG-Schaubilder); leer: das Chat-Modell
 * @param imageMaxTokens      Ausgabelimit einer Bildgenerierung in Tokens; leer: 16384
 * @param visionThinking      Nachdenken des Vision-Modells (none, low, medium, high); leer: wie step-thinking
 * @param imageThinking       Nachdenken der Bildgenerierung; leer: wie step-thinking
 * @param summaryModel        Modell der Update-Zusammenfassung; leer: das Chat-Modell
 * @param summaryMaxTokens    Tokenlänge der Update-Zusammenfassung; leer: 32768
 * @param summaryThinking     Nachdenken der Update-Zusammenfassung; leer: none
 * @param podcastModel        Modell der Podcast-Texte; leer: das Chat-Modell
 * @param podcastMaxTokens    Tokenlänge der Podcast-Texte; leer: 16384
 * @param podcastThinking     Nachdenken der Podcast-Texte; leer: none
 * @param summaryPlanModel    Modell für den Plan der Zusammenfassung; leer: das Chat-Modell
 * @param summaryPlanMaxTokens Tokenlänge des Plans; leer: 16384
 * @param summaryPlanThinking Nachdenken des Plans; leer: none
 * @param imagePlanModel      Modell für den Plan eines Schaubilds; leer: das Chat-Modell
 * @param imagePlanMaxTokens  Tokenlänge des Schaubild-Plans; leer: 16384
 * @param imagePlanThinking   Nachdenken des Schaubild-Plans; leer: none
 * @param deadlineModel       Modell der Fristenextraktion (ADR 0035); leer: das Chat-Modell
 * @param deadlineMaxTokens   Tokenlänge der Fristenextraktion; leer: 8192
 * @param deadlineThinking    Nachdenken der Fristenextraktion; leer: none
 * @param podcastSpeechModel  TTS-Modell des Podcasts; leer: das bevorzugte des Dienstes
 * @param podcastVoiceHost    Stimme des Gastgebers; leer: Standardstimme der Modellfamilie
 * @param podcastVoiceCohost  Stimme des Co-Hosts; leer: erste Stimme der anderen Stimmlage
 * @param readAloudModel      TTS-Modell des Vorlesens (Chat, Leseansicht); leer: das bevorzugte des Dienstes
 * @param readAloudVoice      Stimme des Vorlesens; leer: Standardstimme der Modellfamilie
 * @param podcastPauseMillis  Pause zwischen zwei Beiträgen des Podcasts in ms; leer: Vorgabe des Core
 * @param podcastSpeechAttempts Versuche je Stück beim Vertonen des Podcasts; leer: Vorgabe des Core
 * @param podcastSpeechWorkers gleichzeitige Synthesen je Beitrag des Podcasts; leer: Vorgabe des Core
 * @param diagnosisEnabled    Schalter „Diagnose per KI“; leer: eingeschaltet
 * @param diagnosisModel      Modell der Diagnose; leer: gpt-oss-120b
 * @param diagnosisMaxTokens  Tokenlänge der Diagnose; leer: die des Chats
 * @param diagnosisThinking   Nachdenken der Diagnose; leer: das des Chats
 * @param summaryTimeout      Frist je Anfrage der Update-Zusammenfassung; leer: 5 Minuten
 * @param labelTimeout        Frist je Anfrage der Abschnittsbenennung; leer: 60 Sekunden
 * @param expansionTimeout    Frist je Anfrage der Akronymauflösung; leer: 90 Sekunden
 */
@ConfigurationProperties("space-harvester.ai")
public record AiProperties(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                           Integer embeddingDimensions, @DefaultValue("30s") Duration timeout, String trustBundle,
                           @DefaultValue Set<String> externalProcessingSources,
                           @DefaultValue("0") int embeddingChunkChars, String rerankModel,
                           @DefaultValue("350") int embeddingChunkTokens,
                           @DefaultValue("1") int embeddingChunkOverlapSentences, String visionModel,
                           @DefaultValue("8192") int chatMaxTokens, String provider,
                           Integer contextTokens, Duration loadTimeout, Integer retries,
                           Duration loadPollInterval, String stepThinking, Integer parallel,
                           Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                           String visionThinking, String imageThinking, String summaryModel,
                           Integer summaryMaxTokens, String summaryThinking, String podcastModel,
                           Integer podcastMaxTokens, String podcastThinking, String summaryPlanModel,
                           Integer summaryPlanMaxTokens, String summaryPlanThinking, String imagePlanModel,
                           Integer imagePlanMaxTokens, String imagePlanThinking, String deadlineModel,
                           Integer deadlineMaxTokens, String deadlineThinking, String podcastSpeechModel,
                           String podcastVoiceHost, String podcastVoiceCohost, String readAloudModel,
                           String readAloudVoice, Integer podcastPauseMillis, Integer podcastSpeechAttempts,
                           Integer podcastSpeechWorkers, Boolean diagnosisEnabled, String diagnosisModel,
                           Integer diagnosisMaxTokens, String diagnosisThinking, Duration summaryTimeout,
                           Duration labelTimeout, Duration expansionTimeout) {

    AiServiceSettings toSettings() {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout,
                trustBundle, externalProcessingSources, rerankModel, visionModel, chatMaxTokens,
                AiProvider.fromKey(provider), contextTokens, loadTimeout, retries,
                loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, new AiServiceSettings.ModelUse(summaryModel, summaryMaxTokens,
                summaryThinking), new AiServiceSettings.ModelUse(podcastModel, podcastMaxTokens, podcastThinking),
                new AiServiceSettings.ModelUse(summaryPlanModel, summaryPlanMaxTokens, summaryPlanThinking),
                new AiServiceSettings.ModelUse(imagePlanModel, imagePlanMaxTokens, imagePlanThinking),
                new AiServiceSettings.ModelUse(deadlineModel, deadlineMaxTokens, deadlineThinking),
                new AiServiceSettings.Speech(podcastSpeechModel, podcastVoiceHost, podcastVoiceCohost,
                        readAloudModel, readAloudVoice, podcastPauseMillis, podcastSpeechAttempts,
                        podcastSpeechWorkers),
                new AiServiceSettings.Diagnosis(diagnosisEnabled,
                        new AiServiceSettings.ModelUse(diagnosisModel, diagnosisMaxTokens, diagnosisThinking)),
                new AiServiceSettings.Timeouts(summaryTimeout, labelTimeout, expansionTimeout));
    }

    /** Ohne API-Key. */
    @Override
    public String toString() {
        return toSettings().toString();
    }
}
