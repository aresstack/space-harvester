package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Eingaben für den KI-Dienst ({@code space-harvester.ai}, ADR 0011): Basisadresse eines OpenAI-kompatiblen Dienstes,
 * gewählte Modelle und die Wissensquellen, deren Inhalte an den Dienst gehen dürfen. Der API-Key gehört nicht dazu;
 * er liegt im Tresor und erreicht den Core nur zur Laufzeit.
 *
 * @param baseUrl                   z. B. {@code https://api.openai.com/v1}; leer heißt kein KI-Dienst
 * @param chatModel                 Modell für Text (etwa für spätere Suchbegriffe) oder leer
 * @param embeddingModel            Modell für Embeddings der semantischen Suche oder leer
 * @param embeddingDimensions       Vektordimension als Text oder leer (dann aus der ersten Antwort)
 * @param timeout                   Zeitgrenze je Anfrage; {@code null} für den Standard des Core, bleibt erhalten
 * @param externalProcessingSources Quellen-IDs, deren Inhalte extern verarbeitet werden dürfen; leer heißt keine
 * @param rerankModel               Cross-Encoder des Dienstes ({@code /rerank}) oder leer: Reihenfolge bleibt
 * @param chunkTokens               Obergrenze je Abschnitt für Embeddings als Text oder leer (Core: 350)
 * @param chunkOverlapSentences     überlappende Sätze zwischen Abschnitten als Text oder leer (Core: 1)
 * @param visionModel               Modell, das Bilder beschreibt ({@code vision-model}, #362), oder leer: keine
 *                                  Bildbeschreibung
 * @param chatMaxTokens             Ausgabelimit einer Antwort des Text-Modells in Tokens als Text
 *                                  ({@code chat-max-tokens}, gesendet als {@code max_tokens}) oder leer (Core: 8192)
 * @param provider                  Art des Dienstes ({@code provider}); {@code null} heißt OpenAI-kompatibel. Bei
 *                                  Ollama ist die Basisadresse der Server, etwa {@code http://localhost:11434}
 * @param contextTokens             Kontextlänge je Anfrage an Ollama ({@code context-tokens}, {@code num_ctx}) als
 *                                  Text oder leer (Core: 8192)
 * @param loadTimeout               so viele Sekunden darf Ollama ein Modell laden ({@code load-timeout}) als Text
 *                                  oder leer (Standard des Core)
 * @param retries                   Wiederholungen einer fehlgeschlagenen Bildbeschreibung ({@code retries}) als Text
 *                                  oder leer (Standard des Core)
 * @param loadPollInterval          in diesem Abstand in Sekunden fragt die App Ollama, ob ein angestoßenes Modell läuft
 *                                  ({@code load-poll-interval}), als Text oder leer (Standard des Core)
 * @param stepThinking              Nachdenken bei Analyseschritten ({@code step-thinking}: none, low, medium, high)
 *                                  oder leer (Standard des Core: aus)
 * @param parallel                  gleichzeitige Anfragen an den KI-Dienst ({@code parallel}) als Text oder leer
 *                                  (Standard des Core: 4)
 * @param visionMaxTokens           Ausgabelimit einer Antwort des Vision-Modells ({@code vision-max-tokens}) als
 *                                  Text oder leer (Core: 8192)
 * @param imageModel                Modell der Bildgenerierung ({@code image-model}) oder leer: das Text-Modell
 * @param imageMaxTokens            Ausgabelimit einer Bildgenerierung ({@code image-max-tokens}) als Text oder leer
 *                                  (Core: 16384)
 * @param visionThinking            Nachdenken des Vision-Modells ({@code vision-thinking}) oder leer: wie
 *                                  step-thinking
 * @param imageThinking             Nachdenken der Bildgenerierung ({@code image-thinking}) oder leer: wie
 *                                  step-thinking
 * @param summary                   Zeile „Zusammenfassung“ ({@code summary-model}, {@code -max-tokens},
 *                                  {@code -thinking})
 * @param summaryPlan               Zeile „Zusammenfassung – Plan“ ({@code summary-plan-*})
 * @param podcast                   Zeile „Podcast“ ({@code podcast-*})
 * @param imagePlan                 Zeile „Bildgenerierung – Plan“ ({@code image-plan-*})
 * @param deadline                  Zeile „Fristen“ ({@code deadline-*}, ADR 0035)
 * @param speech                    Sprachausgabe: Modell und Stimmen für Podcast und Vorlesen, je leer: automatisch
 * @param diagnosis                 Zeile „Diagnose“ ({@code diagnosis-*}); leeres Modell: gpt-oss-120b
 * @param diagnosisEnabled          Schalter „Diagnose per KI“ ({@code diagnosis-enabled}); {@code null}: eingeschaltet
 * @param timeouts                  Fristen einzelner KI-Aufgaben ({@code summary-timeout}, {@code label-timeout},
 *                                  {@code expansion-timeout}); leer: Vorgaben des Core
 */
public record AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                      Duration timeout, List<String> externalProcessingSources, String rerankModel,
                      String chunkTokens, String chunkOverlapSentences, String visionModel, String chatMaxTokens,
                      AiProvider provider, String contextTokens, String loadTimeout, String retries,
                      String loadPollInterval, String stepThinking, String parallel, String visionMaxTokens,
                      String imageModel, String imageMaxTokens, String visionThinking, String imageThinking,
                      Use summary, Use summaryPlan, Use podcast, Use imagePlan, Use deadline,
                      AiServiceSettings.Speech speech, Use diagnosis, Boolean diagnosisEnabled,
                      AiServiceSettings.Timeouts timeouts) {

    /**
     * Eingaben einer eigenen Modellzeile (Modell, Tokenlänge, Nachdenken) als Text, wie die Felder sie zeigen; leer
     * heißt jeweils Standard.
     */
    public record Use(String model, String maxTokens, String thinking) {

        public static final Use EMPTY = new Use(null, null, null);

        public Use {
            model = trim(model);
            maxTokens = trim(maxTokens);
            thinking = trim(thinking);
        }

        /** Aus den gelesenen Einstellungen. */
        public static Use of(AiServiceSettings.ModelUse use) {
            return use == null ? EMPTY : new Use(use.model(), use.maxTokens() == null ? null
                    : String.valueOf(use.maxTokens()), use.thinking());
        }

        public boolean empty() {
            return model == null && maxTokens == null && thinking == null;
        }

        /** Die Tokenlänge als Zahl oder {@code null}, wenn leer oder ungültig. */
        public Integer tokens() {
            return tokenLimit(maxTokens);
        }

        /** Das Nachdenken, wenn gültig; sonst {@code null}. */
        public String level() {
            return AiDraft.level(thinking);
        }

        /** Für den Core; nur gültige Teile. */
        public AiServiceSettings.ModelUse use() {
            return new AiServiceSettings.ModelUse(model, tokens(), level());
        }

        void problems(String label, List<String> problems) {
            if (maxTokens != null && tokens() == null) {
                problems.add("Die Tokenlänge der Zeile „" + label + "“ ist eine ganze Zahl von 1 bis "
                        + MAX_CHAT_MAX_TOKENS + " oder bleibt leer.");
            }
            if (thinking != null && level() == null) {
                problems.add("Das Nachdenken der Zeile „" + label + "“ ist none, low, medium oder high oder bleibt "
                        + "leer.");
            }
        }

        void yaml(StringBuilder yaml, String inner, String prefix) {
            append(yaml, inner, prefix + "-model", model);
            if (tokens() != null) {
                yaml.append(inner).append(prefix).append("-max-tokens: ").append(tokens()).append('\n');
            }
            if (level() != null) {
                yaml.append(inner).append(prefix).append("-thinking: ").append(level()).append('\n');
            }
        }
    }

    /** Grenzen der Kontextlänge bei Ollama, wie im Core ({@code AiServiceSettings}). */
    public static final int MIN_CONTEXT_TOKENS = 512;
    public static final int MAX_CONTEXT_TOKENS = 1_048_576;

    /** Größtes Ausgabelimit, wie im Core ({@code AiServiceSettings.MAX_CHAT_MAX_TOKENS}). */
    public static final int MAX_CHAT_MAX_TOKENS = 1_000_000;

    /** Kleinste Obergrenze je Abschnitt, wie im Core ({@code KnowledgeChunkingPolicy}). */
    public static final int MIN_CHUNK_TOKENS = 8;
    public static final AiDraft EMPTY = new AiDraft(null, null, null, null, null, List.of());

    public AiDraft {
        baseUrl = trim(baseUrl);
        chatModel = trim(chatModel);
        embeddingModel = trim(embeddingModel);
        embeddingDimensions = trim(embeddingDimensions);
        rerankModel = trim(rerankModel);
        chunkTokens = trim(chunkTokens);
        chunkOverlapSentences = trim(chunkOverlapSentences);
        visionModel = trim(visionModel);
        chatMaxTokens = trim(chatMaxTokens);
        externalProcessingSources = externalProcessingSources == null ? List.of() : externalProcessingSources
                .stream().map(AiDraft::trim).filter(id -> id != null).distinct().toList();
        provider = provider == null ? AiProvider.OPENAI_COMPATIBLE : provider;
        contextTokens = trim(contextTokens);
        loadTimeout = trim(loadTimeout);
        retries = trim(retries);
        loadPollInterval = trim(loadPollInterval);
        stepThinking = trim(stepThinking);
        parallel = trim(parallel);
        visionMaxTokens = trim(visionMaxTokens);
        imageModel = trim(imageModel);
        imageMaxTokens = trim(imageMaxTokens);
        visionThinking = trim(visionThinking);
        imageThinking = trim(imageThinking);
        summary = summary == null ? Use.EMPTY : summary;
        summaryPlan = summaryPlan == null ? Use.EMPTY : summaryPlan;
        podcast = podcast == null ? Use.EMPTY : podcast;
        imagePlan = imagePlan == null ? Use.EMPTY : imagePlan;
        deadline = deadline == null ? Use.EMPTY : deadline;
        speech = speech == null ? AiServiceSettings.Speech.AUTOMATIC : speech;
        diagnosis = diagnosis == null ? Use.EMPTY : diagnosis;
        // Eingeschaltet ist die Vorgabe und steht nicht in der Datei.
        diagnosisEnabled = Boolean.TRUE.equals(diagnosisEnabled) ? null : diagnosisEnabled;
        timeouts = timeouts == null ? AiServiceSettings.Timeouts.DEFAULT : timeouts;
    }

    /** Ohne eigene Fristen der KI-Aufgaben. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking, Use summary, Use summaryPlan,
                   Use podcast, Use imagePlan, Use deadline, AiServiceSettings.Speech speech, Use diagnosis,
                   Boolean diagnosisEnabled) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, summaryPlan, podcast, imagePlan, deadline, speech, diagnosis,
                diagnosisEnabled, null);
    }

    /** Ohne eigene Zeile der Diagnose. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking, Use summary, Use summaryPlan,
                   Use podcast, Use imagePlan, Use deadline, AiServiceSettings.Speech speech) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, summaryPlan, podcast, imagePlan, deadline, speech, null, null);
    }

    /** Ohne eigene Sprachausgabe. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking, Use summary, Use summaryPlan,
                   Use podcast, Use imagePlan, Use deadline) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, summaryPlan, podcast, imagePlan, deadline, null);
    }

    /** Ohne eigene Zeile der Fristenextraktion. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking, Use summary, Use summaryPlan,
                   Use podcast, Use imagePlan) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, summaryPlan, podcast, imagePlan, null);
    }

    /** Ohne eigene Zeile für den Plan der Schaubilder. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking, Use summary, Use summaryPlan,
                   Use podcast) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, summaryPlan, podcast, null);
    }

    /** Ohne eigene Zeilen für Zusammenfassung und Podcast. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens, String visionThinking, String imageThinking) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, null, null, (Use) null);
    }

    /** Ohne eigenes Nachdenken für Vision-Modell und Bildgenerierung. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel, String visionMaxTokens, String imageModel,
                   String imageMaxTokens) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens, null,
                (String) null);
    }

    /** Ohne eigene Tokenlänge des Vision-Modells und ohne Bildgenerierung. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking, String parallel) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, (String) null, null, null);
    }

    /** Ohne Vorgabe für gleichzeitige Anfragen. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval,
                   String stepThinking) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, null);
    }

    /** Ohne Vorgabe für das Nachdenken bei Analyseschritten. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens, AiProvider provider,
                   String contextTokens, String loadTimeout, String retries, String loadPollInterval) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, null);
    }

    /** Für einen OpenAI-kompatiblen Dienst. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel, String chatMaxTokens) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, null, null, null, null, null);
    }

    /** Mit dem Ausgabelimit des Core. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences, String visionModel) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, visionModel, null);
    }

    /** Ohne Vision-Modell. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources, String rerankModel, String chunkTokens,
                   String chunkOverlapSentences) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, rerankModel,
                chunkTokens, chunkOverlapSentences, null);
    }

    /** Ohne Rerank-Modell und mit der Zerlegung des Core. */
    public AiDraft(String baseUrl, String chatModel, String embeddingModel, String embeddingDimensions,
                   Duration timeout, List<String> externalProcessingSources) {
        this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, null, null,
                null);
    }

    public static AiDraft of(AppSettings.AiSettings settings) {
        return new AiDraft(settings.baseUrl(), settings.chatModel(), settings.embeddingModel(),
                settings.embeddingDimensions() == null ? null : String.valueOf(settings.embeddingDimensions()),
                settings.timeout(), settings.externalProcessingSources(), settings.rerankModel(),
                settings.chunkTokens() == null ? null : String.valueOf(settings.chunkTokens()),
                settings.chunkOverlapSentences() == null ? null : String.valueOf(settings.chunkOverlapSentences()),
                settings.visionModel(),
                settings.chatMaxTokens() == null ? null : String.valueOf(settings.chatMaxTokens()),
                settings.provider(),
                settings.contextTokens() == null ? null : String.valueOf(settings.contextTokens()),
                settings.loadTimeout() == null ? null : String.valueOf(settings.loadTimeout().toSeconds()),
                settings.retries() == null ? null : String.valueOf(settings.retries()),
                settings.loadPollInterval() == null ? null
                        : String.valueOf(settings.loadPollInterval().toSeconds()),
                settings.stepThinking(),
                settings.parallel() == null ? null : String.valueOf(settings.parallel()),
                settings.visionMaxTokens() == null ? null : String.valueOf(settings.visionMaxTokens()),
                settings.imageModel(),
                settings.imageMaxTokens() == null ? null : String.valueOf(settings.imageMaxTokens()),
                settings.visionThinking(), settings.imageThinking(), Use.of(settings.summary()),
                Use.of(settings.summaryPlan()), Use.of(settings.podcast()), Use.of(settings.imagePlan()),
                Use.of(settings.deadline()), settings.speech(), Use.of(settings.diagnosis().use()),
                settings.diagnosis().enabled(), settings.timeouts());
    }

    /**
     * Dieselben Eingaben mit anderen Modellen (Reiter „Modelle“); Adresse, Freigaben und Vision-Modell bleiben.
     */
    public AiDraft withModels(String chatModel, String embeddingModel, String embeddingDimensions,
                              String rerankModel, String chunkTokens, String chunkOverlapSentences) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderem Vision-Modell. */
    public AiDraft withVisionModel(String visionModel) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderem Ausgabelimit des Text-Modells. */
    public AiDraft withChatMaxTokens(String chatMaxTokens) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Modelle mit anderem Dienst und anderen Freigaben (Reiter „KI-Dienst“). */
    public AiDraft withService(String baseUrl, List<String> externalProcessingSources) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben für eine andere Art von Dienst. */
    public AiDraft withProvider(AiProvider provider) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Kontextlänge bei Ollama. */
    public AiDraft withContextTokens(String contextTokens) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Ladefrist bei Ollama (Sekunden). */
    public AiDraft withLoadTimeout(String loadTimeout) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Zahl an Wiederholungen. */
    public AiDraft withRetries(String retries) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderem Abstand der Frage nach dem Laden (Sekunden). */
    public AiDraft withLoadPollInterval(String loadPollInterval) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Ob der Dienst ein Ollama-Server ist. */
    public boolean ollama() {
        return provider == AiProvider.OLLAMA;
    }

    /** Ob nichts eingetragen ist; dann wird der Block entfernt. */
    public boolean empty() {
        return baseUrl == null && chatModel == null && embeddingModel == null && embeddingDimensions == null
                && timeout == null && externalProcessingSources.isEmpty() && rerankModel == null
                && chunkTokens == null && chunkOverlapSentences == null && visionModel == null
                && chatMaxTokens == null && !ollama() && contextTokens == null && loadTimeout == null
                && retries == null && loadPollInterval == null && stepThinking == null && parallel == null
                && visionMaxTokens == null && imageModel == null && imageMaxTokens == null && visionThinking == null
                && imageThinking == null && summary.empty() && summaryPlan.empty() && podcast.empty()
                && imagePlan.empty() && deadline.empty() && speech.automatic() && diagnosis.empty()
                && diagnosisEnabled == null && timeouts.empty();
    }

    /** Das Nachdenken bei Analyseschritten, wenn gültig; sonst {@code null}. */
    public String thinking() {
        return level(stepThinking);
    }

    /** Das Nachdenken des Vision-Modells, wenn gültig; sonst {@code null} (wie das Text-Modell). */
    public String visionLevel() {
        return level(visionThinking);
    }

    /** Das Nachdenken der Bildgenerierung, wenn gültig; sonst {@code null} (wie das Text-Modell). */
    public String imageLevel() {
        return level(imageThinking);
    }

    /** Eine Stufe des Nachdenkens (none, low, medium, high) klein geschrieben; sonst {@code null}. */
    static String level(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).matches("none|low|medium|high")
                ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** Was nicht passt; leer, wenn die Eingaben so in die Datei können. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (baseUrl == null) {
            // Freigaben je Quelle dürfen ohne Dienst stehen: Sie wirken, sobald einer eingetragen ist.
            if (chatModel != null || embeddingModel != null || embeddingDimensions != null || rerankModel != null
                    || visionModel != null || chunkTokens != null || chunkOverlapSentences != null
                    || chatMaxTokens != null || ollama() || contextTokens != null || loadTimeout != null
                    || retries != null || loadPollInterval != null || parallel != null || visionMaxTokens != null
                    || imageModel != null || imageMaxTokens != null || visionThinking != null
                    || imageThinking != null || !summary.empty() || !summaryPlan.empty() || !podcast.empty()
                    || !imagePlan.empty() || !deadline.empty() || !speech.automatic() || !diagnosis.empty()) {
                problems.add("Die Basisadresse des KI-Dienstes fehlt.");
            }
        } else if (looksLikeKey(baseUrl)) {
            problems.add("Die Basisadresse sieht wie ein API-Key aus; der gehört in das Feld API-Key, die "
                    + "Basisadresse beginnt mit http:// oder https://.");
        } else if (!baseUrl.matches("(?i)https?://.+") || !parsable(baseUrl)) {
            problems.add("Die Basisadresse beginnt mit http:// oder https:// und ist eine gültige Adresse.");
        }
        if (embeddingDimensions != null && dimensions() == null) {
            problems.add("Die Dimension ist eine positive ganze Zahl oder bleibt leer.");
        }
        if (chunkTokens != null && tokens() == null) {
            problems.add("Die Abschnittsgröße ist eine ganze Zahl ab " + MIN_CHUNK_TOKENS + " oder bleibt leer.");
        }
        if (chunkOverlapSentences != null && overlap() == null) {
            problems.add("Die Überlappung ist eine ganze Zahl ab 0 oder bleibt leer.");
        }
        if (contextTokens != null && context() == null) {
            problems.add("Die Kontextlänge ist eine ganze Zahl von " + MIN_CONTEXT_TOKENS + " bis "
                    + MAX_CONTEXT_TOKENS + " oder bleibt leer.");
        }
        if (loadTimeout != null && loadDuration() == null) {
            problems.add("Die Ladefrist ist eine ganze Zahl Sekunden von 1 bis "
                    + AiServiceSettings.MAX_LOAD_TIMEOUT.toSeconds() + " oder bleibt leer.");
        }
        if (loadPollInterval != null && pollDuration() == null) {
            problems.add("Der Abfrageabstand ist eine ganze Zahl Sekunden ab 1, höchstens die Ladefrist, oder bleibt "
                    + "leer.");
        }
        if (retries != null && retryCount() == null) {
            problems.add("Die Wiederholungen sind eine ganze Zahl von 0 bis " + AiServiceSettings.MAX_RETRIES
                    + " oder bleiben leer.");
        }
        if (parallel != null && parallelCount() == null) {
            problems.add("Die gleichzeitigen Anfragen sind eine ganze Zahl von 1 bis " + AiServiceSettings.MAX_PARALLEL
                    + " oder bleiben leer.");
        }
        if (stepThinking != null && thinking() == null) {
            problems.add("Das Nachdenken bei Analyseschritten ist none, low, medium oder high oder bleibt leer.");
        }
        if (visionThinking != null && visionLevel() == null) {
            problems.add("Das Nachdenken des Vision-Modells ist none, low, medium oder high oder bleibt leer.");
        }
        if (imageThinking != null && imageLevel() == null) {
            problems.add("Das Nachdenken der Bildgenerierung ist none, low, medium oder high oder bleibt leer.");
        }
        if (chatMaxTokens != null && maxTokens() == null) {
            problems.add("Das Ausgabelimit ist eine ganze Zahl von 1 bis " + MAX_CHAT_MAX_TOKENS
                    + " oder bleibt leer.");
        }
        if (visionMaxTokens != null && visionTokens() == null) {
            problems.add("Die Tokenlänge des Vision-Modells ist eine ganze Zahl von 1 bis " + MAX_CHAT_MAX_TOKENS
                    + " oder bleibt leer.");
        }
        if (imageMaxTokens != null && imageTokens() == null) {
            problems.add("Die Tokenlänge der Bildgenerierung ist eine ganze Zahl von 1 bis " + MAX_CHAT_MAX_TOKENS
                    + " oder bleibt leer.");
        }
        summary.problems("Zusammenfassung", problems);
        summaryPlan.problems("Zusammenfassung – Plan", problems);
        podcast.problems("Podcast", problems);
        imagePlan.problems("Bildgenerierung – Plan", problems);
        deadline.problems("Fristen", problems);
        diagnosis.problems("Diagnose", problems);
        return problems;
    }

    /**
     * Ob ein Wert eher ein Schlüssel als eine Adresse ist: ohne {@code ://}, ohne Leerzeichen und entweder mit
     * typischem Präfix ({@code sk-}, {@code Bearer }) oder lang und ohne Punkt.
     */
    static boolean looksLikeKey(String value) {
        if (value.contains("://")) {
            return false;
        }
        String v = value.strip();
        return v.regionMatches(true, 0, "sk-", 0, 3) || v.regionMatches(true, 0, "bearer ", 0, 7)
                || (v.length() >= 24 && !v.contains(".") && v.matches("[A-Za-z0-9_\\-]+"));
    }

    /** Die Dimension als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer dimensions() {
        if (embeddingDimensions == null || !embeddingDimensions.matches("\\d{1,6}")) {
            return null;
        }
        int value = Integer.parseInt(embeddingDimensions);
        return value > 0 ? value : null;
    }

    /** Die Obergrenze je Abschnitt als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer tokens() {
        Integer value = number(chunkTokens);
        return value != null && value >= MIN_CHUNK_TOKENS ? value : null;
    }

    /** Die überlappenden Sätze als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer overlap() {
        return number(chunkOverlapSentences);
    }

    /** Das Ausgabelimit des Text-Modells als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer maxTokens() {
        return tokenLimit(chatMaxTokens);
    }

    /** Das Ausgabelimit des Vision-Modells als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer visionTokens() {
        return tokenLimit(visionMaxTokens);
    }

    /** Das Ausgabelimit der Bildgenerierung als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer imageTokens() {
        return tokenLimit(imageMaxTokens);
    }

    private static Integer tokenLimit(String text) {
        if (text == null || !text.matches("\\d{1,7}")) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= MAX_CHAT_MAX_TOKENS ? value : null;
    }

    /** Dieselben Eingaben mit anderem Ausgabelimit des Vision-Modells. */
    public AiDraft withVisionMaxTokens(String visionMaxTokens) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Bildgenerierung (Modell und Ausgabelimit). */
    public AiDraft withImageGeneration(String imageModel, String imageMaxTokens) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderen eigenen Zeilen für Zusammenfassung, Plan und Podcast. */
    public AiDraft withUses(Use summary, Use summaryPlan, Use podcast) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Zeile „Bildgenerierung – Plan“. */
    public AiDraft withImagePlan(Use imagePlan) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Sprachausgabe für Podcast und Vorlesen. */
    public AiDraft withSpeech(AiServiceSettings.Speech speech) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Zeile „Fristen“. */
    public AiDraft withDeadline(Use deadline) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Dieselben Eingaben mit anderer Zeile „Diagnose“: Schalter „Diagnose per KI“ und Modell. */
    public AiDraft withDiagnosis(boolean enabled, Use diagnosis) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, enabled, timeouts);
    }

    /** Die Zeile „Diagnose“ für den Core. */
    public AiServiceSettings.Diagnosis diagnosisSettings() {
        return new AiServiceSettings.Diagnosis(diagnosisEnabled, diagnosis.use());
    }

    /** Dieselben Eingaben mit anderem Nachdenken für Vision-Modell und Bildgenerierung (leer: wie Text-Modell). */
    public AiDraft withThinking(String visionThinking, String imageThinking) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    /** Die Kontextlänge als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer context() {
        if (contextTokens == null || !contextTokens.matches("\\d{1,7}")) {
            return null;
        }
        int value = Integer.parseInt(contextTokens);
        return value >= MIN_CONTEXT_TOKENS && value <= MAX_CONTEXT_TOKENS ? value : null;
    }

    /** Die Ladefrist als Dauer oder {@code null}, wenn leer oder ungültig. */
    public Duration loadDuration() {
        if (loadTimeout == null || !loadTimeout.matches("\\d{1,7}")) {
            return null;
        }
        Duration value = Duration.ofSeconds(Long.parseLong(loadTimeout));
        return value.isZero() || value.compareTo(AiServiceSettings.MAX_LOAD_TIMEOUT) > 0 ? null : value;
    }

    /** Der Abfrageabstand als Dauer oder {@code null}, wenn leer oder ungültig. */
    public Duration pollDuration() {
        if (loadPollInterval == null || !loadPollInterval.matches("\\d{1,7}")) {
            return null;
        }
        Duration value = Duration.ofSeconds(Long.parseLong(loadPollInterval));
        Duration limit = loadDuration() == null ? AiServiceSettings.DEFAULT_LOAD_TIMEOUT : loadDuration();
        return value.isZero() || value.compareTo(limit) > 0 ? null : value;
    }

    /** Die Wiederholungen als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer retryCount() {
        if (retries == null || !retries.matches("\\d{1,3}")) {
            return null;
        }
        int value = Integer.parseInt(retries);
        return value <= AiServiceSettings.MAX_RETRIES ? value : null;
    }

    /** Die gleichzeitigen Anfragen als Zahl oder {@code null}, wenn leer oder ungültig. */
    public Integer parallelCount() {
        if (parallel == null || !parallel.matches("\\d{1,3}")) {
            return null;
        }
        int value = Integer.parseInt(parallel);
        return value >= 1 && value <= AiServiceSettings.MAX_PARALLEL ? value : null;
    }

    /** Dieselben Eingaben mit anderer Zahl gleichzeitiger Anfragen. */
    public AiDraft withParallel(String parallel) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary,
                summaryPlan, podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    private static Integer number(String text) {
        return text == null || !text.matches("\\d{1,6}") ? null : Integer.parseInt(text);
    }

    /** Der Block {@code ai:} mit Einrückung {@code indent}; nur Schlüssel mit Wert. */
    public String yaml(String indent) {
        StringBuilder yaml = new StringBuilder(indent).append("ai:\n");
        String inner = indent + "  ";
        if (ollama()) {
            append(yaml, inner, "provider", provider.key());
        }
        append(yaml, inner, "base-url", baseUrl);
        if (context() != null) {
            yaml.append(inner).append("context-tokens: ").append(context()).append('\n');
        }
        if (loadDuration() != null) {
            yaml.append(inner).append("load-timeout: ").append(loadDuration().toSeconds()).append("s\n");
        }
        if (pollDuration() != null) {
            yaml.append(inner).append("load-poll-interval: ").append(pollDuration().toSeconds()).append("s\n");
        }
        append(yaml, inner, "chat-model", chatModel);
        if (thinking() != null) {
            yaml.append(inner).append("step-thinking: ").append(thinking()).append('\n');
        }
        if (maxTokens() != null) {
            yaml.append(inner).append("chat-max-tokens: ").append(maxTokens()).append('\n');
        }
        append(yaml, inner, "embedding-model", embeddingModel);
        if (dimensions() != null) {
            yaml.append(inner).append("embedding-dimensions: ").append(dimensions()).append('\n');
        }
        append(yaml, inner, "rerank-model", rerankModel);
        append(yaml, inner, "vision-model", visionModel);
        if (visionTokens() != null) {
            yaml.append(inner).append("vision-max-tokens: ").append(visionTokens()).append('\n');
        }
        append(yaml, inner, "image-model", imageModel);
        if (imageTokens() != null) {
            yaml.append(inner).append("image-max-tokens: ").append(imageTokens()).append('\n');
        }
        if (visionLevel() != null) {
            yaml.append(inner).append("vision-thinking: ").append(visionLevel()).append('\n');
        }
        if (imageLevel() != null) {
            yaml.append(inner).append("image-thinking: ").append(imageLevel()).append('\n');
        }
        summary.yaml(yaml, inner, "summary");
        summaryPlan.yaml(yaml, inner, "summary-plan");
        podcast.yaml(yaml, inner, "podcast");
        imagePlan.yaml(yaml, inner, "image-plan");
        deadline.yaml(yaml, inner, "deadline");
        if (diagnosisEnabled != null) {
            yaml.append(inner).append(AiServiceSettings.DIAGNOSIS_ENABLED).append(": ").append(diagnosisEnabled)
                    .append('\n');
        }
        diagnosis.yaml(yaml, inner, "diagnosis");
        seconds(yaml, inner, AiServiceSettings.SUMMARY_TIMEOUT, timeouts.summary());
        seconds(yaml, inner, AiServiceSettings.LABEL_TIMEOUT, timeouts.label());
        seconds(yaml, inner, AiServiceSettings.EXPANSION_TIMEOUT, timeouts.expansion());
        append(yaml, inner, AiServiceSettings.PODCAST_SPEECH_MODEL, speech.podcastModel());
        append(yaml, inner, AiServiceSettings.PODCAST_VOICE_HOST, speech.podcastHost());
        append(yaml, inner, AiServiceSettings.PODCAST_VOICE_COHOST, speech.podcastCohost());
        append(yaml, inner, AiServiceSettings.READ_ALOUD_MODEL, speech.readAloudModel());
        append(yaml, inner, AiServiceSettings.READ_ALOUD_VOICE, speech.readAloudVoice());
        append(yaml, inner, AiServiceSettings.PODCAST_PAUSE_MILLIS, digits(speech.podcastPauseMillis()));
        append(yaml, inner, AiServiceSettings.PODCAST_SPEECH_ATTEMPTS, digits(speech.podcastAttempts()));
        append(yaml, inner, AiServiceSettings.PODCAST_SPEECH_WORKERS, digits(speech.podcastWorkers()));
        if (retryCount() != null) {
            yaml.append(inner).append("retries: ").append(retryCount()).append('\n');
        }
        if (parallelCount() != null) {
            yaml.append(inner).append("parallel: ").append(parallelCount()).append('\n');
        }
        if (tokens() != null) {
            yaml.append(inner).append("embedding-chunk-tokens: ").append(tokens()).append('\n');
        }
        if (overlap() != null) {
            yaml.append(inner).append("embedding-chunk-overlap-sentences: ").append(overlap()).append('\n');
        }
        if (timeout != null) {
            yaml.append(inner).append("timeout: ").append(timeout.toMillis()).append("ms\n");
        }
        if (!externalProcessingSources.isEmpty()) {
            yaml.append(inner).append("external-processing-sources:\n");
            externalProcessingSources.forEach(id -> yaml.append(inner).append("  - ").append(quote(id)).append('\n'));
        }
        return yaml.toString();
    }

    private static void append(StringBuilder yaml, String indent, String key, String value) {
        if (value != null) {
            yaml.append(indent).append(key).append(": ").append(quote(value)).append('\n');
        }
    }

    private static void seconds(StringBuilder yaml, String indent, String key, Duration value) {
        if (value != null) {
            yaml.append(indent).append(key).append(": ").append(value.toMillis()).append("ms\n");
        }
    }

    /** Dieselben Eingaben mit anderen Fristen der KI-Aufgaben. */
    public AiDraft withTimeouts(AiServiceSettings.Timeouts timeouts) {
        return new AiDraft(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout,
                externalProcessingSources, rerankModel, chunkTokens, chunkOverlapSentences, visionModel,
                chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking, imageThinking, summary, summaryPlan,
                podcast, imagePlan, deadline, speech, diagnosis, diagnosisEnabled, timeouts);
    }

    private static String digits(Integer value) {
        return value == null ? null : value.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean parsable(String uri) {
        try {
            return new URI(uri).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
