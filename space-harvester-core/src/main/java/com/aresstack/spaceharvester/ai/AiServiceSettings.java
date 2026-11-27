package com.aresstack.spaceharvester.ai;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Der KI-Dienst (ADR 0011): ein OpenAI-kompatibler Endpunkt ({@code /models}, {@code /embeddings}), wie im Enterprise AI
 * Client. Der Core liest keine Umgebungsvariablen und keine Dateien; Server (Spring-Properties) und Desktop-App
 * (Einstellungen, API-Key aus dem Tresor) übergeben die Werte, am einfachsten als {@link Properties} über
 * {@link #fromProperties}.
 *
 * @param baseUrl                   Basisadresse, z. B. {@code https://ki.example.org/v1}; ohne gibt es keinen Dienst
 * @param apiKey                    Bearer-Token oder {@code null}; erscheint nie in {@link #toString()}, Logs oder Fehlern
 * @param chatModel                 Chat-Modell für Suchvarianten (ADR 0017) oder {@code null}
 * @param embeddingModel            Embedding-Modell oder {@code null}; ohne gibt es keine semantische Suche
 * @param embeddingDimensions       Länge der Vektoren des Embedding-Modells; Pflicht mit {@code embeddingModel}, die
 *                                  Oberfläche misst sie einmal mit {@code Harvester.aiEmbeddingDimension}
 * @param timeout                   Lesefrist je Anfrage an den Dienst
 * @param trustBundle               Vertrauensquelle für den Dienst wie {@code trust-bundle} einer Quelle oder
 *                                  {@code null} für das Vertrauen der JVM
 * @param externalProcessingSources Quellen, deren Seiteninhalte an den Dienst gehen dürfen. Das Leserecht eines
 *                                  Benutzers genügt dafür nicht: ohne Eintrag verlässt kein Inhalt dieser Quelle das
 *                                  Haus, und ihre Seiten bekommen keine Embeddings. Suchanfragen selbst gehen immer an
 *                                  den Dienst, wenn die semantische Suche benutzt wird.
 * @param rerankModel               Rerank-Modell ({@code POST /rerank}, #181) oder {@code null}; mit ihm ordnet ein
 *                                  Cross-Encoder die Treffer der semantischen Suche um, aus freigegebenen Quellen
 * @param visionModel               Vision-Modell für Bildbeschreibungen ({@code POST /chat/completions} mit Bild,
 *                                  ADR 0021) oder {@code null}; wie {@code model.vision} im Enterprise AI Client. Ohne
 *                                  bleibt die Bildanalyse aus; Bilder gehen nur aus freigegebenen Quellen an den Dienst
 * @param chatMaxTokens             Ausgabelimit einer Antwort des Chat-Modells in Tokens, gesendet als
 *                                  {@code max_tokens}; {@code null} heißt {@link #DEFAULT_CHAT_MAX_TOKENS}. Endet eine
 *                                  Antwort an diesem Limit ({@code finish_reason = length}), ist sie unvollständig
 * @param provider                  Art des Dienstes; {@code null} heißt {@link AiProvider#OPENAI_COMPATIBLE}. Bei
 *                                  {@link AiProvider#OLLAMA} ist {@code baseUrl} der Ollama-Server, und Chat,
 *                                  Embeddings und Bilder gehen an {@link #apiBaseUrl()}
 * @param contextTokens             Kontextlänge je Anfrage an einen Ollama-Server ({@code num_ctx}); {@code null} heißt
 *                                  {@link #DEFAULT_CONTEXT_TOKENS}. Ohne sie lädt Ollama ein Modell mit dessen voller
 *                                  Kontextlänge, die oft nicht in den Grafikspeicher passt
 * @param loadTimeout               So lange darf ein Ollama-Server ein Modell laden, bevor die App aufgibt;
 *                                  {@code null} heißt {@link #DEFAULT_LOAD_TIMEOUT}. Das Laden dauert oft länger als
 *                                  die Lesefrist einer Anfrage
 * @param retries                   So oft wird eine fehlgeschlagene Bildbeschreibung wiederholt; {@code null} heißt
 *                                  {@link #DEFAULT_RETRIES}
 * @param loadPollInterval          In diesem Abstand fragt die App einen Ollama-Server ({@code GET /api/ps}), ob das
 *                                  Modell nach dem Anstoß zum Laden läuft; {@code null} heißt
 *                                  {@link #DEFAULT_LOAD_POLL_INTERVAL}
 * @param stepThinking              Nachdenken des Chat-Modells bei Analyseschritten (Transkript korrigieren,
 *                                  Folienkommentare, Prüfschritte): {@code none}, {@code low}, {@code medium} oder
 *                                  {@code high}; {@code null} heißt {@link #DEFAULT_STEP_THINKING}. Ein Modell mit
 *                                  Nachdenk-Modus (etwa gemma4) schreibt sonst vor jeder Antwort Tausende Token
 * @param parallel                  So viele Anfragen schickt ein Auftrag höchstens zugleich an den KI-Dienst
 *                                  (Bildbeschreibungen, „In PPTX umwandeln“); {@code null} heißt
 *                                  {@link #DEFAULT_PARALLEL}. Ein Ollama-Server beantwortet so viele zugleich, wie
 *                                  {@code OLLAMA_NUM_PARALLEL} erlaubt
 * @param visionMaxTokens           Ausgabelimit einer Antwort des Vision-Modells in Tokens ({@code max_tokens}, bei
 *                                  Ollama {@code num_predict}); {@code null} heißt {@link #DEFAULT_CHAT_MAX_TOKENS}
 * @param imageModel                Modell der Bildgenerierung (SVG-Schaubilder) oder {@code null} für das Chat-Modell
 * @param imageMaxTokens            Ausgabelimit einer Bildgenerierung in Tokens; {@code null} heißt
 *                                  {@link #DEFAULT_IMAGE_MAX_TOKENS}
 * @param visionThinking            Nachdenken des Vision-Modells ({@code none}, {@code low}, {@code medium},
 *                                  {@code high}); {@code null} heißt wie {@code stepThinking}
 * @param imageThinking             Nachdenken der Bildgenerierung; {@code null} heißt wie {@code stepThinking}
 * @param summaryUse                Modell, Tokenlänge und Nachdenken der Update-Zusammenfassung; {@code null} heißt
 *                                  Chat-Modell, {@link #DEFAULT_SUMMARY_MAX_TOKENS}, Nachdenken aus
 * @param podcastUse                Modell, Tokenlänge und Nachdenken der Podcast-Texte; {@code null} heißt
 *                                  Chat-Modell, {@link #DEFAULT_PODCAST_MAX_TOKENS}, Nachdenken aus
 * @param summaryPlanUse            Modell, Tokenlänge und Nachdenken für den Plan der Update-Zusammenfassung (etwa
 *                                  ein großes Modell); {@code null} heißt Chat-Modell,
 *                                  {@link #DEFAULT_SUMMARY_PLAN_MAX_TOKENS}, Nachdenken aus
 * @param deadlineUse               Modell, Tokenlänge und Nachdenken der Fristenextraktion (ADR 0035, Zeile „Fristen“);
 *                                  {@code null} heißt Chat-Modell, {@link #DEFAULT_DEADLINE_MAX_TOKENS}, Nachdenken aus
 * @param imagePlanUse              Modell, Tokenlänge und Nachdenken für den Plan eines Schaubilds (Zerlegung in
 *                                  Teile); {@code null} heißt Chat-Modell, {@link #DEFAULT_IMAGE_PLAN_MAX_TOKENS},
 *                                  Nachdenken aus
 * @param diagnosis                 Zeile „Diagnose“ (Angelo 26.11.2026): Schalter „Diagnose per KI“ und Modell,
 *                                  Tokenlänge und Nachdenken der Diagnose; {@code null} heißt eingeschaltet mit
 *                                  {@value #DEFAULT_DIAGNOSIS_MODEL}
 * @param timeouts                  Fristen einzelner Aufgaben (Update-Zusammenfassung, Abschnittsbenennung,
 *                                  Akronymauflösung);
 *                                  {@code null} heißt überall die Vorgabe
 * @param speech                    Sprachausgabe: Modell und Stimmen des Podcasts und, getrennt davon, des Vorlesens
 *                                  (Chat, Leseansicht); {@code null} heißt überall die automatische Wahl
 */
public record AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                                Integer embeddingDimensions, Duration timeout, String trustBundle,
                                Set<String> externalProcessingSources, String rerankModel, String visionModel,
                                Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                                Duration loadTimeout, Integer retries, Duration loadPollInterval,
                                String stepThinking, Integer parallel, Integer visionMaxTokens,
                                String imageModel, Integer imageMaxTokens, String visionThinking,
                                String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                                ModelUse summaryPlanUse, ModelUse imagePlanUse,
                                ModelUse deadlineUse, Speech speech, Diagnosis diagnosis, Timeouts timeouts) {

    /** Präfix der Properties im Server und in der Konfigurationsdatei der App. */
    public static final String PREFIX = "space-harvester.ai.";

    public static final String BASE_URL = "base-url";
    public static final String API_KEY = "api-key";
    public static final String CHAT_MODEL = "chat-model";
    public static final String EMBEDDING_MODEL = "embedding-model";
    public static final String EMBEDDING_DIMENSIONS = "embedding-dimensions";
    public static final String TIMEOUT = "timeout";
    public static final String TRUST_BUNDLE = "trust-bundle";
    public static final String EXTERNAL_PROCESSING_SOURCES = "external-processing-sources";
    public static final String RERANK_MODEL = "rerank-model";
    public static final String VISION_MODEL = "vision-model";
    public static final String CHAT_MAX_TOKENS = "chat-max-tokens";
    public static final String PROVIDER = "provider";
    public static final String CONTEXT_TOKENS = "context-tokens";
    public static final String LOAD_TIMEOUT = "load-timeout";
    public static final String RETRIES = "retries";
    public static final String LOAD_POLL_INTERVAL = "load-poll-interval";
    public static final String STEP_THINKING = "step-thinking";
    public static final String PARALLEL = "parallel";
    public static final String VISION_MAX_TOKENS = "vision-max-tokens";
    public static final String IMAGE_MODEL = "image-model";
    public static final String IMAGE_MAX_TOKENS = "image-max-tokens";
    public static final String VISION_THINKING = "vision-thinking";
    public static final String IMAGE_THINKING = "image-thinking";
    public static final String SUMMARY_MODEL = "summary-model";
    public static final String SUMMARY_MAX_TOKENS = "summary-max-tokens";
    public static final String SUMMARY_THINKING = "summary-thinking";
    public static final String PODCAST_MODEL = "podcast-model";
    public static final String PODCAST_MAX_TOKENS = "podcast-max-tokens";
    public static final String PODCAST_THINKING = "podcast-thinking";
    public static final String SUMMARY_PLAN_MODEL = "summary-plan-model";
    public static final String SUMMARY_PLAN_MAX_TOKENS = "summary-plan-max-tokens";
    public static final String SUMMARY_PLAN_THINKING = "summary-plan-thinking";
    public static final String IMAGE_PLAN_MODEL = "image-plan-model";
    public static final String IMAGE_PLAN_MAX_TOKENS = "image-plan-max-tokens";
    public static final String IMAGE_PLAN_THINKING = "image-plan-thinking";
    public static final String DEADLINE_MODEL = "deadline-model";
    public static final String DEADLINE_MAX_TOKENS = "deadline-max-tokens";
    public static final String DEADLINE_THINKING = "deadline-thinking";
    public static final String DIAGNOSIS_MODEL = "diagnosis-model";
    public static final String DIAGNOSIS_MAX_TOKENS = "diagnosis-max-tokens";
    public static final String DIAGNOSIS_THINKING = "diagnosis-thinking";
    public static final String DIAGNOSIS_ENABLED = "diagnosis-enabled";
    public static final String SUMMARY_TIMEOUT = "summary-timeout";
    public static final String LABEL_TIMEOUT = "label-timeout";
    public static final String EXPANSION_TIMEOUT = "expansion-timeout";
    public static final String PODCAST_SPEECH_MODEL = "podcast-speech-model";
    public static final String PODCAST_VOICE_HOST = "podcast-voice-host";
    public static final String PODCAST_VOICE_COHOST = "podcast-voice-cohost";
    public static final String READ_ALOUD_MODEL = "read-aloud-model";
    public static final String READ_ALOUD_VOICE = "read-aloud-voice";
    public static final String PODCAST_PAUSE_MILLIS = "podcast-pause-millis";
    public static final String PODCAST_SPEECH_ATTEMPTS = "podcast-speech-attempts";
    public static final String PODCAST_SPEECH_WORKERS = "podcast-speech-workers";
    public static final String PODCAST_SECTION_CHARS = "podcast-section-chars";
    public static final String PODCAST_SECTIONS = "podcast-sections";
    /** Die Stufen des Nachdenkens, wie sie in der Datei stehen und mit jeder Anfrage mitgehen. */
    public static final List<String> THINKING_LEVELS = List.of("none", "low", "medium", "high");

    /**
     * Standard der gleichzeitigen Anfragen (Angelo, 20.11.2026: „Warum verwendet er nur eine Verbindung zum
     * KI-Server?“); änderbar mit {@link #PARALLEL}.
     */
    public static final int DEFAULT_PARALLEL = 4;
    /** Höchstens so viele gleichzeitige Anfragen. */
    public static final int MAX_PARALLEL = 32;

    /**
     * Standard des Nachdenkens bei Analyseschritten (Angelo, 20.11.2026): aus. Die Schritte brauchen eine direkte
     * Antwort; änderbar mit {@link #STEP_THINKING}.
     */
    public static final String DEFAULT_STEP_THINKING = "none";

    /** Standard des Abstands, in dem nach dem Laden gefragt wird; änderbar mit {@link #LOAD_POLL_INTERVAL}. */
    public static final Duration DEFAULT_LOAD_POLL_INTERVAL = Duration.ofSeconds(2);

    /** Standard der Ladefrist eines Modells bei Ollama; der Benutzer ändert sie mit {@link #LOAD_TIMEOUT}. */
    public static final Duration DEFAULT_LOAD_TIMEOUT = Duration.ofMinutes(10);
    /** Größte Ladefrist. */
    public static final Duration MAX_LOAD_TIMEOUT = Duration.ofHours(1);
    /** Standard der Wiederholungen einer fehlgeschlagenen Bildbeschreibung; änderbar mit {@link #RETRIES}. */
    public static final int DEFAULT_RETRIES = 2;
    /** Höchstens so viele Wiederholungen. */
    public static final int MAX_RETRIES = 10;

    /** Standard der Kontextlänge bei Ollama, wie {@code ModelContextSizes.OTHER_MODELS} in AskAI. */
    public static final int DEFAULT_CONTEXT_TOKENS = 8192;
    /** Kleinste und größte Kontextlänge. */
    public static final int MIN_CONTEXT_TOKENS = 512;
    public static final int MAX_CONTEXT_TOKENS = 1_048_576;

    /** Standard des Ausgabelimits: OpenAI-kompatible Dienste setzen ohne {@code max_tokens} oft ein knappes eigenes. */
    public static final int DEFAULT_CHAT_MAX_TOKENS = 8192;
    /** Obergrenze von {@link #CHAT_MAX_TOKENS}. */
    public static final int MAX_CHAT_MAX_TOKENS = 1_000_000;

    /**
     * Ausgabelimit einer Bildgenerierung (SVG-Schaubilder), höher als beim Text-Modell, wie {@code chat.maxTokens} in
     * 3D4J: Ein Schaubild als SVG ist lang, und bei 8192 brach die Antwort meist ab.
     */
    public static final int DEFAULT_IMAGE_MAX_TOKENS = 16384;

    /**
     * Tokenlänge der Update-Zusammenfassung (Angelo 22.11.2026): Eine Zusammenfassung über viele Seiten ist lang;
     * Nachdenken ist dort standardmäßig aus ({@link #DEFAULT_SUMMARY_THINKING}), es verbrauchte sonst die Tokenlänge.
     */
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 32768;
    public static final String DEFAULT_SUMMARY_THINKING = "none";
    /** Tokenlänge der Podcast-Texte (Plan und Sprechertexte je Abschnitt); Nachdenken standardmäßig aus. */
    public static final int DEFAULT_PODCAST_MAX_TOKENS = 16384;
    public static final String DEFAULT_PODCAST_THINKING = "none";
    /** Tokenlänge des Plans der Update-Zusammenfassung (Angelo 22.11.2026: Plan gern mit anderem Modell). */
    public static final int DEFAULT_SUMMARY_PLAN_MAX_TOKENS = 16384;
    public static final String DEFAULT_SUMMARY_PLAN_THINKING = "none";
    /** Tokenlänge des Plans eines Schaubilds (Angelo 22.11.2026: Schaubild planen, dann in Teile zerlegen). */
    public static final int DEFAULT_IMAGE_PLAN_MAX_TOKENS = 16384;
    public static final String DEFAULT_IMAGE_PLAN_THINKING = "none";
    /** Tokenlänge der Fristenextraktion (ADR 0035): JSON mit Zitaten je Sachverhalt; Nachdenken standardmäßig aus. */
    public static final int DEFAULT_DEADLINE_MAX_TOKENS = 8192;
    public static final String DEFAULT_DEADLINE_THINKING = "none";
    /** Vorbelegung des Modells der Diagnose (Angelo 26.11.2026: „vorbelegen, GPT OSS“); änderbar. */
    public static final String DEFAULT_DIAGNOSIS_MODEL = "gpt-oss-120b";

    /**
     * Ein Einsatz eines Modells mit eigener Zeile in den Einstellungen: Modell ({@code null} = Chat-Modell),
     * Tokenlänge und Nachdenken ({@code null} = Standard des Einsatzes).
     */
    public record ModelUse(String model, Integer maxTokens, String thinking) {

        /** Nichts eingestellt: überall der Standard. */
        public static final ModelUse DEFAULT = new ModelUse(null, null, null);

        public ModelUse {
            model = model == null || model.isBlank() ? null : model.strip();
            if (maxTokens != null && (maxTokens < 1 || maxTokens > MAX_CHAT_MAX_TOKENS)) {
                throw new IllegalArgumentException("Tokenlänge zwischen 1 und " + MAX_CHAT_MAX_TOKENS);
            }
            if (thinking != null && thinking.isBlank()) {
                thinking = null;
            }
            if (thinking != null) {
                thinking = thinking.strip().toLowerCase(java.util.Locale.ROOT);
                if (!THINKING_LEVELS.contains(thinking)) {
                    throw new IllegalArgumentException("Nachdenken ist none, low, medium oder high");
                }
            }
        }
    }

    /**
     * Fristen einzelner KI-Aufgaben (Angelo 26.11.2026: keine fest eingebauten Werte). Leer heißt jeweils die Vorgabe.
     *
     * @param summary   je Anfrage der Update-Zusammenfassung ({@code summary-timeout}); ein längeres {@code timeout}
     *                  gewinnt; Vorgabe {@link #DEFAULT_SUMMARY}
     * @param label     je Anfrage der Abschnittsbenennung der Dokumentnavigation ({@code label-timeout}), unabhängig
     *                  von {@code timeout}; Vorgabe {@link #DEFAULT_LABEL}
     * @param expansion je Anfrage der Akronymauflösung ({@code expansion-timeout}), unabhängig von {@code timeout};
     *                  Vorgabe {@link #DEFAULT_EXPANSION}
     */
    public record Timeouts(Duration summary, Duration label, Duration expansion) {

        /** Nichts eingestellt: überall die Vorgabe. */
        public static final Timeouts DEFAULT = new Timeouts(null, null, null);
        public static final Duration DEFAULT_SUMMARY = Duration.ofMinutes(5);
        public static final Duration DEFAULT_LABEL = Duration.ofSeconds(60);
        public static final Duration DEFAULT_EXPANSION = Duration.ofSeconds(90);
        /** Längste Frist einer Aufgabe: ein Socket-Timeout in Millisekunden passt in ein {@code int}. */
        public static final Duration MAX_TASK_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

        public Timeouts {
            check(SUMMARY_TIMEOUT, summary);
            check(LABEL_TIMEOUT, label);
            check(EXPANSION_TIMEOUT, expansion);
        }

        private static void check(String key, Duration value) {
            if (value != null && (value.isNegative() || value.isZero() || value.compareTo(MAX_TASK_TIMEOUT) > 0)) {
                throw new IllegalArgumentException(PREFIX + key + " muss zwischen 1 ms und "
                        + MAX_TASK_TIMEOUT.toDays() + " Tagen liegen");
            }
        }

        /** Frist der Zusammenfassung: eingestellt oder {@link #DEFAULT_SUMMARY}. */
        public Duration summaryTimeout() {
            return summary == null ? DEFAULT_SUMMARY : summary;
        }

        /** Frist der Abschnittsbenennung: eingestellt oder {@link #DEFAULT_LABEL}. */
        public Duration labelTimeout() {
            return label == null ? DEFAULT_LABEL : label;
        }

        /** Frist der Akronymauflösung: eingestellt oder {@link #DEFAULT_EXPANSION}. */
        public Duration expansionTimeout() {
            return expansion == null ? DEFAULT_EXPANSION : expansion;
        }

        /** Ob nichts eingestellt ist. */
        public boolean empty() {
            return summary == null && label == null && expansion == null;
        }
    }

    /**
     * Diagnose per KI (Angelo 26.11.2026): ein Schalter für alle Erweiterungen, mit denen die KI Fehler und Daten
     * analysiert (etwa die MVS-Binärdiagnose im Kontextmenü und ihre Werkzeuge im Chat), und das Modell dafür.
     * Ausgeschaltet verschwinden diese Erweiterungen; das Modell ist vorbelegt und änderbar.
     *
     * @param enabled Schalter „Diagnose per KI“ ({@code diagnosis-enabled}); {@code null} heißt eingeschaltet, damit
     *                nichts bisher Sichtbares verschwindet
     * @param use     Modell, Tokenlänge und Nachdenken ({@code diagnosis-model}, {@code -max-tokens},
     *                {@code -thinking}); ohne Modell gilt {@link #DEFAULT_DIAGNOSIS_MODEL}
     */
    public record Diagnosis(Boolean enabled, ModelUse use) {

        /** Nichts eingestellt: eingeschaltet, vorbelegtes Modell. */
        public static final Diagnosis DEFAULT = new Diagnosis(null, null);

        public Diagnosis {
            use = use == null ? ModelUse.DEFAULT : use;
        }

        /** Ob die Diagnose per KI eingeschaltet ist. */
        public boolean on() {
            return enabled == null || enabled;
        }

        /** Das Modell der Diagnose: eingestellt oder {@link #DEFAULT_DIAGNOSIS_MODEL}. */
        public String model() {
            return use.model() == null ? DEFAULT_DIAGNOSIS_MODEL : use.model();
        }

        /**
         * Ob {@code name} das Modell der Diagnose ist; ein Anbieter-Präfix wie {@code openai/} zählt nicht,
         * Groß-/Kleinschreibung auch nicht.
         */
        public boolean isModel(String name) {
            return name != null && bare(name).equalsIgnoreCase(bare(model()));
        }

        private static String bare(String name) {
            String text = name.strip();
            int slash = text.lastIndexOf('/');
            return slash < 0 ? text : text.substring(slash + 1);
        }
    }

    /**
     * Sprachausgabe (Angelo 24.11.2026: „für jeden eine eigene Einstellung“): Podcast und Vorlesen (Chat und
     * Leseansicht) haben je ein eigenes TTS-Modell und eigene Stimmen. Leer heißt automatisch: das bevorzugte
     * TTS-Modell des Dienstes und die Standardstimmen seiner Familie ({@code tts-voices.properties}).
     *
     * @param podcastModel   TTS-Modell des Podcasts ({@code podcast-speech-model})
     * @param podcastHost    Stimme des Gastgebers ({@code podcast-voice-host})
     * @param podcastCohost  Stimme des Co-Hosts ({@code podcast-voice-cohost})
     * @param readAloudModel TTS-Modell des Vorlesens ({@code read-aloud-model})
     * @param readAloudVoice Stimme des Vorlesens ({@code read-aloud-voice})
     * @param podcastPauseMillis Pause zwischen zwei Beiträgen des Podcasts in Millisekunden
     *                       ({@code podcast-pause-millis}); {@code null}: {@link #DEFAULT_PODCAST_PAUSE_MILLIS}
     * @param podcastAttempts Versuche je Stück beim Vertonen des Podcasts ({@code podcast-speech-attempts});
     *                       {@code null}: {@link #DEFAULT_PODCAST_ATTEMPTS}
     * @param podcastWorkers gleichzeitige Synthesen je Beitrag des Podcasts ({@code podcast-speech-workers});
     *                       {@code null}: {@link #DEFAULT_PODCAST_WORKERS}
     * @param podcastSectionChars so viele Zeichen eines Abschnitts einer Seite liest der Erklär-Podcast
     *                       ({@code podcast-section-chars}); {@code null}: {@link #DEFAULT_PODCAST_SECTION_CHARS}
     * @param podcastSections so viele Abschnitte einer Seite werden zu eigenen Teilen, der Rest hängt am letzten
     *                       ({@code podcast-sections}); {@code null}: {@link #DEFAULT_PODCAST_SECTIONS}
     */
    public record Speech(String podcastModel, String podcastHost, String podcastCohost, String readAloudModel,
                         String readAloudVoice, Integer podcastPauseMillis, Integer podcastAttempts,
                         Integer podcastWorkers, Integer podcastSectionChars, Integer podcastSections) {

        /** Vorgabe der Pause zwischen zwei Beiträgen des Podcasts. */
        public static final int DEFAULT_PODCAST_PAUSE_MILLIS = 350;
        /** Längste einstellbare Pause zwischen zwei Beiträgen. */
        public static final int MAX_PODCAST_PAUSE_MILLIS = 5000;
        /** Vorgabe der Versuche je Stück beim Vertonen des Podcasts. */
        public static final int DEFAULT_PODCAST_ATTEMPTS = 3;
        /** Höchstens so viele Versuche je Stück (wie der Regler des Vorlese-Ablaufs im Enterprise AI Client). */
        public static final int MAX_PODCAST_ATTEMPTS = 5;
        /** Vorgabe der gleichzeitigen Synthesen je Beitrag des Podcasts. */
        public static final int DEFAULT_PODCAST_WORKERS = 2;
        /** Höchstens so viele gleichzeitige Synthesen je Beitrag (wie im Vorlese-Ablauf des Enterprise AI Client). */
        public static final int MAX_PODCAST_WORKERS = 8;
        /** Vorgabe: so viele Zeichen eines Abschnitts einer Seite liest der Erklär-Podcast (Angelo 27.11.2026). */
        public static final int DEFAULT_PODCAST_SECTION_CHARS = 3000;
        /** Vorgabe: so viele Abschnitte einer Seite werden zu eigenen Teilen des Erklär-Podcasts. */
        public static final int DEFAULT_PODCAST_SECTIONS = 10;

        /** Nichts eingestellt: Modell, Stimmen und Sprechwerte wählt die App. */
        public static final Speech AUTOMATIC = new Speech(null, null, null, null, null, null, null, null, null,
                null);

        public Speech {
            podcastModel = strip(podcastModel);
            podcastHost = strip(podcastHost);
            podcastCohost = strip(podcastCohost);
            readAloudModel = strip(readAloudModel);
            readAloudVoice = strip(readAloudVoice);
            check(podcastPauseMillis, 0, MAX_PODCAST_PAUSE_MILLIS, PODCAST_PAUSE_MILLIS);
            check(podcastAttempts, 1, MAX_PODCAST_ATTEMPTS, PODCAST_SPEECH_ATTEMPTS);
            check(podcastWorkers, 1, MAX_PODCAST_WORKERS, PODCAST_SPEECH_WORKERS);
            check(podcastSectionChars, 1, Integer.MAX_VALUE, PODCAST_SECTION_CHARS);
            check(podcastSections, 1, Integer.MAX_VALUE, PODCAST_SECTIONS);
        }

        /** Ohne eigene Lesemengen des Erklär-Podcasts. */
        public Speech(String podcastModel, String podcastHost, String podcastCohost, String readAloudModel,
                      String readAloudVoice, Integer podcastPauseMillis, Integer podcastAttempts,
                      Integer podcastWorkers) {
            this(podcastModel, podcastHost, podcastCohost, readAloudModel, readAloudVoice, podcastPauseMillis,
                    podcastAttempts, podcastWorkers, null, null);
        }

        /** Nur Modell und Stimmen; Pause, Versuche und Synthesen nach Vorgabe. */
        public Speech(String podcastModel, String podcastHost, String podcastCohost, String readAloudModel,
                      String readAloudVoice) {
            this(podcastModel, podcastHost, podcastCohost, readAloudModel, readAloudVoice, null, null, null);
        }

        /** Dieselben Modelle und Stimmen mit anderen Sprechwerten des Podcasts; {@code null} heißt Vorgabe. */
        public Speech withPodcastTiming(Integer pauseMillis, Integer attempts, Integer workers) {
            return new Speech(podcastModel, podcastHost, podcastCohost, readAloudModel, readAloudVoice, pauseMillis,
                    attempts, workers, podcastSectionChars, podcastSections);
        }

        /** Dieselben Werte mit anderen Lesemengen des Erklär-Podcasts; {@code null} heißt Vorgabe. */
        public Speech withPodcastReading(Integer sectionChars, Integer sections) {
            return new Speech(podcastModel, podcastHost, podcastCohost, readAloudModel, readAloudVoice,
                    podcastPauseMillis, podcastAttempts, podcastWorkers, sectionChars, sections);
        }

        /** Dasselbe mit einem anderen TTS-Modell des Podcasts. */
        public Speech withPodcastModel(String model) {
            return new Speech(model, podcastHost, podcastCohost, readAloudModel, readAloudVoice, podcastPauseMillis,
                    podcastAttempts, podcastWorkers, podcastSectionChars, podcastSections);
        }

        /** Die wirksame Zahl Zeichen, die der Erklär-Podcast von einem Abschnitt einer Seite liest. */
        public int podcastSectionLength() {
            return podcastSectionChars == null ? DEFAULT_PODCAST_SECTION_CHARS : podcastSectionChars;
        }

        /** Die wirksame Zahl Abschnitte einer Seite, die zu eigenen Teilen des Erklär-Podcasts werden. */
        public int podcastSectionCount() {
            return podcastSections == null ? DEFAULT_PODCAST_SECTIONS : podcastSections;
        }

        /** Die wirksame Pause zwischen zwei Beiträgen des Podcasts in Millisekunden. */
        public int podcastPause() {
            return podcastPauseMillis == null ? DEFAULT_PODCAST_PAUSE_MILLIS : podcastPauseMillis;
        }

        /** Die wirksamen Versuche je Stück beim Vertonen des Podcasts. */
        public int podcastSynthesisAttempts() {
            return podcastAttempts == null ? DEFAULT_PODCAST_ATTEMPTS : podcastAttempts;
        }

        /** Die wirksamen gleichzeitigen Synthesen je Beitrag des Podcasts. */
        public int podcastSynthesisWorkers() {
            return podcastWorkers == null ? DEFAULT_PODCAST_WORKERS : podcastWorkers;
        }

        // Eigene Meldung ohne den Wert, wie die übrigen Prüfungen; PREFIX ist eine Konstante und lädt nichts nach.
        private static void check(Integer value, int min, int max, String key) {
            if (value != null && (value < min || value > max)) {
                throw new IllegalArgumentException(PREFIX + key + " muss zwischen " + min + " und " + max + " liegen");
            }
        }

        // Eigene Hilfe statt der äußeren: Sonst lädt AUTOMATIC die äußere Klasse, deren NONE dann ein noch leeres
        // AUTOMATIC sähe.
        private static String strip(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        /** Ob nichts eingestellt ist. */
        public boolean automatic() {
            return equals(AUTOMATIC);
        }
    }

    /** Die Auswahl der Tokenlänge in den Einstellungen, wie die Knöpfe in 3D4J; jede andere Zahl ist erlaubt. */
    public static final List<Integer> TOKEN_CHOICES = List.of(2048, 8192, 16384, 32768, 65536);

    /** Standard der Lesefrist. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Kein Dienst eingerichtet. */
    public static final AiServiceSettings NONE = new AiServiceSettings(null, null, null, null, null, null, null,
            Set.of(), null, null, null, null, null, null, null, null, null, null, null, null, null);

    public AiServiceSettings {
        if (baseUrl != null && !("https".equalsIgnoreCase(baseUrl.getScheme())
                || "http".equalsIgnoreCase(baseUrl.getScheme())) || baseUrl != null && baseUrl.getHost() == null) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " muss eine http(s)-Adresse sein");
        }
        if (baseUrl != null && baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " darf keine Zugangsdaten enthalten");
        }
        apiKey = blankToNull(apiKey);
        chatModel = blankToNull(chatModel);
        embeddingModel = blankToNull(embeddingModel);
        trustBundle = blankToNull(trustBundle);
        if (embeddingDimensions != null && (embeddingDimensions < 1 || embeddingDimensions > 4096)) {
            throw new IllegalArgumentException(PREFIX + EMBEDDING_DIMENSIONS + " muss zwischen 1 und 4096 liegen");
        }
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(PREFIX + TIMEOUT + " muss zwischen 1 ms und 10 min liegen");
        }
        externalProcessingSources = externalProcessingSources == null ? Set.of()
                : Set.copyOf(externalProcessingSources);
        rerankModel = blankToNull(rerankModel);
        visionModel = blankToNull(visionModel);
        chatMaxTokens = chatMaxTokens == null ? DEFAULT_CHAT_MAX_TOKENS : chatMaxTokens;
        if (chatMaxTokens < 1 || chatMaxTokens > MAX_CHAT_MAX_TOKENS) {
            throw new IllegalArgumentException(PREFIX + CHAT_MAX_TOKENS + " muss zwischen 1 und "
                    + MAX_CHAT_MAX_TOKENS + " liegen");
        }
        provider = provider == null ? AiProvider.OPENAI_COMPATIBLE : provider;
        contextTokens = contextTokens == null ? DEFAULT_CONTEXT_TOKENS : contextTokens;
        if (contextTokens < MIN_CONTEXT_TOKENS || contextTokens > MAX_CONTEXT_TOKENS) {
            throw new IllegalArgumentException(PREFIX + CONTEXT_TOKENS + " muss zwischen " + MIN_CONTEXT_TOKENS
                    + " und " + MAX_CONTEXT_TOKENS + " liegen");
        }
        loadTimeout = loadTimeout == null ? DEFAULT_LOAD_TIMEOUT : loadTimeout;
        if (loadTimeout.isNegative() || loadTimeout.isZero() || loadTimeout.compareTo(MAX_LOAD_TIMEOUT) > 0) {
            throw new IllegalArgumentException(PREFIX + LOAD_TIMEOUT + " muss zwischen 1 ms und "
                    + MAX_LOAD_TIMEOUT.toMinutes() + " min liegen");
        }
        retries = retries == null ? DEFAULT_RETRIES : retries;
        if (retries < 0 || retries > MAX_RETRIES) {
            throw new IllegalArgumentException(PREFIX + RETRIES + " muss zwischen 0 und " + MAX_RETRIES + " liegen");
        }
        loadPollInterval = loadPollInterval == null ? DEFAULT_LOAD_POLL_INTERVAL : loadPollInterval;
        if (loadPollInterval.isNegative() || loadPollInterval.isZero()
                || loadPollInterval.compareTo(loadTimeout) > 0) {
            throw new IllegalArgumentException(PREFIX + LOAD_POLL_INTERVAL + " muss zwischen 1 ms und der Ladefrist "
                    + "liegen");
        }
        stepThinking = stepThinking == null || stepThinking.isBlank() ? DEFAULT_STEP_THINKING
                : stepThinking.strip().toLowerCase(java.util.Locale.ROOT);
        if (!stepThinking.matches("none|low|medium|high")) {
            throw new IllegalArgumentException(PREFIX + STEP_THINKING + " ist none, low, medium oder high");
        }
        parallel = parallel == null ? DEFAULT_PARALLEL : parallel;
        if (parallel < 1 || parallel > MAX_PARALLEL) {
            throw new IllegalArgumentException(PREFIX + PARALLEL + " muss zwischen 1 und " + MAX_PARALLEL + " liegen");
        }
        visionMaxTokens = visionMaxTokens == null ? DEFAULT_CHAT_MAX_TOKENS : visionMaxTokens;
        if (visionMaxTokens < 1 || visionMaxTokens > MAX_CHAT_MAX_TOKENS) {
            throw new IllegalArgumentException(PREFIX + VISION_MAX_TOKENS + " muss zwischen 1 und "
                    + MAX_CHAT_MAX_TOKENS + " liegen");
        }
        imageModel = blankToNull(imageModel);
        imageMaxTokens = imageMaxTokens == null ? DEFAULT_IMAGE_MAX_TOKENS : imageMaxTokens;
        if (imageMaxTokens < 1 || imageMaxTokens > MAX_CHAT_MAX_TOKENS) {
            throw new IllegalArgumentException(PREFIX + IMAGE_MAX_TOKENS + " muss zwischen 1 und "
                    + MAX_CHAT_MAX_TOKENS + " liegen");
        }
        visionThinking = thinking(visionThinking, VISION_THINKING);
        imageThinking = thinking(imageThinking, IMAGE_THINKING);
        summaryUse = summaryUse == null ? ModelUse.DEFAULT : summaryUse;
        podcastUse = podcastUse == null ? ModelUse.DEFAULT : podcastUse;
        summaryPlanUse = summaryPlanUse == null ? ModelUse.DEFAULT : summaryPlanUse;
        imagePlanUse = imagePlanUse == null ? ModelUse.DEFAULT : imagePlanUse;
        deadlineUse = deadlineUse == null ? ModelUse.DEFAULT : deadlineUse;
        speech = speech == null ? new Speech(null, null, null, null, null, null, null, null, null, null) : speech;
        diagnosis = diagnosis == null ? Diagnosis.DEFAULT : diagnosis;
        timeouts = timeouts == null ? Timeouts.DEFAULT : timeouts;
    }

    /** Ohne eigene Zeile der Diagnose: es gilt {@link #DEFAULT_DIAGNOSIS_MODEL}. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                             ModelUse summaryPlanUse, ModelUse imagePlanUse, ModelUse deadlineUse, Speech speech) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse,
                deadlineUse, speech, null);
    }

    /** Ohne eigene Fristen der Aufgaben: überall die Vorgabe. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                             ModelUse summaryPlanUse, ModelUse imagePlanUse, ModelUse deadlineUse, Speech speech,
                             Diagnosis diagnosis) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse,
                deadlineUse, speech, diagnosis, null);
    }

    /** Ohne eigene Einstellungen der Sprachausgabe: Modell und Stimmen wählt die App selbst. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                             ModelUse summaryPlanUse, ModelUse imagePlanUse, ModelUse deadlineUse) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse,
                deadlineUse, null);
    }

    /** Ohne eigene Zeile der Fristenextraktion. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                             ModelUse summaryPlanUse, ModelUse imagePlanUse) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse,
                null);
    }

    /** Ohne eigenen Plan der Schaubilder. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse,
                             ModelUse summaryPlanUse) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, null);
    }

    /** Ohne eigenen Plan der Zusammenfassung. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking, ModelUse summaryUse, ModelUse podcastUse) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, summaryUse, podcastUse, null);
    }

    /** Ohne eigene Einstellungen für Zusammenfassung und Podcast: deren Standards. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens,
                             String visionThinking, String imageThinking) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, visionThinking, imageThinking, null, null);
    }

    /** Ohne eigenes Nachdenken für Vision-Modell und Bildgenerierung: beide wie {@code stepThinking}. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel, Integer visionMaxTokens, String imageModel, Integer imageMaxTokens) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel,
                imageMaxTokens, null, null);
    }

    /** Eine Stufe des Nachdenkens, klein geschrieben; leer heißt {@code null} (wie das Text-Modell). */
    private static String thinking(String value, String key) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String level = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!THINKING_LEVELS.contains(level)) {
            throw new IllegalArgumentException(PREFIX + key + " ist none, low, medium oder high");
        }
        return level;
    }

    /** Das Nachdenken, das mit jeder Anfrage an das Vision-Modell mitgeht. */
    public String visionThinkingLevel() {
        return visionThinking != null ? visionThinking : stepThinking;
    }

    /** Das Nachdenken, das mit jeder Bildgenerierung mitgeht. */
    public String imageThinkingLevel() {
        return imageThinking != null ? imageThinking : stepThinking;
    }

    /** Ohne eigene Tokenlänge für Bilder und ohne Bildgenerierung: deren Standards. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking,
                             Integer parallel) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, parallel, null, null, null);
    }

    /** Mit dem Standard der gleichzeitigen Anfragen ({@link #DEFAULT_PARALLEL}). */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval, String stepThinking) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, stepThinking, null);
    }

    /** Ohne Vorgabe für das Nachdenken bei Analyseschritten ({@link #DEFAULT_STEP_THINKING}). */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens,
                loadTimeout, retries, loadPollInterval, null);
    }

    /** Als OpenAI-kompatibler Dienst. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel,
                             Integer chatMaxTokens) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, null, null, null, null, null);
    }

    /** Mit dem Standard des Ausgabelimits. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel, String visionModel) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, null);
    }

    /** Ohne Vision-Modell, wie vor ADR 0021. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources, String rerankModel) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, null);
    }

    /** Ohne Rerank-Modell, wie vor #181. */
    public AiServiceSettings(URI baseUrl, String apiKey, String chatModel, String embeddingModel,
                             Integer embeddingDimensions, Duration timeout, String trustBundle,
                             Set<String> externalProcessingSources) {
        this(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, null, null);
    }

    /**
     * Liest die Einstellungen aus Properties mit oder ohne {@link #PREFIX}, z. B. {@code base-url} oder
     * {@code space-harvester.ai.base-url}. Fehlende Werte bleiben leer.
     *
     * @throws IllegalArgumentException bei einem ungültigen Wert, ohne den Wert zu nennen
     */
    public static AiServiceSettings fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String base = value(properties, BASE_URL);
        String dimensions = value(properties, EMBEDDING_DIMENSIONS);
        String timeout = value(properties, TIMEOUT);
        String sources = value(properties, EXTERNAL_PROCESSING_SOURCES);
        URI baseUrl;
        try {
            baseUrl = base == null ? null : URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PREFIX + BASE_URL + " ist keine gültige Adresse");
        }
        Integer parsedDimensions;
        try {
            parsedDimensions = dimensions == null ? null : Integer.valueOf(dimensions);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + EMBEDDING_DIMENSIONS + " ist keine Zahl");
        }
        Duration parsedTimeout;
        try {
            parsedTimeout = timeout == null ? null : Duration.parse(timeout);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + TIMEOUT + " ist keine ISO-8601-Dauer wie PT30S");
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (sources != null) {
            Arrays.stream(sources.split(",")).map(String::strip).filter(id -> !id.isEmpty()).forEach(allowed::add);
        }
        String context = value(properties, CONTEXT_TOKENS);
        Integer parsedContext;
        try {
            parsedContext = context == null ? null : Integer.valueOf(context);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + CONTEXT_TOKENS + " ist keine Zahl");
        }
        String load = value(properties, LOAD_TIMEOUT);
        Duration parsedLoad;
        try {
            parsedLoad = load == null ? null : Duration.parse(load);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + LOAD_TIMEOUT + " ist keine ISO-8601-Dauer wie PT10M");
        }
        String retries = value(properties, RETRIES);
        Integer parsedRetries;
        try {
            parsedRetries = retries == null ? null : Integer.valueOf(retries);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + RETRIES + " ist keine Zahl");
        }
        String poll = value(properties, LOAD_POLL_INTERVAL);
        Duration parsedPoll;
        try {
            parsedPoll = poll == null ? null : Duration.parse(poll);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + LOAD_POLL_INTERVAL + " ist keine ISO-8601-Dauer wie PT2S");
        }
        String maxTokens = value(properties, CHAT_MAX_TOKENS);
        Integer parsedMaxTokens;
        try {
            parsedMaxTokens = maxTokens == null ? null : Integer.valueOf(maxTokens);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + CHAT_MAX_TOKENS + " ist keine Zahl");
        }
        String parallelValue = value(properties, PARALLEL);
        Integer parsedParallel;
        try {
            parsedParallel = parallelValue == null ? null : Integer.valueOf(parallelValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + PARALLEL + " ist keine Zahl");
        }
        Integer parsedVisionMaxTokens = tokens(properties, VISION_MAX_TOKENS);
        Integer parsedImageMaxTokens = tokens(properties, IMAGE_MAX_TOKENS);
        return new AiServiceSettings(baseUrl, value(properties, API_KEY), value(properties, CHAT_MODEL),
                value(properties, EMBEDDING_MODEL), parsedDimensions, parsedTimeout, value(properties, TRUST_BUNDLE),
                allowed, value(properties, RERANK_MODEL), value(properties, VISION_MODEL), parsedMaxTokens,
                AiProvider.fromKey(value(properties, PROVIDER)), parsedContext, parsedLoad, parsedRetries,
                parsedPoll, value(properties, STEP_THINKING), parsedParallel, parsedVisionMaxTokens,
                value(properties, IMAGE_MODEL), parsedImageMaxTokens, value(properties, VISION_THINKING),
                value(properties, IMAGE_THINKING), use(properties, SUMMARY_MODEL, SUMMARY_MAX_TOKENS,
                SUMMARY_THINKING), use(properties, PODCAST_MODEL, PODCAST_MAX_TOKENS, PODCAST_THINKING),
                use(properties, SUMMARY_PLAN_MODEL, SUMMARY_PLAN_MAX_TOKENS, SUMMARY_PLAN_THINKING),
                use(properties, IMAGE_PLAN_MODEL, IMAGE_PLAN_MAX_TOKENS, IMAGE_PLAN_THINKING),
                use(properties, DEADLINE_MODEL, DEADLINE_MAX_TOKENS, DEADLINE_THINKING),
                new Speech(value(properties, PODCAST_SPEECH_MODEL), value(properties, PODCAST_VOICE_HOST),
                        value(properties, PODCAST_VOICE_COHOST), value(properties, READ_ALOUD_MODEL),
                        value(properties, READ_ALOUD_VOICE), tokens(properties, PODCAST_PAUSE_MILLIS),
                        tokens(properties, PODCAST_SPEECH_ATTEMPTS), tokens(properties, PODCAST_SPEECH_WORKERS),
                        tokens(properties, PODCAST_SECTION_CHARS), tokens(properties, PODCAST_SECTIONS)),
                new Diagnosis(flag(properties, DIAGNOSIS_ENABLED),
                        use(properties, DIAGNOSIS_MODEL, DIAGNOSIS_MAX_TOKENS, DIAGNOSIS_THINKING)),
                new Timeouts(taskTimeout(properties, SUMMARY_TIMEOUT), taskTimeout(properties, LABEL_TIMEOUT),
                        taskTimeout(properties, EXPANSION_TIMEOUT)));
    }

    /** Dieselben Einstellungen für eine andere Art von Dienst. */
    public AiServiceSettings withProvider(AiProvider provider) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderer Kontextlänge bei Ollama; {@code null} heißt Standard. */
    public AiServiceSettings withContextTokens(Integer contextTokens) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderer Ladefrist bei Ollama; {@code null} heißt Standard. */
    public AiServiceSettings withLoadTimeout(Duration loadTimeout) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderer Zahl an Wiederholungen; {@code null} heißt Standard. */
    public AiServiceSettings withRetries(Integer retries) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderer Zahl gleichzeitiger Anfragen; {@code null} heißt Standard. */
    public AiServiceSettings withParallel(Integer parallel) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Nachdenken bei Analyseschritten; {@code null} heißt Standard. */
    public AiServiceSettings withStepThinking(String stepThinking) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Abstand der Frage nach dem Laden; {@code null} heißt Standard. */
    public AiServiceSettings withLoadPollInterval(Duration loadPollInterval) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Die Adresse der OpenAI-kompatiblen Schnittstelle ({@code /chat/completions}, {@code /embeddings},
     * {@code /models}): die Basisadresse selbst, bei Ollama der Server mit {@code /v1}.
     */
    public URI apiBaseUrl() {
        if (baseUrl == null || provider != AiProvider.OLLAMA) {
            return baseUrl;
        }
        return URI.create(ollamaServer() + "/v1");
    }

    /**
     * Der Ollama-Server ohne abschließenden Schrägstrich und ohne {@code /v1}, falls jemand die Adresse der
     * OpenAI-kompatiblen Schnittstelle eingetragen hat; ohne Dienst {@code null}.
     */
    public String ollamaServer() {
        if (baseUrl == null) {
            return null;
        }
        String server = baseUrl.toString();
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        if (server.endsWith("/v1")) {
            server = server.substring(0, server.length() - 3);
        }
        return server;
    }

    /** Ob ein Dienst eingerichtet ist ({@link #baseUrl}). */
    public boolean configured() {
        return baseUrl != null;
    }

    /** Ob ein Chat-Modell Suchvarianten formulieren kann: Dienst und Modell (ADR 0017). */
    public boolean chat() {
        return baseUrl != null && chatModel != null;
    }

    /** Ob Embeddings berechnet werden können: Dienst, Modell und Dimension. */
    public boolean embeddings() {
        return baseUrl != null && embeddingModel != null && embeddingDimensions != null;
    }

    /** Ob Treffer über ein Rerank-Modell umgeordnet werden können: Dienst und Modell (#181). */
    public boolean reranking() {
        return baseUrl != null && rerankModel != null;
    }

    /** Ob Bilder beschrieben werden können: Dienst und Vision-Modell (ADR 0021). */
    public boolean vision() {
        return baseUrl != null && visionModel != null;
    }

    /** Ob Inhalte dieser Quelle an den Dienst gehen dürfen. */
    public boolean mayProcessExternally(String sourceId) {
        return externalProcessingSources.contains(sourceId);
    }

    /** Dieselben Einstellungen mit dem API-Key aus einem Tresor oder Secret-Speicher. */
    public AiServiceSettings withApiKey(String key) {
        return new AiServiceSettings(baseUrl, key, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Embedding-Modell und anderer Dimension. */
    public AiServiceSettings withEmbedding(String model, Integer dimensions) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, model, dimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Rerank-Modell (#181). */
    public AiServiceSettings withRerankModel(String model) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, model, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Vision-Modell (ADR 0021). */
    public AiServiceSettings withVisionModel(String model) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, model, chatMaxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Ausgabelimit des Chat-Modells; {@code null} heißt Standard. */
    public AiServiceSettings withChatMaxTokens(Integer maxTokens) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, maxTokens, provider, contextTokens, loadTimeout, retries, loadPollInterval, stepThinking, parallel,
                visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit anderem Ausgabelimit des Vision-Modells; {@code null} heißt Standard. */
    public AiServiceSettings withVisionMaxTokens(Integer maxTokens) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, maxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Dieselben Einstellungen mit anderer Bildgenerierung: Modell ({@code null}/leer heißt das Chat-Modell) und
     * Ausgabelimit ({@code null} heißt {@link #DEFAULT_IMAGE_MAX_TOKENS}).
     */
    public AiServiceSettings withImageGeneration(String model, Integer maxTokens) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, model, maxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Dieselben Einstellungen mit eigenem Nachdenken für Vision-Modell und Bildgenerierung; {@code null}/leer heißt
     * jeweils wie das Text-Modell ({@code stepThinking}).
     */
    public AiServiceSettings withThinking(String vision, String image) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens, vision,
                image, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Dieselben Einstellungen für einen Schritt mit eigenem Modell und eigener Tokenlänge: {@code model} wird das
     * Chat-Modell, {@code maxTokens} sein Ausgabelimit; {@code null}/leer behält jeweils den eingestellten Wert.
     */
    public AiServiceSettings forChat(String model, Integer maxTokens) {
        return forChat(model, maxTokens, null);
    }

    /**
     * Wie {@link #forChat(String, Integer)}, dazu das Nachdenken des Schritts: {@code thinking} wird
     * {@code stepThinking} und geht mit jeder Anfrage mit; {@code null}/leer behält das des Text-Modells.
     */
    public AiServiceSettings forChat(String model, Integer maxTokens, String thinking) {
        String chosen = model == null || model.isBlank() ? chatModel : model.strip();
        Integer limit = maxTokens == null ? chatMaxTokens : maxTokens;
        String level = thinking == null || thinking.isBlank() ? stepThinking : thinking;
        return new AiServiceSettings(baseUrl, apiKey, chosen, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, limit, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, level, parallel, visionMaxTokens, imageModel, imageMaxTokens, visionThinking,
                imageThinking, summaryUse, podcastUse, summaryPlanUse,
                imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Die Einstellungen der Bildgenerierung (SVG-Schaubilder) als Chat: Chat-Modell = {@link #imageModel} oder, ohne
     * eigenes, das Chat-Modell; Ausgabelimit = {@link #imageMaxTokens}. Damit geht die höhere Tokenlänge bei jeder
     * Anfrage mit, und eine lange SVG-Antwort bricht nicht ab.
     */
    public AiServiceSettings imageGeneration() {
        return forChat(imageModel, imageMaxTokens, imageThinkingLevel());
    }

    /** Die Einzelteile eines Schaubilds (Zeile „Bildgenerierung – Einzelteile“); dasselbe wie {@link #imageGeneration()}. */
    public AiServiceSettings imageParts() {
        return imageGeneration();
    }

    /**
     * Dieselben Einstellungen mit eigener Update-Zusammenfassung und eigenem Podcast; {@code null} heißt jeweils
     * der Standard ({@link ModelUse#DEFAULT}).
     */
    public AiServiceSettings withUses(ModelUse summary, ModelUse podcast) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summary, podcast, summaryPlanUse, imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Die Einstellungen der Update-Zusammenfassung als Chat (Angelo 22.11.2026): Chat-Modell = {@code summary-model}
     * oder das Chat-Modell, Tokenlänge = {@code summary-max-tokens} (Standard {@value #DEFAULT_SUMMARY_MAX_TOKENS}),
     * Nachdenken = {@code summary-thinking} (Standard aus, unabhängig vom Text-Modell).
     */
    public AiServiceSettings summary() {
        return forChat(summaryUse.model(), summaryUse.maxTokens() == null ? DEFAULT_SUMMARY_MAX_TOKENS
                : summaryUse.maxTokens(), summaryUse.thinking() == null ? DEFAULT_SUMMARY_THINKING
                : summaryUse.thinking());
    }

    /** Dieselben Einstellungen mit eigenem Plan der Schaubilder; {@code null} heißt Standard. */
    public AiServiceSettings withImagePlan(ModelUse plan) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, plan, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Die Einstellungen für den Plan eines Schaubilds als Chat (Angelo 22.11.2026: „dieselbe Hierarchie … getrennt
     * einstellbar“): Chat-Modell = {@code image-plan-model}, sonst {@code image-model}, sonst das Chat-Modell, Tokenlänge =
     * {@code image-plan-max-tokens} (Standard {@value #DEFAULT_IMAGE_PLAN_MAX_TOKENS}), Nachdenken =
     * {@code image-plan-thinking} (Standard aus). Die Einzelteile zeichnet {@link #imageGeneration()}.
     */
    public AiServiceSettings imagePlan() {
        String model = imagePlanUse.model() == null || imagePlanUse.model().isBlank() ? imageModel : imagePlanUse.model();
        return forChat(model, imagePlanUse.maxTokens() == null ? DEFAULT_IMAGE_PLAN_MAX_TOKENS
                : imagePlanUse.maxTokens(), imagePlanUse.thinking() == null ? DEFAULT_IMAGE_PLAN_THINKING
                : imagePlanUse.thinking());
    }

    /** Dieselben Einstellungen mit eigener Sprachausgabe für Podcast und Vorlesen; {@code null} heißt automatisch. */
    public AiServiceSettings withSpeech(Speech speech) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse, deadlineUse,
                speech, diagnosis, timeouts);
    }

    /** Dieselben Einstellungen mit eigener Zeile der Fristenextraktion; {@code null} heißt Standard. */
    public AiServiceSettings withDeadline(ModelUse use) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse, use, speech, diagnosis, timeouts);
    }

    /**
     * Die Einstellungen der Fristenextraktion als Chat (ADR 0035, Zeile „Fristen“): Chat-Modell = {@code deadline-model}
     * oder das Chat-Modell, Tokenlänge = {@code deadline-max-tokens} (Standard {@value #DEFAULT_DEADLINE_MAX_TOKENS}),
     * Nachdenken = {@code deadline-thinking} (Standard aus).
     */
    public AiServiceSettings deadline() {
        return forChat(deadlineUse.model(), deadlineUse.maxTokens() == null ? DEFAULT_DEADLINE_MAX_TOKENS
                : deadlineUse.maxTokens(), deadlineUse.thinking() == null ? DEFAULT_DEADLINE_THINKING
                : deadlineUse.thinking());
    }

    /** Dieselben Einstellungen mit eigener Zeile der Diagnose; {@code null} heißt Vorbelegung. */
    public AiServiceSettings withDiagnosis(Diagnosis use) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse, deadlineUse, speech,
                use, timeouts);
    }

    /** Dieselben Einstellungen mit anderen Fristen der Aufgaben; {@code null} heißt Vorgabe. */
    public AiServiceSettings withTimeouts(Timeouts own) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, summaryPlanUse, imagePlanUse, deadlineUse, speech,
                diagnosis, own);
    }

    /** Dieselben Einstellungen mit eigenem Plan der Zusammenfassung; {@code null} heißt Standard. */
    public AiServiceSettings withSummaryPlan(ModelUse plan) {
        return new AiServiceSettings(baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions, timeout, trustBundle,
                externalProcessingSources, rerankModel, visionModel, chatMaxTokens, provider, contextTokens, loadTimeout,
                retries, loadPollInterval, stepThinking, parallel, visionMaxTokens, imageModel, imageMaxTokens,
                visionThinking, imageThinking, summaryUse, podcastUse, plan, imagePlanUse, deadlineUse, speech, diagnosis, timeouts);
    }

    /**
     * Die Einstellungen für den Plan der Update-Zusammenfassung als Chat (Angelo 22.11.2026: „Der Plan kann durchaus
     * auch ein anderes Modell nehmen, zum Beispiel GPT-OSS 120b“): Chat-Modell = {@code summary-plan-model} oder das
     * Chat-Modell, Tokenlänge = {@code summary-plan-max-tokens} (Standard {@value #DEFAULT_SUMMARY_PLAN_MAX_TOKENS}),
     * Nachdenken = {@code summary-plan-thinking} (Standard aus). Die Teilaufgaben laufen über {@link #summary()}.
     */
    public AiServiceSettings summaryPlan() {
        return forChat(summaryPlanUse.model(), summaryPlanUse.maxTokens() == null ? DEFAULT_SUMMARY_PLAN_MAX_TOKENS
                : summaryPlanUse.maxTokens(), summaryPlanUse.thinking() == null ? DEFAULT_SUMMARY_PLAN_THINKING
                : summaryPlanUse.thinking());
    }

    /**
     * Die Einstellungen der Podcast-Texte als Chat: Chat-Modell = {@code podcast-model} oder das Chat-Modell,
     * Tokenlänge = {@code podcast-max-tokens} (Standard {@value #DEFAULT_PODCAST_MAX_TOKENS}), Nachdenken =
     * {@code podcast-thinking} (Standard aus). Sprache (TTS) und Bilder haben eigene Einstellungen.
     */
    public AiServiceSettings podcast() {
        return forChat(podcastUse.model(), podcastUse.maxTokens() == null ? DEFAULT_PODCAST_MAX_TOKENS
                : podcastUse.maxTokens(), podcastUse.thinking() == null ? DEFAULT_PODCAST_THINKING
                : podcastUse.thinking());
    }

    /** Ohne API-Key, damit nichts davon in Logs oder Fehlermeldungen gelangt. */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add("baseUrl=" + (baseUrl == null ? null : baseUrl.getScheme() + "://" + baseUrl.getAuthority()
                + (baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath())));
        parts.add("apiKey=" + (apiKey == null ? "none" : "***"));
        parts.add("chatModel=" + chatModel);
        parts.add("embeddingModel=" + embeddingModel);
        parts.add("embeddingDimensions=" + embeddingDimensions);
        parts.add("timeout=" + timeout);
        parts.add("trustBundle=" + trustBundle);
        parts.add("externalProcessingSources=" + externalProcessingSources);
        parts.add("rerankModel=" + rerankModel);
        parts.add("visionModel=" + visionModel);
        parts.add("chatMaxTokens=" + chatMaxTokens);
        parts.add("provider=" + provider.key());
        parts.add("contextTokens=" + contextTokens);
        parts.add("loadTimeout=" + loadTimeout);
        parts.add("retries=" + retries);
        parts.add("loadPollInterval=" + loadPollInterval);
        parts.add("stepThinking=" + stepThinking);
        parts.add("parallel=" + parallel);
        parts.add("visionMaxTokens=" + visionMaxTokens);
        parts.add("imageModel=" + imageModel);
        parts.add("imageMaxTokens=" + imageMaxTokens);
        parts.add("visionThinking=" + visionThinking);
        parts.add("imageThinking=" + imageThinking);
        parts.add("summaryUse=" + summaryUse);
        parts.add("podcastUse=" + podcastUse);
        parts.add("summaryPlanUse=" + summaryPlanUse);
        parts.add("imagePlanUse=" + imagePlanUse);
        parts.add("deadlineUse=" + deadlineUse);
        parts.add("speech=" + speech);
        return "AiServiceSettings[" + String.join(", ", parts) + "]";
    }

    private static ModelUse use(Properties properties, String model, String maxTokens, String thinking) {
        Integer tokens = tokens(properties, maxTokens);
        try {
            return new ModelUse(value(properties, model), tokens, value(properties, thinking));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PREFIX + (e.getMessage().startsWith("Tokenlänge") ? maxTokens
                    : thinking) + ": " + e.getMessage());
        }
    }

    private static Integer tokens(Properties properties, String name) {
        String text = value(properties, name);
        try {
            return text == null ? null : Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + name + " ist keine Zahl");
        }
    }

    private static Duration taskTimeout(Properties properties, String name) {
        String text = value(properties, name);
        try {
            return text == null ? null : Duration.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + name + " ist keine Dauer (ISO-8601, etwa PT5M)");
        }
    }

    private static Boolean flag(Properties properties, String name) {
        String text = value(properties, name);
        if (text == null) {
            return null;
        }
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.valueOf(text);
        }
        throw new IllegalArgumentException(PREFIX + name + ": true oder false");
    }

    private static String value(Properties properties, String name) {
        String value = properties.getProperty(PREFIX + name, properties.getProperty(name));
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
