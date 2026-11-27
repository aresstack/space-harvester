package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.app.ui.controls.PasswordReveal;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.spaceharvester.app.settings.AiAccess;
import javax.swing.JComboBox;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.ui.controls.DurationField;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.ui.controls.ThinkingBox;
import com.aresstack.spaceharvester.app.ui.controls.TokenLengthBox;
import com.aresstack.spaceharvester.application.configuration.AnalyzerSettings;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.app.ui.content.AnalyzerNames;
import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.configuration.CheckSettings;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.spaceharvester.application.terms.SynonymBudget;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Einstellungen des KI-Dienstes in zwei Reitern wie im Enterprise AI Client (ADR 0011): „KI-Dienst“
 * ({@link #serviceComponent()}: Basisadresse eines OpenAI-kompatiblen Dienstes und API-Key; welche Wissensquellen
 * Inhalte an den Dienst geben dürfen, legt das Formular jeder Quelle fest, Vorgabe keine) und „Modelle“ ({@link #modelsComponent()}: zentral für alle
 * Quellen Text-, Embedding- und Rerank-Modell, Dimension und die Zerlegung in Abschnitte für Embeddings). Beide
 * speichern denselben Block. Adresse und Modelle stehen unter
 * {@code space-harvester.ai} in der Datei; der API-Key nur im Tresor der App (unter Windows DPAPI, sonst für die
 * Sitzung), nie in der Datei, im Protokoll oder in der Oberfläche. Die semantische Suche wird erst damit möglich.
 *
 * <p>„Modelle laden“ fragt die Modelle des Dienstes über die laufende Laufzeit ab (dieselbe Proxy-Route wie die
 * Quellen), auch mit noch nicht gespeicherten Eingaben; „▾“ neben den Modellfeldern bietet sie zur Auswahl an. Ohne
 * Dimension bleibt die semantische Suche aus, deshalb misst Speichern sie beim Dienst
 * ({@code Harvester#aiEmbeddingDimension}), wenn das Feld leer ist oder das Embedding-Modell wechselt.
 */
final class AiSettingsPanel {

    /** Unterordner des Tresors für den KI-Dienst. */
    static final String VAULT = "ai";
    static final String KEY_ID = "api-key";
    static final String KEY_LOGIN = "openai-compatible";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 10));
    /** „Anbieter“: OpenAI-kompatibler Dienst oder Ollama-Server ({@code provider}); Ollama braucht keinen Key. */
    final JComboBox<String> provider = Comic.combo();
    static final String OPENAI_HINT = "OpenAI-kompatibel, z. B. https://api.openai.com/v1";
    static final String OLLAMA_HINT = "Ollama-Server, z. B. " + AiProvider.OLLAMA_DEFAULT_URL
            + "; Chat, Embeddings und Bilder über dessen /v1, ohne API-Key";
    final JTextField baseUrl = Comic.field(32);
    /**
     * Kontextlänge des Chat-Modells ({@code context-tokens}): bei Ollama je Anfrage gesendet (leer: Standard des Core),
     * bei jedem Dienst die Grundlage dafür, wie viel Text der Chat je Anfrage mitgibt (leer: wie der Dienst meldet).
     */
    final JTextField contextTokens = Comic.field(8);
    static final String CONTEXT_TOKENS_LABEL = "Kontextlänge";
    static final String CONTEXT_TOKENS_HINT = "Tokens je Anfrage (num_ctx); leer: 8192. Größer braucht mehr "
            + "Grafikspeicher, Ollama lädt das Modell dafür neu";
    static final String CONTEXT_TOKENS_REMOTE_HINT = "Tokens, die das Chat-Modell je Anfrage fasst; daraus folgt, wie "
            + "viel Text der Chat mitgibt. Leer: wie der KI-Dienst es in seiner Modellliste meldet, sonst keine Grenze";
    /** So viele Sekunden darf Ollama ein Modell laden ({@code load-timeout}); leer: Standard des Core. */
    final JTextField loadTimeout = Comic.field(8);
    static final String LOAD_TIMEOUT_LABEL = "Ladefrist";
    static final String LOAD_TIMEOUT_HINT = "Sekunden, die Ollama zum Laden eines Modells haben darf; leer: "
            + com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_LOAD_TIMEOUT.toSeconds();
    /** Abstand der Frage an Ollama, ob ein angestoßenes Modell läuft ({@code load-poll-interval}). */
    final JTextField loadPollInterval = Comic.field(8);
    static final String LOAD_POLL_LABEL = "Abfrageabstand";
    static final String LOAD_POLL_HINT = "Sekunden zwischen den Fragen an Ollama (/api/ps), ob ein Modell nach dem "
            + "Anstoß zum Laden läuft; leer: "
            + com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_LOAD_POLL_INTERVAL.toSeconds();
    /** Wiederholungen einer fehlgeschlagenen Bildbeschreibung ({@code retries}); leer: Standard des Core. */
    final JTextField retries = Comic.field(4);
    static final String RETRIES_LABEL = "Wiederholungen";
    static final String RETRIES_HINT = "So oft wird ein Bild erneut beschrieben, wenn das Modell scheitert; leer: "
            + com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_RETRIES;
    /**
     * Gleichzeitige Anfragen an den KI-Dienst ({@code parallel}, Angelo 20.11.2026: „Warum verwendet er nur eine
     * Verbindung zum KI-Server?“); leer: Standard des Core.
     */
    final JTextField parallel = Comic.field(4);
    static final String PARALLEL_LABEL = "Gleichzeitige Anfragen";
    static final String PARALLEL_HINT = "So viele Anfragen schickt ein Auftrag zugleich an den KI-Dienst "
            + "(Bildbeschreibungen, „In PPTX umwandeln“); bei Ollama höchstens OLLAMA_NUM_PARALLEL; leer: "
            + com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_PARALLEL;
    /**
     * Nachdenken je Modell (Angelo 20.11. und 22.11.2026) in derselben Zeile wie Modell und Tokenlänge: Das Text-Modell
     * ({@code step-thinking}) denkt standardmäßig nicht, die übrigen folgen ihm, solange nichts anderes gewählt ist.
     * Die Stufe geht bei jeder Anfrage mit, außer ein Aufruf legt das Nachdenken aus gutem Grund selbst fest.
     */
    final ThinkingBox stepThinking = new ThinkingBox();
    static final String THINKING_LIKE_CHAT = "wie Text-Modell";
    final ThinkingBox visionThinking = new ThinkingBox(THINKING_LIKE_CHAT);
    final ThinkingBox imageThinking = new ThinkingBox(THINKING_LIKE_CHAT);
    final ThinkingBox statementThinking = new ThinkingBox(THINKING_LIKE_CHAT);
    final ThinkingBox simplifyThinking = new ThinkingBox(THINKING_LIKE_CHAT);
    final ThinkingBox checkThinking = new ThinkingBox(THINKING_LIKE_CHAT);
    final JPasswordField apiKey = new JPasswordField(32);
    final JTextField chatModel = Comic.field(24);
    final JTextField embeddingModel = Comic.field(24);
    final JTextField dimensions = Comic.field(6);
    /**
     * Welche Quellen Inhalte an den Dienst geben dürfen: je Quelle ein Häkchen (Vorgabe aus), derselbe Schlüssel wie
     * das Häkchen im Formular der Quelle; ohne Quellen eine Zeile Text.
     */
    final JPanel released = new JPanel();
    private final List<javax.swing.JCheckBox> releaseBoxes = new ArrayList<>();
    /** Die Freigaben aus der Datei (für Quellen, die gerade nicht in der Liste stehen). */
    private List<String> releasedIds = List.of();
    private Duration timeout;
    /** „Zugangsdaten beziehen über“: KeePass (Vorgabe) oder Manuell, wie im Enterprise AI Client. */
    final JComboBox<String> access = Comic.combo();
    static final String KEEPASS = "KeePass";
    static final String MANUAL = "Manuell";
    /**
     * Rechts IM Eintragsfeld: „Koppeln“, solange die App nicht gekoppelt ist (dieselbe Kopplung wie unter Sicherheit ›
     * KeePass, hier an Ort und Stelle), sonst „Einträge laden“. Die Kopplung selbst (Stand, Neu koppeln, Entkoppeln)
     * steht unter Einstellungen › Sicherheit ({@link KeePassPairingPanel}), denn sie gilt auch für die Quellen.
     */
    final ComposerButton pair = Comic.button(KeePassPairingPanel.PAIR, "Mit KeePass koppeln (KeePassRPC); KeePass "
            + "zeigt dabei ein Einmal-Passwort");
    static final String LOAD_ENTRIES = "Einträge laden";
    static final String ENTRY_HINT = "Bestehenden Eintrag wählen oder einen neuen Namen eintippen; ein neuer Eintrag "
            + "entsteht beim Speichern mit Basisadresse und API-Key";
    static final String NOT_PAIRED_HINT = "Nicht gekoppelt · „Koppeln“ verbindet mit KeePass (muss laufen, mit dem "
            + "Plugin KeePassRPC) und gilt dann auch für die Wissensquellen";
    /** Editierbar: einen bestehenden Eintrag wählen oder einen neuen Titel eintippen. */
    final JComboBox<String> entry = Comic.combo();
    /** Eintragsfeld mit dem Knopf {@link #pair} rechts IM Feld (InputFrame). */
    final JPanel entryRow = Comic.fieldWithButton(entry, pair);
    /** Wird nach einer Kopplung von hier aus gerufen, damit Sicherheit › KeePass den Stand zeigt. */
    private Runnable pairingChanged = () -> { };
    /** Die beim Koppeln gelesenen Titel; leer, solange nicht gekoppelt. */
    List<String> titles = List.of();
    /** Ob das Feld „API-Key“ gerade den aus KeePass gelesenen Key zeigt (dann zählt er nicht als eingetippt). */
    boolean keyFromKeePass;
    /** Ob der gewählte KeePass-Eintrag ein Passwort hat; {@code null}: noch nicht gelesen. */
    Boolean keePassKeyPresent;
    /** Der Eintrag, dessen Key gelesen wird oder im Feld steht; {@code null}: keiner. */
    private String keyTitle;
    /** Zählt die Lesevorgänge, damit eine späte Antwort für einen anderen Eintrag nichts überschreibt. */
    private int keyRead;
    /** Bezugsart, wie {@link #render()} sie zeigte; Maßstab für „geändert“. */
    private AiAccess shownAccess = AiAccess.NONE;
    final JTextField rerankModel = Comic.field(24);
    final JTextField visionModel = Comic.field(24);
    final JTextField chunkTokens = Comic.field(6);
    /**
     * Tokenlänge je Modell (Angelo 21.11.2026): in derselben Zeile rechts neben dem Modell eine editierbare Auswahl
     * mit den Werten aus 3D4J; geht bei jeder Anfrage als {@code max_tokens} bzw. {@code num_predict} mit.
     */
    final TokenLengthBox chatMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS);
    final TokenLengthBox visionMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS);
    /** Bildgenerierung (SVG-Schaubilder): eigenes Modell und eigene, höhere Tokenlänge. */
    final JTextField imageModel = Comic.field(24);
    final TokenLengthBox imageMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_IMAGE_MAX_TOKENS);
    /** Tokenlänge der Schritte mit eigenem Modell; „wie Text-Modell“ folgt {@code ai.chat-max-tokens}. */
    static final String TOKENS_LIKE_CHAT = THINKING_LIKE_CHAT;
    final TokenLengthBox statementMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS, TOKENS_LIKE_CHAT);
    final TokenLengthBox simplifyMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS, TOKENS_LIKE_CHAT);
    final TokenLengthBox checkMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS, TOKENS_LIKE_CHAT);
    final ComposerButton pickImage = Comic.button("▾", "Modell der Bildgenerierung aus der Liste des Dienstes wählen");
    /**
     * Eigene Zeilen für Update-Zusammenfassung, ihren Plan und den Podcast (Angelo 22.11.2026): je Modell, Tokenlänge
     * und Nachdenken mit eigenen Standards; Nachdenken standardmäßig aus, unabhängig vom Text-Modell.
     */
    final JTextField summaryModel = Comic.field(24);
    final TokenLengthBox summaryMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_SUMMARY_MAX_TOKENS);
    final ThinkingBox summaryThinking = new ThinkingBox();
    final ComposerButton pickSummary = Comic.button("▾", "Modell der Zusammenfassung aus der Liste des Dienstes wählen");
    final JTextField summaryPlanModel = Comic.field(24);
    final TokenLengthBox summaryPlanMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_SUMMARY_PLAN_MAX_TOKENS);
    final ThinkingBox summaryPlanThinking = new ThinkingBox();
    final ComposerButton pickSummaryPlan = Comic.button("▾", "Modell für den Plan der Zusammenfassung aus der Liste "
            + "des Dienstes wählen");
    final JTextField podcastModel = Comic.field(24);
    final TokenLengthBox podcastMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_PODCAST_MAX_TOKENS);
    final ThinkingBox podcastThinking = new ThinkingBox();
    final ComposerButton pickPodcast = Comic.button("▾", "Modell der Podcast-Texte aus der Liste des Dienstes wählen");
    /** Plan eines Schaubilds (Zerlegung in Teile); die Teile zeichnet die Zeile „Bildgenerierung – Einzelteile“. */
    final JTextField imagePlanModel = Comic.field(24);
    final TokenLengthBox imagePlanMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_IMAGE_PLAN_MAX_TOKENS);
    final ThinkingBox imagePlanThinking = new ThinkingBox();
    final ComposerButton pickImagePlan = Comic.button("▾", "Modell für den Plan der Schaubilder aus der Liste des "
            + "Dienstes wählen");
    /** Fristenextraktion (ADR 0035): liest Erlasse und liefert Merkmale mit wörtlichem Zitat. */
    final JTextField deadlineModel = Comic.field(24);
    final TokenLengthBox deadlineMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_DEADLINE_MAX_TOKENS);
    final ThinkingBox deadlineThinking = new ThinkingBox();
    final ComposerButton pickDeadline = Comic.button("▾", "Modell für die Fristen aus der Liste des Dienstes wählen");
    /**
     * Diagnose per KI (Angelo 26.11.2026): Schalter für alle KI-Erweiterungen der Fehler- und Datenanalyse (etwa
     * „Binärdiagnose …“ im FTP-Kontextmenü und ihre Werkzeuge im Chat) und das Modell dafür, in einer Zeile.
     */
    final javax.swing.JCheckBox diagnosisEnabled = Comic.checkBox(DIAGNOSIS_TEXT);
    final JTextField diagnosisModel = Comic.field(24);
    final TokenLengthBox diagnosisMaxTokens = new TokenLengthBox(AiServiceSettings.TOKEN_CHOICES,
            AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS, TOKENS_LIKE_CHAT);
    final ThinkingBox diagnosisThinking = new ThinkingBox();
    /** Fristen je Anfrage einzelner KI-Aufgaben (Angelo 26.11.2026: keine fest eingebauten Werte). */
    final DurationField summaryTimeout = new DurationField(TIMEOUT_MAX_AMOUNT);
    final DurationField labelTimeout = new DurationField(TIMEOUT_MAX_AMOUNT);
    final DurationField expansionTimeout = new DurationField(TIMEOUT_MAX_AMOUNT);
    final ComposerButton pickDiagnosis = Comic.button("▾", "Modell der Diagnose aus der Liste des Dienstes wählen");
    /**
     * Sprachausgabe (Angelo 24.11.2026: „für jeden eine eigene Einstellung“): Podcast und Vorlesen (Chat, Leseansicht)
     * mit je eigenem TTS-Modell und eigenen Stimmen; leer heißt automatisch.
     */
    final JTextField podcastSpeechModel = Comic.field(24);
    final ComposerButton pickPodcastSpeech = Comic.button("▾", "Sprachmodell (TTS) des Podcasts aus der Liste des "
            + "Dienstes wählen");
    final JComboBox<String> podcastHost = voiceBox();
    final JComboBox<String> podcastCohost = voiceBox();
    final JTextField readAloudModel = Comic.field(24);
    final ComposerButton pickReadAloud = Comic.button("▾", "Sprachmodell (TTS) des Vorlesens aus der Liste des "
            + "Dienstes wählen");
    final JComboBox<String> readAloudVoice = voiceBox();
    /**
     * Sprechwerte des Podcasts unter „Erweitert“ (Angelo 24.11.2026: keine fest eingebauten Werte): Pause zwischen
     * zwei Beiträgen, Versuche je Stück und gleichzeitige Synthesen je Beitrag; nur für den Podcast, nicht fürs Vorlesen.
     */
    /** Takt der Live-Spracheingabe (unter „Erweitert“, dient als Zeilenkennung fürs Auf- und Zuklappen). */
    final JSpinner liveDictationStep = countSpinner(
            com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MIN_STEP_MILLIS,
            com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MAX_STEP_MILLIS, 100);
    final JSpinner podcastPause = countSpinner(0, AiServiceSettings.Speech.MAX_PODCAST_PAUSE_MILLIS, 50,
            AiServiceSettings.Speech.DEFAULT_PODCAST_PAUSE_MILLIS);
    final JSpinner podcastAttempts = countSpinner(1, AiServiceSettings.Speech.MAX_PODCAST_ATTEMPTS, 1,
            AiServiceSettings.Speech.DEFAULT_PODCAST_ATTEMPTS);
    final JSpinner podcastWorkers = countSpinner(1, AiServiceSettings.Speech.MAX_PODCAST_WORKERS, 1,
            AiServiceSettings.Speech.DEFAULT_PODCAST_WORKERS);
    /**
     * Grenzen der Skala um den Vorlese-Knopf unter „Erweitert“ (Angelo 27.11.2026: keine fest eingebauten Werte):
     * langsamstes und schnellstes Tempo, Raster und Sätze am Ende der Sprung-Hälfte. Gespeichert im App-Zustand, gilt
     * beim nächsten Einblenden der Skala.
     */
    final JSpinner readAloudRateMin = countSpinner(
            com.aresstack.spaceharvester.app.speech.ReadAloudRate.MIN_PERCENT,
            com.aresstack.spaceharvester.app.speech.ReadAloudRate.NORMAL_PERCENT, 5);
    static final String READ_ALOUD_DIAL_LABEL = "Vorlesen – Regler";
    static final String READ_ALOUD_DIAL_HELP = "Die Skala erscheint beim Vorlesen, wenn die Maus auf den runden "
            + "Knopf kommt. Obere Hälfte: Sprechgeschwindigkeit, oben normal, links bis zum langsamsten, rechts bis zum "
            + "schnellsten Tempo, im eingestellten Raster (Doppelklick oben = normal). Untere Hälfte: nach links ziehen "
            + "springt beim Loslassen satzweise zurück, nach rechts vor, am Ende der Skala um die eingestellten Sätze.";
    static final String PODCAST_TIMING_LABEL = "Podcast – Sprechen";
    static final String PODCAST_PAUSE_UNIT = "ms Pause";
    static final String PODCAST_ATTEMPTS_UNIT = "Versuche";
    static final String PODCAST_WORKERS_UNIT = "zugleich";
    static final String PODCAST_TIMING_HELP = "Wie der Podcast vertont wird, nur für den Podcast, nicht fürs Vorlesen."
            + "\n\nms Pause: Stille zwischen zwei Wortbeiträgen; Vorgabe "
            + AiServiceSettings.Speech.DEFAULT_PODCAST_PAUSE_MILLIS + " ms.\n\nVersuche: so oft versucht die "
            + "Sprachausgabe ein Stück, bevor sie Satz für Satz spricht; Vorgabe "
            + AiServiceSettings.Speech.DEFAULT_PODCAST_ATTEMPTS + ".\n\nzugleich: so viele Stücke eines Beitrags "
            + "vertont der Dienst gleichzeitig, zusammen nie mehr als „" + PARALLEL_LABEL + "“; Vorgabe "
            + AiServiceSettings.Speech.DEFAULT_PODCAST_WORKERS + ".\n\nSchlüssel in der Konfigurationsdatei: "
            + "space-harvester.ai." + AiServiceSettings.PODCAST_PAUSE_MILLIS + ", ."
            + AiServiceSettings.PODCAST_SPEECH_ATTEMPTS + " und ." + AiServiceSettings.PODCAST_SPEECH_WORKERS + ".";
    private static final com.aresstack.spaceharvester.application.podcast.PodcastVoices VOICES =
            com.aresstack.spaceharvester.application.podcast.PodcastVoices.bundled();
    final JTextField chunkOverlap = Comic.field(6);
    final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    private final SettingDialogs.Rows modelRows;
    private final JPanel models = new JPanel(new BorderLayout(0, 10));
    final ComposerButton save = Comic.primary("Speichern", "KI-Einstellungen übernehmen; gilt ohne Neustart");
    final ComposerButton saveModels = Comic.primary("Speichern", "Modelle übernehmen; gilt ohne Neustart, nur die Zerlegung erst danach");
    final ComposerButton removeKey = Comic.button("API-Key entfernen", "Gespeicherten API-Key löschen");
    /** Meldungen im Reiter „KI-Dienst“ (im Assistenten die einzige). */
    final Feedback feedback = new Feedback();
    /** Meldungen im Reiter „Modelle“; im Assistenten dieselbe wie {@link #feedback}. */
    final Feedback modelsFeedback;
    final ComposerButton loadModels = Comic.button("Modelle laden", "Die Modelle des KI-Dienstes abfragen");
    final ComposerButton pickChat = Comic.button("▾", "Text-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickEmbedding = Comic.button("▾", "Embedding-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickRerank = Comic.button("▾", "Rerank-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickVision = Comic.button("▾", "Vision-Modell aus der Liste des Dienstes wählen");
    /** Klappt Abschnittsgröße und Überlappung auf; sie sind keine Modelle, sondern gehören zur Zerlegung. */
    final javax.swing.JButton advanced = Comic.disclosure("Erweitert",
            "Dimension, Abschnittsgröße und Überlappung für Embeddings; Abschnitte zugleich beim Vereinfachen");
    /** Stand des MaltParser-Modells (ADR 0024); nur lesbar, der Knopf steht im Feld. */
    final JTextField parserStatus = Comic.field(24);
    /**
     * Paare je Synonymlauf, die zusätzlich das Text-Modell prüft ({@code space-harvester.terms.synonyms.
     * max-judged-pairs}, #336); 0 lässt die Prüfung aus. Zahl mit Einheit, kein Freitext.
     */
    final JSpinner judgedPairs = countSpinner(0, SynonymRunForm.MAX_PAIRS, 10);
    final HelpButton judgedPairsHelp = new HelpButton(Dialogs.CLOSE_SIZE, JUDGED_PAIRS_LABEL, JUDGED_PAIRS_HELP);
    static final String JUDGED_PAIRS_LABEL = "Synonymprüfung";
    static final String JUDGED_PAIRS_UNIT = "Paare je Lauf";
    static final String JUDGED_PAIRS_HELP = "Die Synonymerkennung (Knopf „Synonyme vorschlagen“ bei den "
            + "Beziehungen) findet Kandidatenpaare über Regeln und das Embedding-Modell. Steht hier eine Zahl über "
            + "0, gehen je Lauf höchstens so viele Kandidatenpaare zusätzlich mit kurzen Fundstellen aus freigegebenen "
            + "Quellen an das Text-Modell, das die Bedeutung prüft (Synonym, Schreibvariante, verwandt). Bei 0 bleibt "
            + "die Prüfung aus; ähnliche Embedding-Paare werden dann direkt Vorschläge.\n\n"
            + "Der Wert ist die Vorbelegung des Startdialogs „Synonyme vorschlagen“ und lässt sich dort je Lauf "
            + "ändern. Er gilt ohne Neustart beim nächsten Lauf. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.terms.synonyms.max-judged-pairs.";
    /**
     * Lauf nach jedem Abgleich ({@code space-harvester.terms.synonyms.automatic}, SY1/SY3, Angelo 17.11.2026): nach
     * der Anreicherung einer Quelle und nach neuer KI-Freigabe einer Seite startet ein Synonymlauf für die Quelle;
     * aus heißt nur noch auf Klick. Gilt ohne Neustart beim nächsten Abgleich.
     */
    final javax.swing.JCheckBox automaticSynonyms = Comic.checkBox(AUTOMATIC_SYNONYMS_TEXT);
    final HelpButton automaticSynonymsHelp = new HelpButton(Dialogs.CLOSE_SIZE, AUTOMATIC_SYNONYMS_LABEL,
            AUTOMATIC_SYNONYMS_HELP);
    static final String AUTOMATIC_SYNONYMS_LABEL = "Synonyme";
    /**
     * Synonyme nur zu bestätigten Fachbegriffen ({@code space-harvester.terms.synonyms.glossary-terms-only}, K3,
     * Angelo 21.11.2026): Vorgabe für den automatischen Lauf und Vorbelegung des Startdialogs. Standard aus.
     */
    static final String GLOSSARY_SYNONYMS_TEXT = "Nur zu bestätigten Fachbegriffen";
    static final String GLOSSARY_SYNONYMS_TIP = "Synonyme nur zu bestätigten Fachbegriffen des Glossars vorschlagen; "
            + "ohne Häkchen zu allen Begriffen";
    final javax.swing.JCheckBox glossarySynonyms = Comic.checkBox(GLOSSARY_SYNONYMS_TEXT);
    static final String GLOSSARY_SYNONYMS_LABEL = "Synonyme zu";
    static final String GLOSSARY_SYNONYMS_HELP = "Mit Häkchen sucht die Synonymerkennung nur Synonyme zu Begriffen, "
            + "die du im Fachglossar als Fachbegriff bestätigt hast; ein Paar zählt, wenn mindestens einer der beiden "
            + "Begriffe ein solcher Fachbegriff ist. Ohne Häkchen (Standard) gehen alle Begriffe ein, die mit den "
            + "meisten Seiten zuerst.\n\nGilt für den Lauf nach jedem Abgleich und ist die Vorbelegung im Dialog "
            + "„Synonyme vorschlagen“, dort je Lauf änderbar. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.terms.synonyms.glossary-terms-only.";
    final HelpButton glossarySynonymsHelp = new HelpButton(Dialogs.CLOSE_SIZE, GLOSSARY_SYNONYMS_LABEL,
            GLOSSARY_SYNONYMS_HELP);
    static final String AUTOMATIC_SYNONYMS_TEXT = "Nach jedem Abgleich vorschlagen";
    /** Die Zeile auf der Abschlussseite des Assistenten. */
    static final String AUTOMATIC_SYNONYMS_ON = "nach jedem Abgleich";
    static final String AUTOMATIC_SYNONYMS_OFF = "nur auf Klick";
    static final String AUTOMATIC_SYNONYMS_HELP = "Mit Häkchen schlägt Space Harvester nach jedem Abgleich einer "
            + "Quelle und immer dann, wenn eine Seite neu für die KI freigegeben wird, von selbst Synonyme für diese "
            + "Quelle vor; der Lauf erscheint unter Aufträge. Ohne Häkchen gibt es Vorschläge nur über „Synonyme "
            + "vorschlagen“ an der Liste der Beziehungen.\n\n"
            + "Für Vorschläge aus der Bedeutung braucht es ein Embedding-Modell und für die KI freigegebene Seiten. "
            + "Fehlt eins davon, findet der Lauf nur Schreibvarianten und Stellen wie „auch genannt“ oder "
            + "„kurz: …“.\n\n"
            + "Bestätigt wird dabei nichts: Alles bleibt ein Vorschlag, den du in der Liste der Beziehungen annimmst "
            + "oder ablehnst. Was du dort entschieden hast, bleibt bestehen. Gilt ohne Neustart beim nächsten Abgleich. "
            + "Schlüssel in der Konfigurationsdatei: space-harvester.terms.synonyms.automatic.";
    /**
     * Bilder automatisch beschreiben ({@code space-harvester.images.automatic} und {@code .max-per-run}, Fahrplan B1):
     * nach dem Abgleich einer Quelle und nach neuer KI-Freigabe beschreibt ein eigener Auftrag die Bilder freigegebener
     * Seiten, höchstens so viele je Lauf. Standard aus. Gilt ohne Neustart beim nächsten Abgleich.
     */
    final javax.swing.JCheckBox automaticImages = Comic.checkBox(AUTOMATIC_IMAGES_TEXT);
    final JSpinner imagesPerRun = countSpinner(0, MAX_IMAGES_PER_RUN, 50);
    final HelpButton automaticImagesHelp = new HelpButton(Dialogs.CLOSE_SIZE, AUTOMATIC_IMAGES_LABEL,
            AUTOMATIC_IMAGES_HELP);
    static final String AUTOMATIC_IMAGES_LABEL = "Bilder";
    static final String AUTOMATIC_IMAGES_TEXT = "Nach jedem Abgleich beschreiben";
    static final String IMAGES_PER_RUN_BEFORE = "höchstens";
    static final String IMAGES_PER_RUN_AFTER = "je Lauf";
    static final int MAX_IMAGES_PER_RUN = 1_000_000;
    /** Die Zeile auf der Abschlussseite des Assistenten. */
    static final String AUTOMATIC_IMAGES_OFF = "nur auf Klick";
    static final String AUTOMATIC_IMAGES_HELP = "Mit Häkchen beschreibt Space Harvester nach jedem Abgleich einer "
            + "Quelle und immer dann, wenn eine Seite neu für die KI freigegeben wird, von selbst die Bilder dieser "
            + "Quelle mit dem Vision-Modell; der Lauf erscheint unter Aufträge als „Bilder beschreiben“ und lässt sich "
            + "dort abbrechen. Was schon beschrieben ist, bleibt.\n\n"
            + "Beschrieben werden nur Bilder von Seiten mit KI-Freigabe und nur solche, die für das eingestellte "
            + "Vision-Modell noch keine Beschreibung haben; ein Bild geht nie zweimal an das Modell. Je Lauf gehen "
            + "höchstens so viele Bilder an das Modell, wie hinter „" + IMAGES_PER_RUN_BEFORE + "“ steht (0 = "
            + "unbegrenzt); der Rest folgt beim nächsten Abgleich, und der Auftrag nennt die Zahl der offenen "
            + "Bilder. Die Grenze kann um die Bilder einer Seite überzogen werden.\n\n"
            + "Ohne Vision-Modell wird nichts beschrieben; der Auftrag endet gelb. Ohne Häkchen gibt es "
            + "Beschreibungen nur über den Knopf im Reiter „Bilder“ der Seite. Gilt ohne Neustart beim nächsten "
            + "Abgleich. Schlüssel in der Konfigurationsdatei: space-harvester.images.automatic und "
            + "space-harvester.images.max-per-run.";
    /**
     * Aussagenanalyse ({@code space-harvester.statements.analyzer} und {@code analyzer-model}, Fahrplan A2/A3): EINE
     * Einstellung für den vorgewählten Analysator und die Ablage, aus der alle Ansichten lesen, dazu das Modell nur für
     * den KI-Analysator.
     */
    final JComboBox<String> statementAnalyzer = Comic.combo();
    final JTextField statementModel = Comic.field(24);
    final ComposerButton pickStatement = Comic.button("▾",
            "Modell für die Aussagenanalyse aus der Liste des Dienstes wählen");
    final HelpButton statementHelp = new HelpButton(Dialogs.CLOSE_SIZE, ANALYZER_LABEL, ANALYZER_HELP);
    static final String ANALYZER_LABEL = "Aussagenanalyse";
    static final String ANALYZER_PARSER = AnalyzerNames.PARSER;
    static final String ANALYZER_MODEL = AnalyzerNames.MODEL;
    static final String ANALYZER_MODEL_LABEL = "Aussagen-Modell";
    static final String ANALYZER_HELP = AnalyzerNames.HELP + "\n\n"
            + "Jeder Analysator hat eine eigene Ablage; Aussagen, Graph, Stand, Begriffe und Export zeigen immer nur "
            + "die hier gewählte. Der KI-Analysator rechnet nur Seiten mit KI-Freigabe und nur, wenn Sie ihn beim "
            + "Start der Analyse wählen. Ohne eigenes „" + ANALYZER_MODEL_LABEL + "“ gilt das Text-Modell.\n\n"
            + "Die Werte gelten ohne Neustart. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.statements.analyzer und .analyzer-model.";
    /**
     * Vorstufe „Sätze vereinfachen“ der Aussagenanalyse ({@code space-harvester.statements.simplification.*},
     * ADR 0032): Betriebsart, Abschnitte zugleich und das Modell nur für diesen Schritt.
     */
    final JComboBox<String> simplifyMode = Comic.combo();
    final JSpinner simplifyParallel = countSpinner(1, MAX_SIMPLIFY_PARALLEL, 1);
    final JTextField simplifyModel = Comic.field(24);
    final ComposerButton pickSimplify = Comic.button("▾",
            "Modell zum Vereinfachen aus der Liste des Dienstes wählen");
    final HelpButton simplifyHelp = new HelpButton(Dialogs.CLOSE_SIZE, SIMPLIFY_LABEL, SIMPLIFY_HELP);
    static final String SIMPLIFY_LABEL = "Sätze vereinfachen";
    static final String SIMPLIFY_WHERE_NEEDED = "nur wo nötig";
    static final String SIMPLIFY_ALWAYS = "immer";
    static final String SIMPLIFY_PARALLEL_UNIT = "Abschnitte zugleich";
    static final String SIMPLIFY_PARALLEL_LABEL = "Vereinfachen parallel";
    static final String SIMPLIFY_MODEL_LABEL = "Vereinfachungs-Modell";
    static final int MAX_SIMPLIFY_PARALLEL = 16;
    static final String SIMPLIFY_HELP = "Vorstufe der Aussagenanalyse: Ein Chat-Modell formt Sätze vor dem "
            + "MaltParser zu einfachen Hauptsätzen um. Sie läuft nur, wenn sie beim Start der Analyse angekreuzt "
            + "ist, und nur für Seiten mit KI-Freigabe; alle anderen Seiten werden ohne Vorstufe analysiert.\n\n"
            + "„" + SIMPLIFY_WHERE_NEEDED + "“ vereinfacht nur Stichpunkte, Kennungszeilen und Sätze ohne sichere "
            + "Aussage. „" + SIMPLIFY_ALWAYS + "“ vereinfacht alle Sätze mit Verb.\n\n"
            + "Ohne eigenes „" + SIMPLIFY_MODEL_LABEL + "“ gilt das Text-Modell. Unter „Erweitert“ steht, wie viele "
            + "Abschnitte einer Seite höchstens gleichzeitig beim Modell sind.\n\n"
            + "Die Werte gelten ohne Neustart beim nächsten Lauf. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.statements.simplification.mode, .parallel und .model.";
    /**
     * Prüfschritt „Aussagen prüfen“ ({@code space-harvester.statements.check.*}, ADR 0032): Prüfumfang, Sätze zugleich
     * und das Modell nur für diesen Schritt. Die Auswahl heißt „Prüfumfang“, damit Einstellung und Auftrag nicht
     * gleich heißen.
     */
    final JComboBox<String> checkScope = Comic.combo();
    final JSpinner checkParallel = countSpinner(1, MAX_CHECK_PARALLEL, 1);
    final JTextField checkModel = Comic.field(24);
    final ComposerButton pickCheck = Comic.button("▾", "Modell zum Prüfen aus der Liste des Dienstes wählen");
    final HelpButton checkHelp = new HelpButton(Dialogs.CLOSE_SIZE, CHECK_SCOPE_LABEL, CHECK_HELP);
    static final String CHECK_JOB = com.aresstack.spaceharvester.app.ui.content.CheckTexts.JOB;
    static final String CHECK_SCOPE_LABEL = "Prüfumfang";
    static final String CHECK_UNCERTAIN_AND_STAGE = "unsichere und Vorstufe";
    static final String CHECK_ALL = "alle";
    static final String CHECK_PARALLEL_UNIT = "Sätze zugleich";
    static final String CHECK_PARALLEL_LABEL = "Prüfen parallel";
    static final String CHECK_MODEL_LABEL = "Prüf-Modell";
    static final int MAX_CHECK_PARALLEL = 16;
    static final String CHECK_HELP = "Der Prüfschritt „" + CHECK_JOB + "“ gibt nur Seiten mit KI-Freigabe an das "
            + "Modell und hebt nie eine Stufe.\n\n"
            + "„" + CHECK_UNCERTAIN_AND_STAGE + "“ prüft Aussagen unterhalb von „plausibel“ und alle Aussagen der "
            + "Vorstufe. „" + CHECK_ALL + "“ prüft alle Aussagen außer den ungültigen.\n\n"
            + "Ohne eigenes „" + CHECK_MODEL_LABEL + "“ gilt das Text-Modell. Unter „Erweitert“ steht, wie viele "
            + "Sätze einer Seite höchstens gleichzeitig beim Modell sind.\n\n"
            + "Die Werte gelten ohne Neustart beim nächsten Auftrag. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.statements.check.scope, .parallel und .model.";
    final ComposerButton parserInstall = Comic.button(ParserModelSetup.INSTALL,
            "Lädt das freigegebene deutsche MaltParser-Modell aus dem GitHub-Release, prüft die Prüfsumme und "
                    + "entpackt es ins Datenverzeichnis");
    /** Auswahl des Whisper-Modells; eine Änderung wird sofort gespeichert und gilt beim nächsten Gebrauch. */
    final JComboBox<String> whisperChoice = Comic.combo();
    final ComposerButton whisperInstall = Comic.button(WhisperModelSetup.INSTALL,
            "Lädt das gewählte Whisper-Modell von Hugging Face in den Benutzerordner und prüft die Prüfsummen");
    /**
     * Auswahl des Whisper-Modells der Spracheingabe im Chat, getrennt von dem der Transkripte (Angelo 21.11.2026:
     * dort zählt Tempo, Vorgabe Tiny); eine Änderung wird sofort gespeichert und gilt ab der nächsten Aufnahme.
     */
    final JComboBox<String> dictationChoice = Comic.combo();
    final ComposerButton dictationInstall = Comic.button(WhisperModelSetup.INSTALL,
            "Lädt das gewählte Whisper-Modell der Spracheingabe von Hugging Face in den Benutzerordner und prüft die "
                    + "Prüfsummen");
    static final String WHISPER_TRANSCRIPTS_LABEL = "Whisper-Modell Transkripte";
    static final String WHISPER_DICTATION_LABEL = "Whisper-Modell Spracheingabe";
    static final String LIVE_DICTATION_LABEL = "Spracheingabe live";
    static final String LIVE_DICTATION_TUNING_LABEL = "Spracheingabe live – Fenster";
    static final String LIVE_DICTATION_HELP = "Live mitschreiben: Die Wörter erscheinen schon während des Sprechens im "
            + "Eingabefeld, vorläufiger Text blassgelb hinterlegt. Umschalten auch per Klick auf die Wellen im Chat. "
            + "Ohne Häkchen kommt der Text erst nach dem Stopp (erst komplett).\n\n"
            + "Takt: so oft wird neu erkannt (bei langsamem Rechner wartet die nächste Erkennung auf die vorige). "
            + "Fenster: höchstens so viele Sekunden noch nicht festen Tons werden auf einmal erkannt, immer unter 30 "
            + "Sekunden, weil Whisper nicht mehr sieht. Text steht fest, sobald zwei Erkennungen nacheinander gleich "
            + "lauten; Wiederholungen an der Fenstergrenze fallen weg. Anlauf: so viel Ton braucht die erste "
            + "Erkennung.";
    static final String WHISPER_TRANSCRIPTS_HELP = "Für Video-Transkripte und „In PPTX umwandeln“, läuft lokal; größere "
            + "Modelle erkennen genauer und rechnen länger. Wird nur nach Rückfrage oder auf Klick einmal von Hugging "
            + "Face geladen.";
    static final String WHISPER_DICTATION_HELP = "Für die Spracheingabe im Chat, läuft lokal; Tiny liefert am "
            + "schnellsten. Wird nur nach Rückfrage oder auf Klick einmal von Hugging Face geladen.";
    static final String WHISPER_INSTALL_TIP = "Lädt das gewählte Whisper-Modell von Hugging Face in den Benutzerordner "
            + "und prüft die Prüfsummen";
    static final String WHISPER_BUSY_TIP = "Es lädt gerade ein anderes Modell";
    /** Während {@link #render} die Auswahl setzt, ist das keine Eingabe des Benutzers. */
    private boolean showingWhisper;
    private final PreferredModels preferred = PreferredModels.bundled();
    /** Die zuletzt geladenen Modelle; leer bis „Modelle laden“. */
    List<AiModel> loaded = List.of();
    /** Wiederholt „Modelle laden“ im Assistenten, solange „Modelle“ offen ist und der Dienst nicht erreichbar ist. */
    final ModelRetry modelRetry;
    /** Ob „Modelle laden“ über {@link #modelRetry} läuft (Assistent, Schritt „Modelle“ offen) statt einmal. */
    private boolean autoRetry;
    /** Zählt jede Anfrage nach Modellen; nur die Antwort auf die jüngste zählt. */
    private int modelsRequest;
    /** Für welchen Dienst ({@link #serviceKey}) {@link #loaded} gilt. */
    private String loadedService = "";
    /** Für Tests: wie viele veraltete Antworten (älterer Dienst oder überholte Anfrage) verworfen wurden. */
    int discardedModelAnswers;
    /** Grund des letzten gescheiterten Versuchs, für die Statuszeile der Wiederholung. */
    private String lastFailure = "";

    AiSettingsPanel(AppContext context) {
        this(context, ModelRetry.SWING);
    }

    /** Mit eigener Zeitplanung für die Wiederholung von „Modelle laden“; für Tests. */
    AiSettingsPanel(AppContext context, ModelRetry.Scheduler scheduler) {
        this.context = context;
        this.modelRetry = new ModelRetry(scheduler, this::attemptModels, this::retrying);
        this.modelsFeedback = new Feedback();
        this.modelRows = new SettingDialogs.Rows();
        root.setOpaque(false);
        Comic.style(apiKey);
        PasswordReveal.install(apiKey);
        for (AiProvider each : AiProvider.values()) {
            provider.addItem(each.displayName());
        }
        rows.add("Anbieter", provider, "Ollama läuft meist auf diesem Rechner und meldet seine Modelle mit ihren "
                + "Fähigkeiten (Text, Bild, Embedding)");
        provider.addActionListener(e -> updateProvider());
        access.addItem(KEEPASS);
        access.addItem(MANUAL);
        entry.setEditable(true);
        rows.add("Zugangsdaten beziehen über", access, "KeePass liest Basisadresse und API-Key aus einem Eintrag; "
                + "Manuell legt den Key im Tresor dieses Windows-Kontos ab");
        rows.add("Eintrag", entryRow, ENTRY_HINT);
        access.addActionListener(e -> updateAccess());
        pair.addActionListener(e -> pairKeePass());
        entry.addActionListener(e -> entryChosen());
        if (entry.getEditor().getEditorComponent() instanceof javax.swing.text.JTextComponent editor) {
            // Ein anderer Name im Eintragsfeld nimmt den gelesenen Key sofort heraus, damit man den Key tippen kann.
            editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    javax.swing.SwingUtilities.invokeLater(AiSettingsPanel.this::entryEdited);
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    javax.swing.SwingUtilities.invokeLater(AiSettingsPanel.this::entryEdited);
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            });
        }
        rows.add("Basisadresse", baseUrl, OPENAI_HINT);
        rows.add(CONTEXT_TOKENS_LABEL, contextTokens, CONTEXT_TOKENS_HINT);
        rows.add(LOAD_TIMEOUT_LABEL, loadTimeout, LOAD_TIMEOUT_HINT);
        rows.add(LOAD_POLL_LABEL, loadPollInterval, LOAD_POLL_HINT);
        rows.add("API-Key", apiKey, " ");
        modelRows.add("Modelle", loadModels, "Fragt die Modelle beim Dienst ab und wählt die Vorzugsmodelle vor; andere über das Auswahlmenü rechts");
        modelRows.add("Text-Modell", modelRow(Comic.fieldWithButton(chatModel, pickChat), chatMaxTokens,
                stepThinking, "Text-Modell", CHAT_MAX_TOKENS_HELP), null);
        judgedPairs.setToolTipText("Höchstens so viele Kandidatenpaare je Synonymlauf prüft das Text-Modell; 0 "
                + "lässt die Prüfung aus");
        modelRows.add(JUDGED_PAIRS_LABEL, Comic.row(judgedPairs, Comic.text(JUDGED_PAIRS_UNIT), judgedPairsHelp),
                null);
        automaticSynonyms.setToolTipText("Synonymlauf für eine Quelle nach jedem Abgleich und nach neuer KI-Freigabe; "
                + "ohne Häkchen nur auf Klick");
        modelRows.add(AUTOMATIC_SYNONYMS_LABEL, Comic.row(automaticSynonyms, automaticSynonymsHelp), null);
        glossarySynonyms.setToolTipText(GLOSSARY_SYNONYMS_TIP);
        modelRows.add(GLOSSARY_SYNONYMS_LABEL, Comic.row(glossarySynonyms, glossarySynonymsHelp), null);
        statementAnalyzer.addItem(ANALYZER_PARSER);
        statementAnalyzer.addItem(ANALYZER_MODEL);
        statementAnalyzer.setToolTipText(AnalyzerNames.STORE_TOOLTIP);
        modelRows.add(ANALYZER_LABEL, Comic.row(statementAnalyzer, statementHelp), null);
        modelRows.add(ANALYZER_MODEL_LABEL, modelRow(Comic.fieldWithButton(statementModel, pickStatement),
                statementMaxTokens, statementThinking, ANALYZER_MODEL_LABEL, stepTokensHelp("den KI-Analysator der Aussagenanalyse",
                        "space-harvester.statements.analyzer-model, .analyzer-max-tokens und .analyzer-thinking")),
                null);
        simplifyMode.addItem(SIMPLIFY_WHERE_NEEDED);
        simplifyMode.addItem(SIMPLIFY_ALWAYS);
        simplifyMode.setToolTipText("Welche Sätze die Vorstufe der Aussagenanalyse vereinfacht");
        simplifyParallel.setToolTipText("Höchstens so viele Abschnitte einer Seite sind gleichzeitig beim Modell");
        modelRows.add(SIMPLIFY_LABEL, Comic.row(simplifyMode, simplifyHelp), null);
        modelRows.add(SIMPLIFY_MODEL_LABEL, modelRow(Comic.fieldWithButton(simplifyModel, pickSimplify),
                simplifyMaxTokens, simplifyThinking, SIMPLIFY_MODEL_LABEL, stepTokensHelp("„" + SIMPLIFY_LABEL + "“",
                        "space-harvester.statements.simplification.model, .max-tokens und .thinking")), null);
        checkScope.addItem(CHECK_UNCERTAIN_AND_STAGE);
        checkScope.addItem(CHECK_ALL);
        checkScope.setToolTipText("Welche Aussagen der Auftrag „" + CHECK_JOB + "“ dem Modell zeigt");
        checkParallel.setToolTipText("Höchstens so viele Sätze einer Seite sind gleichzeitig beim Modell");
        modelRows.add(CHECK_SCOPE_LABEL, Comic.row(checkScope, checkHelp), null);
        modelRows.add(CHECK_MODEL_LABEL, modelRow(Comic.fieldWithButton(checkModel, pickCheck), checkMaxTokens,
                checkThinking, CHECK_MODEL_LABEL, stepTokensHelp("„" + CHECK_JOB + "“",
                        "space-harvester.statements.check.model, .max-tokens und .thinking")), null);
        modelRows.add("Embedding-Modell", Comic.fieldWithButton(embeddingModel, pickEmbedding),
                "Für die semantische Suche, z. B. text-embedding-3-small");
        modelRows.add("Vision-Modell", modelRow(Comic.fieldWithButton(visionModel, pickVision), visionMaxTokens,
                visionThinking, "Vision-Modell", VISION_MAX_TOKENS_HELP), null);
        modelRows.add(DEADLINE_LABEL, modelRow(Comic.fieldWithButton(deadlineModel, pickDeadline),
                deadlineMaxTokens, deadlineThinking, DEADLINE_LABEL, DEADLINE_HELP), null);
        diagnosisEnabled.setToolTipText(DIAGNOSIS_TIP);
        modelRows.add(DIAGNOSIS_LABEL, leading(diagnosisEnabled, modelRow(Comic.fieldWithButton(diagnosisModel,
                pickDiagnosis), diagnosisMaxTokens, diagnosisThinking, DIAGNOSIS_LABEL, DIAGNOSIS_HELP)), null);
        modelRows.add(IMAGE_PLAN_LABEL, modelRow(Comic.fieldWithButton(imagePlanModel, pickImagePlan),
                imagePlanMaxTokens, imagePlanThinking, IMAGE_PLAN_LABEL, IMAGE_PLAN_HELP), null);
        modelRows.add(IMAGE_LABEL, modelRow(Comic.fieldWithButton(imageModel, pickImage), imageMaxTokens,
                imageThinking, IMAGE_LABEL, IMAGE_HELP), null);
        modelRows.add(SUMMARY_PLAN_LABEL, modelRow(Comic.fieldWithButton(summaryPlanModel, pickSummaryPlan),
                summaryPlanMaxTokens, summaryPlanThinking, SUMMARY_PLAN_LABEL, SUMMARY_PLAN_HELP), null);
        modelRows.add(SUMMARY_LABEL, modelRow(Comic.fieldWithButton(summaryModel, pickSummary), summaryMaxTokens,
                summaryThinking, SUMMARY_LABEL, SUMMARY_HELP), null);
        modelRows.add(PODCAST_LABEL, modelRow(Comic.fieldWithButton(podcastModel, pickPodcast), podcastMaxTokens,
                podcastThinking, PODCAST_LABEL, PODCAST_HELP), null);
        modelRows.add(PODCAST_SPEECH_LABEL, speechRow(Comic.fieldWithButton(podcastSpeechModel, pickPodcastSpeech),
                PODCAST_SPEECH_LABEL, PODCAST_SPEECH_HELP, "Gastgeber", podcastHost, "Co-Host", podcastCohost), null);
        modelRows.add(READ_ALOUD_LABEL, speechRow(Comic.fieldWithButton(readAloudModel, pickReadAloud),
                READ_ALOUD_LABEL, READ_ALOUD_HELP, "Stimme", readAloudVoice), null);
        followVoices(podcastSpeechModel, podcastHost, podcastCohost);
        followVoices(readAloudModel, readAloudVoice);
        automaticImages.setToolTipText("Bilder freigegebener Seiten nach jedem Abgleich und nach neuer KI-Freigabe "
                + "beschreiben; ohne Häkchen nur auf Klick");
        imagesPerRun.setToolTipText("Höchstens so viele Bilder gehen je Lauf und Quelle an das Vision-Modell; "
                + "0 = unbegrenzt");
        // Eine Zeile (UX-Manager 21.11.): Die Obergrenze gehört zur Checkbox und bleibt immer bedienbar.
        modelRows.add(AUTOMATIC_IMAGES_LABEL, Comic.row(automaticImages, Comic.text(IMAGES_PER_RUN_BEFORE),
                imagesPerRun, Comic.text(IMAGES_PER_RUN_AFTER), automaticImagesHelp), null);
        modelRows.add(TIMEOUTS_LABEL, Comic.row(Comic.text("Update-Zusammenfassung"), summaryTimeout,
                Comic.text("Abschnittsnamen"), labelTimeout, Comic.text("Akronyme"), expansionTimeout,
                new HelpButton(Dialogs.CLOSE_SIZE, TIMEOUTS_LABEL, TIMEOUTS_HELP)), null);
        modelRows.add(RETRIES_LABEL, retries, RETRIES_HINT);
        modelRows.add(PARALLEL_LABEL, parallel, PARALLEL_HINT);
        {
            modelRows.add("Rerank-Modell", Comic.fieldWithButton(rerankModel, pickRerank),
                    "Cross-Encoder des Dienstes (/rerank); ordnet die Treffer aus dem Cache neu, leer: Reihenfolge "
                            + "bleibt");
            for (com.aresstack.spaceharvester.app.transcription.WhisperModel each : com.aresstack.spaceharvester.app.transcription.WhisperModel.values()) {
                whisperChoice.addItem(each.described());
                dictationChoice.addItem(each.described());
            }
            // Hilfetexte als ?-Knopf am Zeilenende, keine Hinweiszeile darunter (UX-Manager 21.11.2026).
            modelRows.add(WHISPER_TRANSCRIPTS_LABEL, Comic.row(whisperChoice, whisperInstall,
                    new HelpButton(Dialogs.CLOSE_SIZE, WHISPER_TRANSCRIPTS_LABEL, WHISPER_TRANSCRIPTS_HELP)), null);
            modelRows.add(WHISPER_DICTATION_LABEL, Comic.row(dictationChoice, dictationInstall,
                    new HelpButton(Dialogs.CLOSE_SIZE, WHISPER_DICTATION_LABEL, WHISPER_DICTATION_HELP)), null);
            modelRows.add(LIVE_DICTATION_LABEL, liveDictationRow(), null);
            modelRows.add(LIVE_DICTATION_TUNING_LABEL, liveDictationTuningRow(), null);
            whisperChoice.addActionListener(e -> {
                int index = whisperChoice.getSelectedIndex();
                if (showingWhisper || index < 0 || context.settings() == null) {
                    return;
                }
                com.aresstack.spaceharvester.app.transcription.WhisperModel chosen = com.aresstack.spaceharvester.app.transcription.WhisperModel.values()[index];
                if (chosen != context.whisper()) {
                    context.whisper(chosen, () -> modelsFeedback.success(WHISPER_TRANSCRIPTS_LABEL + ": "
                            + chosen.label()), modelsFeedback::problem);
                }
            });
            dictationChoice.addActionListener(e -> {
                int index = dictationChoice.getSelectedIndex();
                if (showingWhisper || index < 0 || context.settings() == null) {
                    return;
                }
                com.aresstack.spaceharvester.app.transcription.WhisperModel chosen = com.aresstack.spaceharvester.app.transcription.WhisperModel.values()[index];
                if (chosen != context.dictationWhisper()) {
                    context.dictationWhisper(chosen, () -> modelsFeedback.success(WHISPER_DICTATION_LABEL + ": "
                            + chosen.label()), modelsFeedback::problem);
                }
            });
            whisperInstall.addActionListener(e -> installWhisper(context.whisper()));
            dictationInstall.addActionListener(e -> installWhisper(context.dictationWhisper()));
            context.whisperModel().listen(this::whisperModel);
            parserStatus.setEditable(false);
            parserStatus.setFocusable(false);
            modelRows.add(ParserModelSetup.LABEL, Comic.fieldWithButton(parserStatus, parserInstall),
                    "MaltParser (deutsch) für „Analysen › Aussagen“; wird nur auf Klick aus dem GitHub-Release geladen "
                            + "und läuft danach lokal");
            parserInstall.addActionListener(e -> {
                ParserModelSetup setup = context.parserModel();
                if (setup.status().state() == ParserModelSetup.State.INSTALLING) {
                    setup.cancel();
                } else {
                    setup.install();
                }
            });
            context.parserModel().listen(this::parserModel);
            // Wie im Assistenten von AskAI: linksbündige Überschrift, die aufgeklappten Felder im selben Raster.
            modelRows.wide(advanced);
            modelRows.add("Dimension", dimensions, "Vektorlänge des Embedding-Modells; leer: wird beim Übernehmen "
                    + "beim Dienst gemessen");
            modelRows.add("Abschnittsgröße", chunkTokens, "Höchstens so viele Tokens je Abschnitt für Embeddings; "
                    + "leer: 350; wirkt nach Neustart");
            modelRows.add("Überlappung", chunkOverlap, "So viele Sätze wiederholen sich zwischen Abschnitten; leer: 1; "
                    + "wirkt nach Neustart");
            modelRows.add(SIMPLIFY_PARALLEL_LABEL, Comic.row(simplifyParallel, Comic.text(SIMPLIFY_PARALLEL_UNIT)),
                    "„" + SIMPLIFY_LABEL + "“: so viele Abschnitte einer Seite sind höchstens gleichzeitig beim "
                            + "Modell; Vorgabe " + SimplificationSettings.DEFAULT_PARALLEL);
            modelRows.add(CHECK_PARALLEL_LABEL, Comic.row(checkParallel, Comic.text(CHECK_PARALLEL_UNIT)),
                    "„" + CHECK_JOB + "“: so viele Sätze einer Seite sind höchstens gleichzeitig beim Modell; "
                            + "Vorgabe " + CheckSettings.DEFAULT_PARALLEL);
            // Eine Zeile mit ?-Knopf statt drei Zeilen mit Hinweistext (UX: Hilfe als ?-Knopf am Zeilenende).
            modelRows.add(PODCAST_TIMING_LABEL, Comic.row(podcastPause, Comic.text(PODCAST_PAUSE_UNIT),
                    podcastAttempts, Comic.text(PODCAST_ATTEMPTS_UNIT), podcastWorkers,
                    Comic.text(PODCAST_WORKERS_UNIT), new HelpButton(Dialogs.CLOSE_SIZE, PODCAST_TIMING_LABEL,
                            PODCAST_TIMING_HELP)), null);
            modelRows.add(READ_ALOUD_DIAL_LABEL, readAloudDialRow(), null);
            advanced.addActionListener(e -> advanced(!advancedOpen()));
            advanced(false);
        }
        pickChat.setEnabled(false);
        pickSimplify.setEnabled(false);
        pickCheck.setEnabled(false);
        pickEmbedding.setEnabled(false);
        pickRerank.setEnabled(false);
        pickVision.setEnabled(false);
        pickImage.setEnabled(false);
        pickSummary.setEnabled(false);
        pickSummaryPlan.setEnabled(false);
        pickPodcast.setEnabled(false);
        pickImagePlan.setEnabled(false);
        pickDeadline.setEnabled(false);
        pickDiagnosis.setEnabled(false);
        pickPodcastSpeech.setEnabled(false);
        pickReadAloud.setEnabled(false);
        loadModels.addActionListener(e -> loadModels());
        pickSimplify.addActionListener(e -> pick(pickSimplify, simplifyModel, AiModel.CHAT));
        pickCheck.addActionListener(e -> pick(pickCheck, checkModel, AiModel.CHAT));
        pickStatement.addActionListener(e -> pick(pickStatement, statementModel, AiModel.CHAT));
        pickChat.addActionListener(e -> pick(pickChat, chatModel, AiModel.CHAT));
        pickEmbedding.addActionListener(e -> pick(pickEmbedding, embeddingModel, AiModel.EMBEDDING));
        pickRerank.addActionListener(e -> pick(pickRerank, rerankModel, null));
        pickVision.addActionListener(e -> pick(pickVision, visionModel, AiModel.VISION));
        pickImage.addActionListener(e -> pick(pickImage, imageModel, AiModel.CHAT));
        pickSummary.addActionListener(e -> pick(pickSummary, summaryModel, AiModel.CHAT));
        pickSummaryPlan.addActionListener(e -> pick(pickSummaryPlan, summaryPlanModel, AiModel.CHAT));
        pickPodcast.addActionListener(e -> pick(pickPodcast, podcastModel, AiModel.CHAT));
        pickImagePlan.addActionListener(e -> pick(pickImagePlan, imagePlanModel, AiModel.CHAT));
        pickDeadline.addActionListener(e -> pick(pickDeadline, deadlineModel, AiModel.CHAT));
        pickDiagnosis.addActionListener(e -> pick(pickDiagnosis, diagnosisModel, AiModel.CHAT));
        pickPodcastSpeech.addActionListener(e -> pick(pickPodcastSpeech, podcastSpeechModel, AiModel.TTS));
        pickReadAloud.addActionListener(e -> pick(pickReadAloud, readAloudModel, AiModel.TTS));
        released.setOpaque(false);
        released.setLayout(new javax.swing.BoxLayout(released, javax.swing.BoxLayout.Y_AXIS));
        rows.add("An den Dienst senden", released, "Nur freigegebene Quellen bekommen Embeddings und Reranking");
        root.add(Comic.note("Die semantische Suche (experimentell) braucht einen KI-Dienst mit Embedding-Modell; die "
                + "Modelle wählst du unter „Modelle“. Der API-Key liegt nur im Tresor dieses Windows-Kontos, nie in der "
                + "Konfigurationsdatei."), BorderLayout.NORTH);
        rows.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        root.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save, removeKey), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        save.addActionListener(e -> save(save, feedback, () -> { }, false));
        removeKey.addActionListener(e -> removeKey());
        {
            models.setOpaque(false);
            models.add(Comic.note("Modelle gelten zentral für alle Wissensquellen. „Modelle laden“ fragt den unter "
                    + "„KI-Dienst“ eingetragenen Dienst ab."), BorderLayout.NORTH);
            modelRows.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
            models.add(modelRows, BorderLayout.CENTER);
            JPanel modelsSouth = new JPanel(new BorderLayout(0, 6));
            modelsSouth.setOpaque(false);
            modelsSouth.add(Comic.row(saveModels), BorderLayout.NORTH);
            modelsSouth.add(modelsFeedback, BorderLayout.SOUTH);
            models.add(modelsSouth, BorderLayout.SOUTH);
            saveModels.addActionListener(e -> save(saveModels, modelsFeedback, () -> { }, false));
        }
        render();
    }

    /** Reiter „KI-Dienst“: Adresse, API-Key und wie viele Quellen freigegeben sind. */
    JComponent serviceComponent() {
        return root;
    }

    /** Reiter „Modelle“: zentrale Modellwahl nach Funktion und Zerlegung. */
    JComponent modelsComponent() {
        return models;
    }

    /** Übernimmt die Werte der Datei in die Felder; der API-Key erscheint nur als „gespeichert“ oder „fehlt“. */
    void render() {
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        provider.setSelectedItem(current.provider().displayName());
        baseUrl.setText(current.baseUrl() == null ? "" : current.baseUrl());
        contextTokens.setText(current.contextTokens() == null ? "" : current.contextTokens());
        loadTimeout.setText(current.loadTimeout() == null ? "" : current.loadTimeout());
        retries.setText(current.retries() == null ? "" : current.retries());
        parallel.setText(current.parallel() == null ? "" : current.parallel());
        stepThinking.showStored(current.thinking());
        visionThinking.showStored(current.visionLevel());
        imageThinking.showStored(current.imageLevel());
        showUse(current.summary(), summaryModel, summaryMaxTokens, summaryThinking);
        showUse(current.summaryPlan(), summaryPlanModel, summaryPlanMaxTokens, summaryPlanThinking);
        showUse(current.podcast(), podcastModel, podcastMaxTokens, podcastThinking);
        showUse(current.imagePlan(), imagePlanModel, imagePlanMaxTokens, imagePlanThinking);
        showUse(current.deadline(), deadlineModel, deadlineMaxTokens, deadlineThinking);
        showUse(current.diagnosis(), diagnosisModel, diagnosisMaxTokens, diagnosisThinking);
        // Die Vorbelegung steht sichtbar im Feld (Angelo 26.11.2026: „vorbelegen, GPT OSS“).
        diagnosisModel.setText(current.diagnosisSettings().model());
        diagnosisEnabled.setSelected(current.diagnosisSettings().on());
        summaryTimeout.value(current.timeouts().summaryTimeout());
        labelTimeout.value(current.timeouts().labelTimeout());
        expansionTimeout.value(current.timeouts().expansionTimeout());
        podcastSpeechModel.setText(text(current.speech().podcastModel()));
        podcastHost.setSelectedItem(text(current.speech().podcastHost()));
        podcastCohost.setSelectedItem(text(current.speech().podcastCohost()));
        readAloudModel.setText(text(current.speech().readAloudModel()));
        readAloudVoice.setSelectedItem(text(current.speech().readAloudVoice()));
        podcastPause.setValue(current.speech().podcastPause());
        podcastAttempts.setValue(current.speech().podcastSynthesisAttempts());
        podcastWorkers.setValue(current.speech().podcastSynthesisWorkers());
        loadPollInterval.setText(current.loadPollInterval() == null ? "" : current.loadPollInterval());
        chatModel.setText(current.chatModel() == null ? "" : current.chatModel());
        embeddingModel.setText(current.embeddingModel() == null ? "" : current.embeddingModel());
        dimensions.setText(current.embeddingDimensions() == null ? "" : current.embeddingDimensions());
        rerankModel.setText(current.rerankModel() == null ? "" : current.rerankModel());
        visionModel.setText(current.visionModel() == null ? "" : current.visionModel());
        chatMaxTokens.showStored(current.chatMaxTokens());
        visionMaxTokens.showStored(current.visionMaxTokens());
        imageModel.setText(current.imageModel() == null ? "" : current.imageModel());
        imageMaxTokens.showStored(current.imageMaxTokens());
        judgedPairs.setValue(Math.min(savedBudget(settings).maxJudgedPairs(), SynonymRunForm.MAX_PAIRS));
        automaticSynonyms.setSelected(savedBudget(settings).automatic());
        glossarySynonyms.setSelected(savedBudget(settings).glossaryTermsOnly());
        ImageSettings images = savedImages(settings);
        automaticImages.setSelected(images.automatic());
        imagesPerRun.setValue(Math.min(images.maxPerRun(), MAX_IMAGES_PER_RUN));
        SimplificationSettings simplification = savedSimplification(settings);
        simplifyMode.setSelectedItem(simplification.mode() == SimplificationSettings.Mode.ALWAYS ? SIMPLIFY_ALWAYS
                : SIMPLIFY_WHERE_NEEDED);
        simplifyParallel.setValue(Math.min(simplification.parallel(), MAX_SIMPLIFY_PARALLEL));
        simplifyModel.setText(simplification.model() == null ? "" : simplification.model());
        simplifyMaxTokens.showStored(simplification.maxTokens() == null ? null
                : String.valueOf(simplification.maxTokens()));
        simplifyThinking.showStored(simplification.thinking());
        AnalyzerSettings analyzer = savedAnalyzer(settings);
        statementAnalyzer.setSelectedItem(analyzer.analyzer() == AnalyzerChoice.MODEL ? ANALYZER_MODEL
                : ANALYZER_PARSER);
        statementModel.setText(analyzer.model() == null ? "" : analyzer.model());
        statementMaxTokens.showStored(analyzer.maxTokens() == null ? null : String.valueOf(analyzer.maxTokens()));
        statementThinking.showStored(analyzer.thinking());
        CheckSettings check = savedCheck(settings);
        checkScope.setSelectedItem(check.scope() == CheckScope.ALL ? CHECK_ALL : CHECK_UNCERTAIN_AND_STAGE);
        checkParallel.setValue(Math.min(check.parallel(), MAX_CHECK_PARALLEL));
        checkModel.setText(check.model() == null ? "" : check.model());
        checkMaxTokens.showStored(check.maxTokens() == null ? null : String.valueOf(check.maxTokens()));
        checkThinking.showStored(check.thinking());
        chunkTokens.setText(current.chunkTokens() == null ? "" : current.chunkTokens());
        chunkOverlap.setText(current.chunkOverlapSentences() == null ? "" : current.chunkOverlapSentences());
        advanced(current.chunkTokens() != null || current.chunkOverlapSentences() != null
                || savedSimplification(settings).parallel() != SimplificationSettings.DEFAULT_PARALLEL
                || savedCheck(settings).parallel() != CheckSettings.DEFAULT_PARALLEL
                || current.speech().podcastPauseMillis() != null || current.speech().podcastAttempts() != null
                || current.speech().podcastWorkers() != null);
        timeout = current.timeout();
        releasedIds = current.externalProcessingSources();
        renderReleased(settings, releasedIds);
        AiAccess fileAccess = settings == null ? AiAccess.NONE : settings.aiAccess();
        // Nichts gewählt: KeePass, wenn die App schon gekoppelt ist oder KeePass für eine Quelle gewählt wurde
        // (dieselbe Kopplung gilt weiter, kein neues Koppeln), sonst Manuell; ein Key im Tresor spricht für Manuell.
        boolean manual = fileAccess.mode() == AiAccess.Mode.MANUAL
                || fileAccess.mode() == null && (context.aiVault().has(KEY_ID, KEY_LOGIN)
                || !context.keePassPreferred());
        access.setSelectedItem(manual ? MANUAL : KEEPASS);
        String title = fileAccess.entry() == null ? AiAccess.DEFAULT_ENTRY : fileAccess.entry();
        if (titles.isEmpty()) {
            entry.removeAllItems();
            entry.addItem(title);
        }
        entry.setSelectedItem(title);
        entry.getEditor().setItem(title);
        clearKeePassKey();
        apiKey.setText("");
        // Setzt auch den Hinweis zum Key und liest bei KeePass den gewählten Eintrag.
        updateAccess();
        refreshPairing();
        shownAccess = accessDraft();
        updateProvider();
    }

    /** Der gewählte Anbieter. */
    AiProvider chosenProvider() {
        for (AiProvider each : AiProvider.values()) {
            if (each.displayName().equals(provider.getSelectedItem())) {
                return each;
            }
        }
        return AiProvider.OPENAI_COMPATIBLE;
    }

    boolean ollamaChosen() {
        return chosenProvider() == AiProvider.OLLAMA;
    }

    /**
     * Ollama: ohne Zugangsdaten (KeePass, API-Key), die Adresse mit dem Server dieses Rechners vorbelegt, solange
     * keine eingetragen ist. Sonst die Zeilen wie zuvor.
     */
    private void updateProvider() {
        boolean ollama = ollamaChosen();
        rows.visible(access, !ollama);
        rows.visible(entryRow, !ollama && keePassChosen());
        rows.visible(apiKey, !ollama);
        // Die Kontextlänge gilt für jeden Dienst: bei Ollama gesendet, sonst nur für die Textmenge des Chats.
        rows.hint(contextTokens, ollama ? CONTEXT_TOKENS_HINT : CONTEXT_TOKENS_REMOTE_HINT);
        rows.visible(loadTimeout, ollama);
        rows.visible(loadPollInterval, ollama);
        rows.hint(baseUrl, ollama ? OLLAMA_HINT : OPENAI_HINT);
        if (ollama && baseUrl.getText().isBlank()) {
            baseUrl.setText(AiProvider.OLLAMA_DEFAULT_URL);
        }
        removeKey.setVisible(!ollama && context.aiKeyAvailable() && !keePassChosen());
        root.revalidate();
        root.repaint();
    }

    private String manualHint() {
        return context.aiKeyAvailable() ? "Gespeichert; leer lassen, um ihn zu behalten"
                : context.aiVault().available() ? "Fehlt; wird verschlüsselt im Tresor abgelegt"
                : "Fehlt; ohne Windows-Tresor gilt er nur bis zum Schließen der App";
    }

    /** Der eingetippte Key; der aus KeePass gelesene zählt nicht, er wird bei Bedarf frisch gelesen. */
    char[] typedKey() {
        entryEdited();
        return keyFromKeePass ? new char[0] : apiKey.getPassword();
    }

    /** Gilt der gelesene Key nicht mehr für den eingetragenen Namen, liest das den neuen Eintrag (oder leert nur). */
    private void entryEdited() {
        if (keyTitle != null && !keyTitle.equals(entryTitle())) {
            entryChosen();
        }
    }

    /** Nimmt einen aus KeePass gelesenen Key wieder aus dem Feld; ein eingetippter bleibt. */
    private void clearKeePassKey() {
        keyRead++;
        if (keyFromKeePass) {
            apiKey.setText("");
        }
        keyFromKeePass = false;
        keePassKeyPresent = null;
        keyTitle = null;
        apiKey.setEditable(true);
    }

    /** Ein Eintrag aus der Liste von KeePass, oder der gespeicherte, solange die App gekoppelt ist. */
    private boolean knownEntry(String title) {
        return titles.contains(title) || title.equals(savedAccess().entry()) && context.keePass().paired();
    }

    private AiAccess savedAccess() {
        AppSettings settings = context.settings();
        return settings == null ? AiAccess.NONE : settings.aiAccess();
    }

    boolean keePassChosen() {
        return KEEPASS.equals(access.getSelectedItem());
    }

    /** Der eingetragene oder gewählte Titel, getrimmt. */
    String entryTitle() {
        Object item = entry.isEditable() ? entry.getEditor().getItem() : entry.getSelectedItem();
        return item == null ? "" : item.toString().strip();
    }

    /** Bezugsart und Eintrag, wie sie gespeichert würden. */
    AiAccess accessDraft() {
        return keePassChosen() ? new AiAccess(AiAccess.Mode.KEEPASS, entryTitle())
                : new AiAccess(AiAccess.Mode.MANUAL, null);
    }

    void onPairingChange(Runnable pairingChanged) {
        this.pairingChanged = pairingChanged;
    }

    /**
     * Knopf und Hinweis am Eintragsfeld nach dem gespeicherten Kopplungsstand: nicht gekoppelt → „Koppeln“ mit Hinweis
     * an Ort und Stelle, sonst „Einträge laden“. Auch nach Koppeln oder Entkoppeln unter Sicherheit.
     */
    void refreshPairing() {
        boolean paired = context.keePass().paired();
        pair.setText(paired ? LOAD_ENTRIES : KeePassPairingPanel.PAIR);
        pair.setToolTipText(paired ? "Die Einträge aus KeePass für das Auswahlfeld lesen"
                : "Mit KeePass koppeln (KeePassRPC); KeePass zeigt dabei ein Einmal-Passwort");
        rows.hint(entryRow, paired ? ENTRY_HINT : NOT_PAIRED_HINT);
    }

    private void updateAccess() {
        boolean keePass = keePassChosen();
        rows.visible(entryRow, keePass && !ollamaChosen());
        if (keePass) {
            entryChosen();
        } else {
            clearKeePassKey();
            rows.hint(apiKey, manualHint());
        }
        root.revalidate();
        root.repaint();
    }

    /** Koppelt (KeePass zeigt ein Einmal-Passwort) und liest die Einträge für das Auswahlfeld. */
    void pairKeePass() {
        pair.setEnabled(false);
        rows.hint(entryRow, "Verbinde mit KeePass …");
        String keep = entryTitle();
        Background.run(() -> context.keePass().titles(), read -> {
            pair.setEnabled(true);
            titles = List.copyOf(read);
            entry.removeAllItems();
            read.forEach(entry::addItem);
            if (!keep.isEmpty() && !read.contains(keep)) {
                entry.addItem(keep);
            }
            entry.setSelectedItem(keep.isEmpty() && !read.isEmpty() ? read.get(0) : keep);
            entry.getEditor().setItem(entry.getSelectedItem());
            refreshPairing();
            rows.hint(entryRow, "Gekoppelt · " + Texts.count(read.size(), "Eintrag", "Einträge") + " lesbar; "
                    + "Eintrag wählen oder neuen Namen eintippen");
            entryChosen();
            pairingChanged.run();
        }, error -> {
            pair.setEnabled(true);
            refreshPairing();
            feedback.warning("KeePass nicht erreichbar", keePassReason(error));
            pairingChanged.run();
        });
    }

    /**
     * Ein bestehender Eintrag liefert Basisadresse und API-Key (sein Passwort): Der Key steht danach maskiert und
     * schreibgeschützt im Feld, damit man sieht, dass er gelesen wurde; er bleibt nur im Speicher. Ein neuer Titel
     * verlangt den API-Key. Ohne Kopplungsdialog: Gekoppelt wird nur über „Koppeln“.
     */
    private void entryChosen() {
        String title = entryTitle();
        clearKeePassKey();
        if (!keePassChosen()) {
            return;
        }
        if (title.isEmpty() || !knownEntry(title)) {
            rows.hint(apiKey, "Für einen neuen Eintrag: wird in KeePass unter diesem Namen gespeichert");
            return;
        }
        rows.hint(apiKey, "Wird aus dem KeePass-Eintrag „" + title + "“ gelesen …");
        keyTitle = title;
        int request = keyRead;
        Background.run(() -> {
            try (var read = context.keePass().readSilently(title)) {
                return new EntryRead(read.location(), read.copyPassword());
            }
        }, read -> {
            try {
                if (request != keyRead || !keePassChosen() || !title.equals(entryTitle())) {
                    return;
                }
                if (read.location() != null && !read.location().isBlank()) {
                    baseUrl.setText(read.location());
                }
                if (read.key().length == 0) {
                    keePassKeyPresent = false;
                    rows.hint(apiKey, "Der Eintrag „" + title + "“ hat kein Passwort; bitte den API-Key in "
                            + "KeePass als Passwort eintragen");
                    return;
                }
                apiKey.setText(new String(read.key()));
                keyFromKeePass = true;
                keePassKeyPresent = true;
                apiKey.setEditable(false);
                rows.hint(apiKey, "Aus KeePass gelesen (Eintrag „" + title + "“); bleibt nur im Speicher");
            } finally {
                Arrays.fill(read.key(), '\0');
            }
        }, error -> {
            if (request == keyRead && keePassChosen() && title.equals(entryTitle())) {
                rows.hint(apiKey, "Nicht aus KeePass lesbar: " + keePassReason(error));
            }
        });
    }

    /** Adresse und Passwort eines Eintrags; der Empfänger überschreibt das Passwort nach Gebrauch. */
    private record EntryRead(String location, char[] key) {
    }

    static String keePassReason(Throwable error) {
        if (error instanceof SecretUnavailableException unavailable) {
            return switch (unavailable.reason()) {
                case NOT_AVAILABLE -> "KeePass läuft nicht oder das Plugin KeePassRPC fehlt; KeePass starten und "
                        + "erneut koppeln.";
                case ACCESS_DENIED -> "KeePass hat die Kopplung abgelehnt; bitte erneut koppeln.";
                case CANCELLED -> "Kopplung abgebrochen.";
                case NOT_FOUND -> "Kein Eintrag mit diesem Namen in KeePass.";
                case ALREADY_EXISTS -> "Ein Eintrag mit diesem Namen existiert schon in KeePass und bleibt "
                        + "unverändert.";
            };
        }
        Problems.Problem problem = Problems.describe(error);
        return problem.message() == null || problem.message().isBlank() ? problem.title() : problem.message();
    }

    /**
     * Der Key für eine Anfrage an den Dienst: eingetippt, sonst aus dem gewählten KeePass-Eintrag, sonst aus Tresor
     * oder Sitzung. Maßgeblich ist die gewählte Bezugsart, nicht die gespeicherte: Sonst ginge nach einem Wechsel auf
     * „Manuell“ der KeePass-Key an eine neu eingetragene Adresse. Im Hintergrund; der Aufrufer leert ihn.
     */
    char[] currentKey(char[] typed, AiAccess chosen, boolean known) {
        if (typed.length > 0) {
            return typed.clone();
        }
        if (chosen.fromKeePass()) {
            return known ? context.keePass().credentials(chosen.entry()).apiKey() : null;
        }
        return context.manualAiKey();
    }

    AiDraft draft() {
        return new AiDraft(baseUrl.getText(), chatModel.getText(), embeddingModel.getText(), dimensions.getText(),
                timeout, releasedDraft(), rerankModel.getText(), chunkTokens.getText(), chunkOverlap.getText(),
                visionModel.getText(), chatMaxTokens.text(), chosenProvider(),
                ollamaChosen() ? contextTokens.getText() : null, ollamaChosen() ? loadTimeout.getText() : null,
                retries.getText(), ollamaChosen() ? loadPollInterval.getText() : null,
                stepThinking.level(), parallel.getText(), visionMaxTokens.text(), imageModel.getText(),
                imageMaxTokens.text(), visionThinking.level(), imageThinking.level(),
                new AiDraft.Use(summaryModel.getText(), summaryMaxTokens.text(), summaryThinking.level()),
                new AiDraft.Use(summaryPlanModel.getText(), summaryPlanMaxTokens.text(), summaryPlanThinking.level()),
                new AiDraft.Use(podcastModel.getText(), podcastMaxTokens.text(), podcastThinking.level()),
                new AiDraft.Use(imagePlanModel.getText(), imagePlanMaxTokens.text(), imagePlanThinking.level()),
                new AiDraft.Use(deadlineModel.getText(), deadlineMaxTokens.text(), deadlineThinking.level()),
                new AiServiceSettings.Speech(podcastSpeechModel.getText(), voice(podcastHost), voice(podcastCohost),
                        readAloudModel.getText(), voice(readAloudVoice)).withPodcastTiming(
                        own(podcastPause, AiServiceSettings.Speech.DEFAULT_PODCAST_PAUSE_MILLIS),
                        own(podcastAttempts, AiServiceSettings.Speech.DEFAULT_PODCAST_ATTEMPTS),
                        own(podcastWorkers, AiServiceSettings.Speech.DEFAULT_PODCAST_WORKERS)),
                new AiDraft.Use(ownDiagnosisModel(), diagnosisMaxTokens.text(), diagnosisThinking.level()),
                diagnosisEnabled.isSelected(), new AiServiceSettings.Timeouts(
                        own(summaryTimeout, AiServiceSettings.Timeouts.DEFAULT_SUMMARY),
                        own(labelTimeout, AiServiceSettings.Timeouts.DEFAULT_LABEL),
                        own(expansionTimeout, AiServiceSettings.Timeouts.DEFAULT_EXPANSION)));
    }

    /** Die Frist eines Felds; die Vorgabe oder 0 heißt nichts eingestellt ({@code null}), dann gilt die des Core. */
    private static Duration own(DurationField field, Duration fallback) {
        Duration value = field.value();
        return value == null || value.isZero() || value.equals(fallback) ? null : value;
    }

    /** Das Modell der Diagnose; die Vorbelegung heißt nichts eingestellt, dann gilt die des Core. */
    private String ownDiagnosisModel() {
        String model = diagnosisModel.getText();
        return model == null || model.isBlank()
                || model.strip().equalsIgnoreCase(AiServiceSettings.DEFAULT_DIAGNOSIS_MODEL) ? null : model;
    }

    /** {@code lead} (etwa ein Schalter) vor einer Modellzeile, die den Rest der Breite füllt. */
    private static JComponent leading(JComponent lead, JComponent rest) {
        JPanel row = new JPanel(new java.awt.GridBagLayout());
        row.setOpaque(false);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = 0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        row.add(lead, c);
        c.weightx = 1;
        c.insets = new java.awt.Insets(0, 6, 0, 0);
        row.add(rest, c);
        return row;
    }

    /** Der Wert eines Zählers; die Vorgabe heißt nichts eingestellt ({@code null}), dann gilt die des Core. */
    private static Integer own(JSpinner spinner, int fallback) {
        int value = ((Number) spinner.getValue()).intValue();
        return value == fallback ? null : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** Die eingetippte oder gewählte Stimme; leer heißt automatisch. */
    private static String voice(JComboBox<String> box) {
        Object typed = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        Object value = typed != null ? typed : box.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    /** Eine Stimmenauswahl: Stimmen aus {@code tts-voices.properties}, frei eintippbar, leer = automatisch. */
    private static JComboBox<String> voiceBox() {
        JComboBox<String> box = Comic.combo();
        box.setEditable(true);
        box.setPrototypeDisplayValue("Uncle_Fu  ");
        box.setToolTipText("Stimme wählen oder eintippen; leer: Standardstimme des Modells");
        fillVoices(box, "");
        return box;
    }

    /** Füllt die Stimmen passend zum Modell; eine eingetragene Stimme bleibt stehen. */
    static void fillVoices(JComboBox<String> box, String model) {
        String current = voice(box);
        box.removeAllItems();
        box.addItem("");
        VOICES.voices(model).forEach(box::addItem);
        box.setSelectedItem(current);
    }

    /** Die Stimmenauswahl folgt dem eingetragenen Sprachmodell. */
    private static void followVoices(JTextField model, JComboBox<?>... boxes) {
        model.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                changed();
            }

            @SuppressWarnings("unchecked")
            private void changed() {
                for (JComboBox<?> box : boxes) {
                    fillVoices((JComboBox<String>) box, model.getText().strip());
                }
            }
        });
    }

    /** Eine Zeile „links Sprachmodell, rechts Stimmen“ mit dem ?-Knopf am Ende. */
    private static JComponent speechRow(JComponent model, String heading, String help, Object... labelsAndBoxes) {
        JPanel row = new JPanel(new java.awt.GridBagLayout());
        row.setOpaque(false);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = 0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        row.add(model, c);
        c.weightx = 0;
        c.insets = new java.awt.Insets(0, 6, 0, 0);
        for (int i = 0; i + 1 < labelsAndBoxes.length; i += 2) {
            row.add(Comic.text((String) labelsAndBoxes[i]), c);
            row.add((JComponent) labelsAndBoxes[i + 1], c);
        }
        row.add(new HelpButton(Dialogs.CLOSE_SIZE, heading, help), c);
        return row;
    }

    /** Zeigt eine eigene Modellzeile aus der Datei. */
    private static void showUse(AiDraft.Use use, JTextField model, TokenLengthBox tokens, ThinkingBox thinking) {
        model.setText(use.model() == null ? "" : use.model());
        tokens.showStored(use.maxTokens());
        thinking.showStored(use.thinking());
    }

    /**
     * Eine Zeile „links Modell, rechts Tokenlänge“ (Angelo 21.11.2026) mit dem ?-Knopf am Ende: das Modellfeld füllt
     * die Breite, die Tokenlänge hat feste Breite.
     */
    private static JComponent modelRow(JComponent model, TokenLengthBox tokens, ThinkingBox thinking, String heading,
                                       String help) {
        JPanel row = new JPanel(new java.awt.GridBagLayout());
        row.setOpaque(false);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = 0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        row.add(model, c);
        c.weightx = 0;
        c.insets = new java.awt.Insets(0, 6, 0, 0);
        tokens.setPrototypeDisplayValue("1000000");
        row.add(tokens, c);
        row.add(Comic.text(TOKENS_UNIT), c);
        // Gleich breit in allen Zeilen, damit die Spalten untereinander stehen.
        thinking.setPrototypeDisplayValue(TOKENS_LIKE_CHAT);
        row.add(thinking, c);
        row.add(new HelpButton(Dialogs.CLOSE_SIZE, heading, help), c);
        return row;
    }

    /** Je konfigurierte Quelle ein Häkchen mit ihrem Anzeigenamen; ohne Quellen eine Zeile Text. */
    private void renderReleased(AppSettings settings, List<String> ids) {
        released.removeAll();
        releaseBoxes.clear();
        List<AppSettings.SourceEntry> sources = settings == null ? List.of() : settings.sources();
        if (sources.isEmpty()) {
            released.add(Comic.muted("Noch keine Wissensquelle angelegt"));
        }
        for (AppSettings.SourceEntry source : sources) {
            String title = source.config().title();
            javax.swing.JCheckBox box = Comic.checkBox(title == null || title.isBlank() ? source.id() : title);
            box.putClientProperty("sourceId", source.id());
            box.setSelected(ids.contains(source.id()));
            // Nur ein angefasstes Häkchen überschreibt die Datei; sonst gilt, was das Quellenformular inzwischen
            // dort eingetragen hat.
            box.addActionListener(e -> box.putClientProperty(TOUCHED, Boolean.TRUE));
            releaseBoxes.add(box);
            released.add(box);
        }
        released.revalidate();
        released.repaint();
    }

    /** Markiert ein Häkchen der Freigabe, das jemand angeklickt hat. */
    private static final String TOUCHED = "space-harvester.touched";

    /**
     * Die Freigaben für die Datei: der Stand der Datei, überschrieben nur durch Häkchen, die hier jemand angeklickt
     * hat. Freigaben für Quellen, die gerade nicht in der Liste stehen, bleiben erhalten, und was das Quellenformular
     * inzwischen geändert hat, geht nicht verloren.
     */
    private List<String> releasedDraft() {
        AppSettings settings = context.settings();
        List<String> file = settings == null ? releasedIds : settings.ai().externalProcessingSources();
        List<String> ids = new ArrayList<>(file);
        for (javax.swing.JCheckBox box : releaseBoxes) {
            if (!Boolean.TRUE.equals(box.getClientProperty(TOUCHED))) {
                continue;
            }
            String id = (String) box.getClientProperty("sourceId");
            ids.remove(id);
            if (box.isSelected()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Die Häkchen der Freigabe, in der Reihenfolge der Quellen (für Tests). */
    List<javax.swing.JCheckBox> releaseBoxes() {
        return List.copyOf(releaseBoxes);
    }

    /** „2 von 3 Quellen“ mit ihren Namen, oder „keine“. */
    static String releasedText(AppSettings settings, List<String> ids) {
        if (ids.isEmpty()) {
            return "keine Quelle";
        }
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            String name = id;
            if (settings != null) {
                for (AppSettings.SourceEntry source : settings.sources()) {
                    if (source.id().equals(id) && source.config().title() != null && !source.config().title().isBlank()) {
                        name = source.config().title();
                    }
                }
            }
            names.add(name);
        }
        int total = settings == null ? ids.size() : settings.sources().size();
        return ids.size() + " von " + Texts.count(total, "Quelle", "Quellen") + ": " + String.join(", ", names);
    }

    /** Ob seit dem Öffnen etwas eingetragen wurde (Felder oder API-Key). */
    boolean changed() {
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        return !draft().equals(current) || !ollamaChosen() && (typedKey().length > 0
                || !accessDraft().equals(shownAccess))
                || judgedPairs() != savedBudget(settings).maxJudgedPairs()
                || automaticSynonyms() != savedBudget(settings).automatic()
                || glossarySynonyms() != savedBudget(settings).glossaryTermsOnly()
                || !images().equals(savedImages(settings))
                || !simplification().equals(savedSimplification(settings))
                || !check().equals(savedCheck(settings))
                || !analyzer().equals(savedAnalyzer(settings));
    }

    /** Ob gerade gespeichert wird (Assistent: Übernehmen beim Schrittwechsel). */
    private boolean saving;
    /** Was nach dem laufenden Speichern noch übernommen werden soll; nur das letzte zählt. */
    private Runnable queued;

    /**
     * Im Assistenten gibt es kein „Speichern“: Er übernimmt die Eingaben beim Schrittwechsel und spätestens bei
     * „Fertig“ ({@link #autoSave}), wie der Assistent von AskAI.
     */
    void wizardMode() {
        save.setVisible(false);
        saveModels.setVisible(false);
    }

    /**
     * Wie {@link #wizardMode()}, zusätzlich mit genau einem Statusbereich: Meldungen beider Schritte erscheinen in
     * {@code shared} (im Assistenten über der Knopfleiste), nicht mehr in den scrollenden Inhalten.
     */
    void wizardMode(Feedback shared) {
        wizardMode();
        feedback.forwardTo(shared);
        modelsFeedback.forwardTo(shared);
    }

    /**
     * Übernimmt geänderte Eingaben ohne Knopf, wie der Assistent von AskAI beim Verlassen eines Schritts;
     * unverändert ohne Meldung. {@code then} läuft danach auf dem EDT, auch wenn nichts zu tun war, nicht aber, wenn
     * die Eingaben unvollständig sind oder das Speichern scheitert; das meldet {@code feedback}.
     */
    void autoSave(Feedback feedback, Runnable then) {
        if (saving) {
            // Ein Speichern läuft schon (etwa vom Schrittwechsel): danach erneut prüfen, dann weiter.
            queued = () -> autoSave(feedback, then);
            return;
        }
        save(null, feedback, then, true);
    }

    private void save(javax.swing.JComponent save, Feedback feedback, Runnable then, boolean quiet) {
        AiDraft draft = draft();
        if (!draft.problems().isEmpty()) {
            feedback.warning(quiet ? "KI-Einstellungen nicht übernommen" : "Bitte prüfen",
                    String.join(" ", draft.problems()));
            return;
        }
        // Ollama braucht keinen Key: weder ein eingetippter noch die Bezugsart zählen, die gespeicherte bleibt.
        boolean ollama = draft.ollama();
        char[] key = ollama ? new char[0] : typedKey();
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        AiAccess chosen = ollama ? shownAccess : accessDraft();
        int judged = judgedPairs();
        boolean automatic = automaticSynonyms();
        boolean glossaryOnly = glossarySynonyms();
        SimplificationSettings simplification = simplification();
        CheckSettings check = check();
        ImageSettings images = images();
        AnalyzerSettings analyzer = analyzer();
        if (draft.equals(current) && key.length == 0 && chosen.equals(shownAccess)
                && judged == savedBudget(settings).maxJudgedPairs() && automatic == savedBudget(settings).automatic()
                && glossaryOnly == savedBudget(settings).glossaryTermsOnly()
                && simplification.equals(savedSimplification(settings)) && check.equals(savedCheck(settings))
                && images.equals(savedImages(settings)) && analyzer.equals(savedAnalyzer(settings))) {
            if (!quiet) {
                feedback.info("Nichts geändert", null);
            }
            then.run();
            return;
        }
        if (!ollama && chosen.mode() == AiAccess.Mode.KEEPASS && chosen.entry() == null) {
            Arrays.fill(key, '\0');
            feedback.warning("Eintrag fehlt", "Bitte einen KeePass-Eintrag wählen oder einen neuen Namen eintippen.");
            return;
        }
        boolean known = chosen.entry() != null && titles.contains(chosen.entry()) || chosen.equals(savedAccess());
        if (!ollama && chosen.fromKeePass() && !known && key.length == 0) {
            feedback.warning("API-Key fehlt", "Für den neuen KeePass-Eintrag „" + chosen.entry() + "“ bitte den "
                    + "API-Key eingeben; er wird nur in KeePass gespeichert.");
            return;
        }
        if (!ollama && chosen.fromKeePass() && known && Boolean.FALSE.equals(keePassKeyPresent)) {
            // Zuletzt ohne Passwort gelesen: erst neu lesen, denn vielleicht ist es inzwischen in KeePass nachgetragen.
            String title = chosen.entry();
            Background.run(() -> {
                try (var read = context.keePass().readSilently(title)) {
                    char[] password = read.copyPassword();
                    boolean present = password.length > 0;
                    Arrays.fill(password, '\0');
                    return present;
                }
            }, present -> {
                if (present && title.equals(entryTitle()) && keePassChosen()) {
                    entryChosen(); // zeigt den Key wieder im Feld
                    save(save, feedback, then, quiet);
                } else {
                    feedback.warning("API-Key fehlt", "Der KeePass-Eintrag „" + title + "“ hat kein Passwort. "
                            + "Bitte den API-Key dort als Passwort eintragen und erneut speichern.");
                }
            }, error -> feedback.warning("Eintrag nicht lesbar", keePassReason(error)));
            return;
        }
        // Nur die Zerlegung für Embeddings übernimmt der Core erst beim Bauen; alles andere gilt ohne Neustart.
        boolean restart = !java.util.Objects.equals(draft.chunkTokens(), current.chunkTokens())
                || !java.util.Objects.equals(draft.chunkOverlapSentences(), current.chunkOverlapSentences());
        if (restart && !Dialogs.restartAllowed(root, context)) {
            Arrays.fill(key, '\0');
            return;
        }
        if (save != null) {
            save.setEnabled(false);
        }
        saving = true;
        feedback.working(quiet ? "KI-Einstellungen werden übernommen …" : "Wird gespeichert …");
        boolean vault = context.aiVault().available();
        AppRuntime runtime = context.runtime();
        Background.run(() -> {
            if (ollama) {
                Measured measured = measure(context, runtime, draft, new char[0], false);
                store(context, measured.draft(), new char[0], chosen, judged, automatic, glossaryOnly, simplification, check,
                        images, analyzer);
                return measured;
            }
            if (chosen.fromKeePass()) {
                // Neuer Eintrag: Basisadresse und Key gehen nach KeePass, nie in Tresor oder Datei.
                if (!known) {
                    context.keePass().create(chosen.entry(), draft.baseUrl(), key);
                }
                char[] resolved = currentKey(key, chosen, true);
                try {
                    Measured measured = measure(context, runtime, draft, resolved == null ? new char[0] : resolved,
                            false);
                    store(context, measured.draft(), new char[0], chosen, judged, automatic, glossaryOnly, simplification, check,
                        images, analyzer);
                    return measured;
                } finally {
                    if (resolved != null) {
                        Arrays.fill(resolved, '\0');
                    }
                }
            }
            Measured measured = measure(context, runtime, draft, key, true);
            store(context, measured.draft(), vault ? key.clone() : new char[0], chosen, judged, automatic, glossaryOnly,
                    simplification, check, images, analyzer);
            return measured;
        }, measured -> {
            if (!ollama && !chosen.fromKeePass() && !vault && key.length > 0) {
                context.sessionAiKey(key);
            }
            boolean serviceChanged = key.length > 0 || !chosen.equals(shownAccess)
                    || draft.provider() != current.provider()
                    || !java.util.Objects.equals(blankToNull(draft.baseUrl()), blankToNull(current.baseUrl()));
            Arrays.fill(key, '\0');
            if (save != null) {
                save.setEnabled(true);
            }
            if (serviceChanged) {
                // Anderer Dienst oder Key: Beim nächsten Betreten von „Modelle“ lädt der Assistent neu.
                forgetModels();
            }
            context.reload(() -> {
                saving = false;
                AiDraft now = draft();
                if (now.equals(draft)) {
                    render();
                } else {
                    // Inzwischen weiter getippt: nichts überschreiben, nur den Key aus dem Feld nehmen. Die gemessene
                    // Dimension nur, wenn Feld und Embedding-Modell noch so sind wie beim Speichern.
                    if (!keyFromKeePass) {
                        apiKey.setText("");
                    }
                    String measuredDimension = measured.draft().embeddingDimensions();
                    if (measuredDimension != null
                            && java.util.Objects.equals(blankToNull(now.embeddingDimensions()),
                            blankToNull(draft.embeddingDimensions()))
                            && java.util.Objects.equals(blankToNull(now.embeddingModel()),
                            blankToNull(draft.embeddingModel()))) {
                        dimensions.setText(measuredDimension);
                    }
                }
                if (measured.problem() == null) {
                    feedback.success("KI-Einstellungen übernommen");
                } else {
                    feedback.warning("Gespeichert, semantische Suche bleibt aus", measured.problem());
                }
                then.run();
                runQueued();
            });
        }, error -> {
            Arrays.fill(key, '\0');
            saving = false;
            queued = null;
            if (save != null) {
                save.setEnabled(true);
            }
            feedback.problem(error);
        });
    }

    private void runQueued() {
        Runnable next = queued;
        queued = null;
        if (next != null) {
            next.run();
        }
    }

    /**
     * Erst der Tresor, dann die Datei; scheitert die Datei, kommt der bisherige Tresoreintrag zurück. Läuft im
     * Hintergrund.
     *
     * @param key eingegebener API-Key, leer zum Behalten; wird geleert
     */
    static void store(AppContext context, AiDraft draft, char[] key) throws IOException {
        AppSettings settings = context.settings();
        store(context, draft, key, settings == null ? AiAccess.NONE : settings.aiAccess());
    }

    /** Wie {@link #store(AppContext, AiDraft, char[])}, dazu Bezugsart und KeePass-Eintrag des Keys. */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access) throws IOException {
        store(context, draft, key, access, null);
    }

    /**
     * Wie {@link #store(AppContext, AiDraft, char[], AiAccess)}, dazu die Paare je Synonymlauf für das Text-Modell
     * ({@code terms.synonyms.max-judged-pairs}); {@code null} oder unverändert lässt den Block {@code terms} in Ruhe.
     */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access, Integer judgedPairs)
            throws IOException {
        store(context, draft, key, access, judgedPairs, null, null, null);
    }

    /**
     * Wie {@link #store(AppContext, AiDraft, char[], AiAccess, Integer)}, dazu der Lauf nach jedem Abgleich
     * ({@code terms.synonyms.automatic}), die Vorstufe „Sätze vereinfachen“ ({@code statements.simplification.*}) und
     * der Prüfschritt ({@code statements.check.*}); {@code null} oder unverändert lässt den jeweiligen Block in Ruhe.
     */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access, Integer judgedPairs,
                      Boolean automatic, SimplificationSettings simplification, CheckSettings check)
            throws IOException {
        store(context, draft, key, access, judgedPairs, automatic, simplification, check, null);
    }

    /**
     * Wie {@link #store(AppContext, AiDraft, char[], AiAccess, Integer, Boolean, SimplificationSettings,
     * CheckSettings)}, dazu die automatische Bildbeschreibung ({@code images.*}); {@code null} oder unverändert lässt
     * sie in Ruhe.
     */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access, Integer judgedPairs,
                      Boolean automatic, SimplificationSettings simplification, CheckSettings check,
                      ImageSettings images) throws IOException {
        store(context, draft, key, access, judgedPairs, automatic, null, simplification, check, images, null);
    }

    /** Wie oben, dazu „Synonyme nur zu bestätigten Fachbegriffen“ (K3); {@code null} lässt den Wert stehen. */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access, Integer judgedPairs,
                      Boolean automatic, Boolean glossaryOnly, SimplificationSettings simplification,
                      CheckSettings check, ImageSettings images) throws IOException {
        store(context, draft, key, access, judgedPairs, automatic, glossaryOnly, simplification, check, images, null);
    }

    /** Wie oben, dazu die Aussagenanalyse ({@code statements.analyzer}); {@code null} oder unverändert lässt sie. */
    static void store(AppContext context, AiDraft draft, char[] key, AiAccess access, Integer judgedPairs,
                      Boolean automatic, Boolean glossaryOnly, SimplificationSettings simplification,
                      CheckSettings check, ImageSettings images, AnalyzerSettings analyzer) throws IOException {
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        String text = Files.isRegularFile(context.configFile())
                ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
        ConfigFileEditor.Draft written = editor.withAi(context.configFile(), new ConfigFileEditor.Draft(text, null),
                draft);
        written = editor.withAiAccess(context.configFile(), written, access);
        SynonymBudget budget = savedBudget(context.settings());
        SynonymBudget wanted = budget;
        if (judgedPairs != null) {
            wanted = wanted.withMaxJudgedPairs(judgedPairs);
        }
        if (automatic != null) {
            wanted = wanted.withAutomatic(automatic);
        }
        if (glossaryOnly != null) {
            wanted = wanted.withGlossaryTermsOnly(glossaryOnly);
        }
        if (!wanted.equals(budget)) {
            written = editor.withSynonymBudget(context.configFile(), written, wanted);
        }
        if (simplification != null && !simplification.equals(savedSimplification(context.settings()))) {
            written = editor.withSimplification(context.configFile(), written, simplification);
        }
        if (check != null && !check.equals(savedCheck(context.settings()))) {
            written = editor.withCheck(context.configFile(), written, check);
        }
        if (images != null && !images.equals(savedImages(context.settings()))) {
            written = editor.withImages(context.configFile(), written, images);
        }
        if (analyzer != null && !analyzer.equals(savedAnalyzer(context.settings()))) {
            written = editor.withAnalyzer(context.configFile(), written, analyzer);
        }
        boolean backup = !editor.fileHasPlaintextSecret(context.configFile());
        if (key.length == 0) {
            editor.save(context.configFile(), written, backup);
            return;
        }
        SecretVault vault = context.aiVault();
        String stored = vault.raw(KEY_ID);
        vault.store(KEY_ID, KEY_LOGIN, key);
        try {
            editor.save(context.configFile(), written, backup);
        } catch (IOException | RuntimeException e) {
            try {
                if (stored == null) {
                    vault.remove(KEY_ID);
                } else {
                    vault.restore(KEY_ID, stored);
                }
            } catch (IOException | RuntimeException restore) {
                e.addSuppressed(restore);
            }
            throw e;
        }
    }

    /** Das Budget der Synonymerkennung aus der Datei; ohne Datei der Standard des Core (Prüfung aus). */
    static SynonymBudget savedBudget(AppSettings settings) {
        return settings == null ? SynonymBudget.DEFAULT : settings.terms().synonyms();
    }

    /** Die Vorstufe „Sätze vereinfachen“ aus der Datei; ohne Datei die Vorgabe des Core. */
    static ImageSettings savedImages(AppSettings settings) {
        return settings == null ? ImageSettings.DEFAULT : settings.images();
    }

    /** Die eingestellte automatische Bildbeschreibung. */
    ImageSettings images() {
        return new ImageSettings(automaticImages.isSelected(), ((Number) imagesPerRun.getValue()).intValue());
    }

    /** Die Zeile zur automatischen Bildbeschreibung, für die Abschlussseite des Assistenten. */
    static String automaticImagesSummary(ImageSettings images) {
        return !images.automatic() ? AUTOMATIC_IMAGES_OFF : "nach jedem Abgleich, " + (images.unlimited()
                ? "unbegrenzt" : "höchstens " + images.maxPerRun() + " je Lauf");
    }

    static SimplificationSettings savedSimplification(AppSettings settings) {
        return settings == null ? SimplificationSettings.DEFAULT : settings.simplification();
    }

    /** Die eingestellte Vorstufe „Sätze vereinfachen“. */
    SimplificationSettings simplification() {
        return new SimplificationSettings(SIMPLIFY_ALWAYS.equals(simplifyMode.getSelectedItem())
                ? SimplificationSettings.Mode.ALWAYS : SimplificationSettings.Mode.WHERE_NEEDED,
                ((Number) simplifyParallel.getValue()).intValue(), simplifyModel.getText(),
                stepTokens(simplifyMaxTokens), simplifyThinking.level());
    }

    /** Die Vorstufe in einer Zeile, für Abschlussseite und Übersichten. */
    static String simplificationSummary(SimplificationSettings simplification) {
        return (simplification.mode() == SimplificationSettings.Mode.ALWAYS ? SIMPLIFY_ALWAYS : SIMPLIFY_WHERE_NEEDED)
                + " · " + simplification.parallel() + " " + SIMPLIFY_PARALLEL_UNIT + " · "
                + (simplification.model() == null ? "mit dem Text-Modell" : "Modell " + simplification.model());
    }

    /** Die Aussagenanalyse aus der Datei; ohne Datei die Vorgabe des Core (MaltParser). */
    static AnalyzerSettings savedAnalyzer(AppSettings settings) {
        return settings == null ? AnalyzerSettings.DEFAULT : settings.analyzer();
    }

    /** Die eingestellte Aussagenanalyse. */
    AnalyzerSettings analyzer() {
        return new AnalyzerSettings(ANALYZER_MODEL.equals(statementAnalyzer.getSelectedItem()) ? AnalyzerChoice.MODEL
                : AnalyzerChoice.PARSER, statementModel.getText(), stepTokens(statementMaxTokens),
                statementThinking.level());
    }

    /** Der Prüfschritt aus der Datei; ohne Datei die Vorgabe des Core. */
    static CheckSettings savedCheck(AppSettings settings) {
        return settings == null ? CheckSettings.DEFAULT : settings.check();
    }

    /** Der eingestellte Prüfschritt. */
    CheckSettings check() {
        return new CheckSettings(CHECK_ALL.equals(checkScope.getSelectedItem()) ? CheckScope.ALL
                : CheckScope.UNCERTAIN_AND_STAGE, ((Number) checkParallel.getValue()).intValue(),
                checkModel.getText(), stepTokens(checkMaxTokens), checkThinking.level());
    }

    /**
     * Die Tokenlänge eines Schritts als Zahl; leer oder „wie Text-Modell“ heißt {@code null}. Eine ungültige Eingabe
     * gilt als nicht gesetzt (die Auswahl bietet nur gültige Werte, eine getippte Zahl außerhalb 1 bis 1000000 bleibt
     * ohne Wirkung).
     */
    static Integer stepTokens(TokenLengthBox box) {
        String text = box.text();
        if (!text.matches("\\d{1,7}")) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= AiDraft.MAX_CHAT_MAX_TOKENS ? value : null;
    }

    /** Hilfetext der Zeile eines Schritts mit eigenem Modell und eigener Tokenlänge. */
    private static String stepTokensHelp(String step, String keys) {
        return "Eigenes Modell nur für " + step + "; leer: das Text-Modell.\n\n" + TOKENS_HELP_TAIL + " „"
                + TOKENS_LIKE_CHAT + "“ übernimmt Tokenlänge bzw. Nachdenken des Text-Modells.\n\nGilt ohne Neustart beim "
                + "nächsten Lauf. Schlüssel in der Konfigurationsdatei: " + keys + ".";
    }

    /** Der Prüfschritt in einer Zeile, für Abschlussseite und Übersichten. */
    static String checkSummary(CheckSettings check) {
        return (check.scope() == CheckScope.ALL ? CHECK_ALL : CHECK_UNCERTAIN_AND_STAGE)
                + " · " + check.parallel() + " " + CHECK_PARALLEL_UNIT + " · "
                + (check.model() == null ? "mit dem Text-Modell" : "Modell " + check.model());
    }

    /** Ob Synonyme nur zu bestätigten Fachbegriffen gesucht werden (K3). */
    boolean glossarySynonyms() {
        return glossarySynonyms.isSelected();
    }

    /** Ob nach jedem Abgleich einer Quelle ein Synonymlauf startet. */
    boolean automaticSynonyms() {
        return automaticSynonyms.isSelected();
    }

    /** Die Zeile zum Lauf nach jedem Abgleich, für die Abschlussseite des Assistenten. */
    static String automaticSynonymsSummary(SynonymBudget budget) {
        return budget.automatic() ? AUTOMATIC_SYNONYMS_ON : AUTOMATIC_SYNONYMS_OFF;
    }

    /** Die eingestellten Paare je Synonymlauf für das Text-Modell; 0 = Prüfung aus. */
    int judgedPairs() {
        return ((Number) judgedPairs.getValue()).intValue();
    }

    /** Ein Zahlenfeld von {@code minimum} bis {@code maximum} im Stil der Dauer-Felder dieser App. */
    /** Wie {@link #countSpinner(int, int, int)}, mit einem anderen Anfangswert als dem kleinsten. */
    /**
     * „Spracheingabe live“: dieselbe Umschaltung wie der Klick auf die Wellen im Chat, dazu die Werte des gleitenden
     * Fensters (Angelo 26.11.2026: keine fest eingebauten Werte). Gespeichert im App-Zustand, gilt ab der nächsten
     * Aufnahme bzw. sofort für die Umschaltung.
     */
    private javax.swing.JComponent liveDictationRow() {
        javax.swing.JCheckBox live = Comic.checkBox("live mitschreiben");
        live.setSelected(com.aresstack.spaceharvester.app.speech.LiveDictationSettings.live());
        live.addActionListener(e -> com.aresstack.spaceharvester.app.speech.LiveDictationSettings.live(
                live.isSelected()));
        com.aresstack.spaceharvester.app.speech.LiveDictationSettings.listen(() -> javax.swing.SwingUtilities
                .invokeLater(() -> live.setSelected(
                        com.aresstack.spaceharvester.app.speech.LiveDictationSettings.live())));
        return Comic.row(live, new HelpButton(Dialogs.CLOSE_SIZE, LIVE_DICTATION_LABEL, LIVE_DICTATION_HELP));
    }

    /** Die Werte des gleitenden Fensters unter „Erweitert“. */
    private javax.swing.JComponent liveDictationTuningRow() {
        com.aresstack.spaceharvester.app.speech.LiveDictationSettings.Tuning tuning =
                com.aresstack.spaceharvester.app.speech.LiveDictationSettings.tuning();
        liveDictationStep.setValue(tuning.stepMillis());
        JSpinner step = liveDictationStep;
        JSpinner window = countSpinner(
                com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MIN_WINDOW_SECONDS,
                com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MAX_WINDOW_SECONDS, 1,
                tuning.windowSeconds());
        JSpinner first = countSpinner(com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MIN_FIRST_MILLIS,
                com.aresstack.spaceharvester.app.speech.LiveDictationSettings.MAX_FIRST_MILLIS, 100,
                tuning.firstMillis());
        javax.swing.event.ChangeListener save = e -> com.aresstack.spaceharvester.app.speech.LiveDictationSettings
                .tuning(new com.aresstack.spaceharvester.app.speech.LiveDictationSettings.Tuning(
                        ((Number) step.getValue()).intValue(), ((Number) window.getValue()).intValue(),
                        ((Number) first.getValue()).intValue()));
        step.addChangeListener(save);
        window.addChangeListener(save);
        first.addChangeListener(save);
        // Der Takt steht vorn: an ihm erkennt „Erweitert“ die Zeile.
        return Comic.row(step, Comic.text("ms Takt,"), window, Comic.text("s Fenster,"), first,
                Comic.text("ms Anlauf"),
                new HelpButton(Dialogs.CLOSE_SIZE, LIVE_DICTATION_TUNING_LABEL, LIVE_DICTATION_HELP));
    }

    /** Die Grenzen der Skala um den Vorlese-Knopf unter „Erweitert“; jede Änderung wird gleich gemerkt. */
    private javax.swing.JComponent readAloudDialRow() {
        com.aresstack.spaceharvester.app.speech.ReadAloudRate rates =
                com.aresstack.spaceharvester.app.speech.ReadAloudRate.shared();
        com.aresstack.spaceharvester.app.speech.ReadAloudRate.Limits limits = rates.limits();
        readAloudRateMin.setValue(limits.minPercent());
        JSpinner min = readAloudRateMin;
        JSpinner max = countSpinner(com.aresstack.spaceharvester.app.speech.ReadAloudRate.NORMAL_PERCENT,
                com.aresstack.spaceharvester.app.speech.ReadAloudRate.MAX_PERCENT, 5, limits.maxPercent());
        JSpinner step = countSpinner(1, com.aresstack.spaceharvester.app.speech.ReadAloudRate.MAX_STEP_PERCENT, 1,
                limits.stepPercent());
        JSpinner jump = countSpinner(1, com.aresstack.spaceharvester.app.speech.ReadAloudRate.MAX_JUMP_SENTENCES, 1,
                limits.jumpSentences());
        javax.swing.event.ChangeListener save = e -> rates.limits(
                new com.aresstack.spaceharvester.app.speech.ReadAloudRate.Limits(
                        ((Number) min.getValue()).intValue(), ((Number) max.getValue()).intValue(),
                        ((Number) step.getValue()).intValue(), ((Number) jump.getValue()).intValue()));
        min.addChangeListener(save);
        max.addChangeListener(save);
        step.addChangeListener(save);
        jump.addChangeListener(save);
        // Das langsamste Tempo steht vorn: an ihm erkennt „Erweitert“ die Zeile.
        return Comic.row(Comic.text("Tempo"), min, Comic.text("bis"), max, Comic.text("%, Raster"), step,
                Comic.text("%, Sprung bis"), jump, Comic.text("Sätze"),
                new HelpButton(Dialogs.CLOSE_SIZE, READ_ALOUD_DIAL_LABEL, READ_ALOUD_DIAL_HELP));
    }

    private static JSpinner countSpinner(int minimum, int maximum, int step, int initial) {
        JSpinner spinner = countSpinner(minimum, maximum, step);
        spinner.setValue(initial);
        return spinner;
    }

    private static JSpinner countSpinner(int minimum, int maximum, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(minimum, minimum, maximum, step));
        spinner.setFont(Comic.regular(13f));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            Comic.style(editor.getTextField());
            editor.getTextField().setColumns(6);
            editor.getTextField().setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 4));
        }
        spinner.setBorder(Comic.fieldBorder());
        return spinner;
    }

    /** Die Eingaben mit Dimension, falls gemessen; {@code problem} sagt, warum nicht. */
    record Measured(AiDraft draft, String problem) {
    }

    /**
     * Misst die Dimension des Embedding-Modells, wenn sie fehlt oder das Modell gegenüber der Datei wechselt und die
     * Dimension noch die alte ist. Läuft im Hintergrund (Tresor, Netz); scheitert die Messung, bleibt das Feld leer
     * und die semantische Suche aus.
     *
     * @param key    eingegebener oder aufgelöster API-Key, leer für den manuell gespeicherten; bleibt unverändert
     * @param manual ob der Key manuell bezogen wird; nur dann fällt ein leerer Key auf Tresor oder Sitzung zurück
     */
    static Measured measure(AppContext context, AppRuntime runtime, AiDraft draft, char[] key, boolean manual) {
        String model = blankToNull(draft.embeddingModel());
        if (model == null || blankToNull(draft.baseUrl()) == null) {
            return new Measured(draft, null);
        }
        AppSettings settings = context.settings();
        AppSettings.AiSettings saved = settings == null ? AppSettings.AiSettings.NONE : settings.ai();
        String savedDimensions = saved.embeddingDimensions() == null ? null
                : String.valueOf(saved.embeddingDimensions());
        boolean stale = !model.equals(saved.embeddingModel())
                && java.util.Objects.equals(blankToNull(draft.embeddingDimensions()), savedDimensions);
        if (blankToNull(draft.embeddingDimensions()) != null && !stale) {
            return new Measured(draft, null);
        }
        AiDraft without = withDimensions(draft, null);
        if (runtime == null) {
            return new Measured(without, "Die Dimension ließ sich nicht messen, weil die App nicht gestartet ist.");
        }
        // Ohne eingetippten Key nur der manuell gespeicherte, und nur bei „Manuell“; aus KeePass löst der Aufrufer auf.
        char[] stored = key.length > 0 || !manual ? null : context.manualAiKey();
        try {
            int measured = runtime.workspace().aiEmbeddingDimension(service(without, key.length > 0 ? key : stored),
                    model);
            return new Measured(withDimensions(draft, String.valueOf(measured)), null);
        } catch (AiServiceException e) {
            boolean keyGiven = key.length > 0 || stored != null && stored.length > 0;
            return new Measured(without, "Die Dimension ließ sich nicht messen: " + reason(e, keyGiven) + ".");
        } catch (RuntimeException e) {
            return new Measured(without, "Die Dimension ließ sich nicht messen: " + Problems.describe(e).title()
                    + ".");
        } finally {
            if (stored != null) {
                Arrays.fill(stored, '\0');
            }
        }
    }

    private static AiDraft withDimensions(AiDraft draft, String dimensions) {
        return draft.withModels(draft.chatModel(), draft.embeddingModel(), dimensions, draft.rerankModel(),
                draft.chunkTokens(), draft.chunkOverlapSentences());
    }

    /** Die Eingaben als Einstellungen des Core, mit dem API-Key nur im Speicher. */
    static AiServiceSettings service(AiDraft draft, char[] key) {
        return new AiServiceSettings(URI.create(draft.baseUrl().strip()),
                key == null || key.length == 0 || draft.ollama() ? null : new String(key),
                blankToNull(draft.chatModel()), blankToNull(draft.embeddingModel()), draft.dimensions(),
                draft.timeout(), null, Set.of()).withProvider(draft.provider()).withContextTokens(draft.context())
                .withLoadTimeout(draft.loadDuration()).withRetries(draft.retryCount())
                .withLoadPollInterval(draft.pollDuration()).withParallel(draft.parallelCount());
    }

    static final String TOKENS_UNIT = "Tokens";
    private static final String TOKENS_HELP_TAIL = "Rechts daneben steht die Tokenlänge: So viele Tokens darf eine "
            + "Antwort höchstens lang werden. Sie geht bei jeder Anfrage mit (OpenAI-kompatibel als max_tokens, bei "
            + "Ollama als num_predict). Zur Auswahl stehen 2048, 8192, 16384, 32768 und 65536 wie in 3D4J; jede andere "
            + "Zahl kann man eintippen. Endet eine Antwort an der Grenze, gilt sie als abgeschnitten und der Schritt "
            + "meldet das; dann die Tokenlänge erhöhen.\n\nGanz rechts steht das Nachdenken: „denkt nicht“ antwortet "
            + "direkt, „denkt wenig/mittel/viel“ lässt ein Modell mit Nachdenk-Modus erst nachdenken (dauert länger "
            + "und braucht Tokens). Es geht bei jeder Anfrage mit (bei Ollama als think, OpenAI-kompatibel als "
            + "reasoning_effort; „denkt nicht“ dort ohne Angabe), damit nie ein unbekannter Standard des Dienstes "
            + "gilt. Legt ein Schritt das Nachdenken aus gutem Grund selbst fest, bleibt das.";
    static final String CHAT_MAX_TOKENS_HELP = "Das Text-Modell formuliert Suchvarianten, prüft Synonyme, schreibt "
            + "Zusammenfassungen und Podcast-Texte und beantwortet den Chat. Ohne eigenes Modell gilt es auch für "
            + "Aussagenanalyse, Vereinfachen, Prüfen und die Bildgenerierung.\n\n" + TOKENS_HELP_TAIL
            + "\n\nStandard " + AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS + ". Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.ai.chat-model, .chat-max-tokens und .step-thinking (Standard: denkt nicht).";
    static final String VISION_MAX_TOKENS_HELP = "Das Vision-Modell beschreibt die Bilder einer Seite (Leseansicht › "
            + "Bilder) und die Folien eines Videos; leer: keine Bildbeschreibung.\n\n" + TOKENS_HELP_TAIL
            + "\n\nStandard " + AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS + ". Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.ai.vision-model, .vision-max-tokens und .vision-thinking (leer: wie Text-Modell).";
    static final String IMAGE_LABEL = "Bildgenerierung – Einzelteile";
    static final String IMAGE_PLAN_LABEL = "Bildgenerierung – Plan";
    static final String IMAGE_HELP = "Dieses Modell zeichnet Bilder und Schaubilder als SVG, etwa für den Podcast. "
            + "Ohne eigenes Modell gilt das Text-Modell.\n\n" + TOKENS_HELP_TAIL + " Ein SVG-Schaubild ist lang; "
            + "deshalb ist der Standard hier höher (" + AiServiceSettings.DEFAULT_IMAGE_MAX_TOKENS + "), sonst bricht "
            + "die Antwort meist mittendrin ab.\n\nGilt ohne Neustart. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.ai.image-model, .image-max-tokens und .image-thinking (leer: wie Text-Modell).";

    static final String IMAGE_PLAN_HELP = "Dieses Modell plant ein Schaubild: welche Teile es hat und wie sie "
            + "angeordnet sind. Die Teile zeichnet danach die Zeile „Bildgenerierung – Einzelteile“. Leer: das "
            + "Text-Modell.\n\n" + TOKENS_HELP_TAIL + "\n\nStandard " + AiServiceSettings.DEFAULT_IMAGE_PLAN_MAX_TOKENS
            + " Tokens, denkt nicht. Schlüssel in der Konfigurationsdatei: space-harvester.ai.image-plan-model, "
            + ".image-plan-max-tokens und .image-plan-thinking.";
    static final String DEADLINE_LABEL = "Fristen";
    static final String DEADLINE_HELP = "Dieses Modell liest Erlasse und Verfügungen für die Fristenprüfung: Konten, "
            + "Fristaussagen, Ausnahmen, Antrag oder Genehmigung, jeweils mit wörtlichem Zitat. Leer: das Text-Modell."
            + "\n\n" + TOKENS_HELP_TAIL + "\n\nStandard " + AiServiceSettings.DEFAULT_DEADLINE_MAX_TOKENS
            + " Tokens, denkt nicht. Schlüssel in der Konfigurationsdatei: space-harvester.ai.deadline-model, "
            + ".deadline-max-tokens und .deadline-thinking.";
    static final String TIMEOUTS_LABEL = "Fristen je Anfrage";
    /** Größte Zahl im Feld einer Frist (in der gewählten Einheit). */
    static final int TIMEOUT_MAX_AMOUNT = 999;
    static final String TIMEOUTS_HELP = "So lange wartet die App höchstens auf eine Antwort des Modells, getrennt je "
            + "Aufgabe: Update-Zusammenfassung (Vorgabe 5 Minuten; ein längerer allgemeiner Timeout gewinnt), Namen "
            + "der Themenabschnitte in der Gliederung (Vorgabe 60 Sekunden) und Auflösung von Akronymen (Vorgabe "
            + "90 Sekunden). Große Modelle oder ein langsamer Dienst brauchen mehr.\n\nGilt ab der nächsten "
            + "Anfrage. Schlüssel in der Konfigurationsdatei: space-harvester.ai.summary-timeout, .label-timeout und "
            + ".expansion-timeout.";
    static final String DIAGNOSIS_LABEL = "Diagnose";
    static final String DIAGNOSIS_TEXT = "Diagnose per KI";
    static final String DIAGNOSIS_TIP = "Ohne Häkchen verschwinden alle KI-Erweiterungen der Diagnose, etwa "
            + "„Binärdiagnose …“ im Kontextmenü und die Diagnosewerkzeuge im Chat";
    static final String DIAGNOSIS_HELP = "Diagnose per KI: Mit Häkchen kann die KI Fehler und Daten analysieren, "
            + "etwa die Binärdiagnose eines MVS-Datasets („Binärdiagnose …“ im Kontextmenü), und im Chat zu Problemen "
            + "helfen. Die Werkzeuge der Diagnose bekommt der Chat nur, wenn dort dieses Modell gewählt ist. Ohne Häkchen verschwinden Kontextmenü-Eintrag und Werkzeuge.\n\nVorbelegt mit "
            + AiServiceSettings.DEFAULT_DIAGNOSIS_MODEL + ", je nach Aufgabe änderbar. " + TOKENS_HELP_TAIL
            + "\n\nSchlüssel in der Konfigurationsdatei: space-harvester.ai.diagnosis-enabled, .diagnosis-model, "
            + ".diagnosis-max-tokens und .diagnosis-thinking.";
    static final String SUMMARY_PLAN_LABEL = "Zusammenfassung – Plan";
    static final String SUMMARY_PLAN_HELP = "Dieses Modell plant die Update-Zusammenfassung: welche Themen es gibt und "
            + "welche Seiten dazugehören. Dafür passt ein großes Modell (etwa gpt-oss 120b). Leer: das Text-Modell.\n\n"
            + TOKENS_HELP_TAIL + "\n\nStandard " + AiServiceSettings.DEFAULT_SUMMARY_PLAN_MAX_TOKENS + " Tokens, denkt "
            + "nicht. Schlüssel in der Konfigurationsdatei: space-harvester.ai.summary-plan-model, "
            + ".summary-plan-max-tokens und .summary-plan-thinking.";
    static final String SUMMARY_LABEL = "Zusammenfassung";
    static final String SUMMARY_HELP = "Dieses Modell schreibt die Teile der Update-Zusammenfassung (Themen mit "
            + "Details). Leer: das Text-Modell.\n\n" + TOKENS_HELP_TAIL + " Nachdenken passt hier selten: Es verbraucht "
            + "die Tokenlänge, und die Antwort bricht ab.\n\nStandard " + AiServiceSettings.DEFAULT_SUMMARY_MAX_TOKENS
            + " Tokens, denkt nicht. Schlüssel in der Konfigurationsdatei: space-harvester.ai.summary-model, "
            + ".summary-max-tokens und .summary-thinking.";
    static final String PODCAST_LABEL = "Podcast";
    static final String PODCAST_SPEECH_LABEL = "Podcast – Sprache";
    static final String PODCAST_SPEECH_HELP = "Sprachmodell (TTS) und die beiden Stimmen des Podcasts, unabhängig vom "
            + "Vorlesen. Die Auswahl zeigt die Stimmen der Modellfamilie; jede andere Stimme lässt sich eintippen.\n\n"
            + "Leer: das bevorzugte Sprachmodell des Dienstes, Gastgeber die Standardstimme seiner Familie, Co-Host die "
            + "erste Stimme der anderen Stimmlage. Schlüssel in der Konfigurationsdatei: "
            + "space-harvester.ai.podcast-speech-model, .podcast-voice-host und .podcast-voice-cohost.";
    static final String READ_ALOUD_LABEL = "Vorlesen";
    static final String READ_ALOUD_HELP = "Sprachmodell (TTS) und Stimme für das Vorlesen im Chat und in der "
            + "Leseansicht, unabhängig vom Podcast. Lange Texte werden absatzweise gesprochen, Doppelklick liest ab "
            + "der Stelle.\n\nLeer: das bevorzugte Sprachmodell des Dienstes mit der Standardstimme seiner Familie. "
            + "Schlüssel in der Konfigurationsdatei: space-harvester.ai.read-aloud-model und .read-aloud-voice.";
    static final String PODCAST_HELP = "Dieses Modell plant den Podcast und schreibt die Sprechertexte. Stimmen und "
            + "Bilder haben eigene Einstellungen. Leer: das Text-Modell.\n\n" + TOKENS_HELP_TAIL + "\n\nStandard "
            + AiServiceSettings.DEFAULT_PODCAST_MAX_TOKENS + " Tokens, denkt nicht. Schlüssel in der "
            + "Konfigurationsdatei: space-harvester.ai.podcast-model, .podcast-max-tokens und .podcast-thinking.";

    static final String UNAUTHENTICATED = "Nicht authentifiziert (401/403): API-Key prüfen";
    static final String NOT_SIGNED_IN = "Nicht angemeldet – API-Key fehlt";
    private static final java.util.regex.Pattern HTTP_STATUS = java.util.regex.Pattern.compile("HTTP (\\d{3})");

    /** Wie {@link #reason(AiServiceException, boolean)} mit eingetragenem Key. */
    static String reason(AiServiceException e) {
        return reason(e, true);
    }

    /**
     * {@code AiServiceException.Reason} verständlich, ohne Adresse oder Schlüssel. Abgelehnt ohne Key heißt „nicht
     * angemeldet“, mit Key „nicht authentifiziert“. Meldet der Core 401/403 noch als unerwartete Antwort, erkennt das
     * der HTTP-Status in seiner Meldung ({@link #httpStatus(Throwable)}).
     *
     * @param keyGiven ob die Anfrage einen API-Key trug
     */
    static String reason(AiServiceException e, boolean keyGiven) {
        if (unauthorized(e)) {
            return keyGiven ? UNAUTHENTICATED : NOT_SIGNED_IN;
        }
        int status = httpStatus(e);
        return switch (e.reason()) {
            case NOT_CONFIGURED -> "kein Dienst eingetragen";
            case UNAUTHORIZED -> keyGiven ? UNAUTHENTICATED : NOT_SIGNED_IN;
            case UNREACHABLE -> "Dienst nicht erreichbar";
            case TIMEOUT -> "keine Antwort in der Frist";
            case INVALID_RESPONSE -> "unerwartete Antwort des Dienstes" + (status > 0 ? " (HTTP " + status + ")" : "");
        };
    }

    /** Ob der Dienst den Zugang abgelehnt hat: Grund {@code UNAUTHORIZED} oder HTTP 401/403 in der Meldung. */
    static boolean unauthorized(Throwable error) {
        if (!(error instanceof AiServiceException ai)) {
            return false;
        }
        int status = httpStatus(ai);
        return ai.reason() == AiServiceException.Reason.UNAUTHORIZED || status == 401 || status == 403;
    }

    /** Ob Warten helfen kann: nur ein nicht erreichbarer oder zu langsamer Dienst. */
    static boolean retryable(Throwable error) {
        return error instanceof AiServiceException ai && !unauthorized(ai)
                && (ai.reason() == AiServiceException.Reason.UNREACHABLE
                || ai.reason() == AiServiceException.Reason.TIMEOUT);
    }

    /** Der HTTP-Status aus der Meldung des Core („… HTTP 401“), sonst 0; die Meldung nennt nie den Key. */
    static int httpStatus(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = HTTP_STATUS.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * „Modelle laden“: im Assistenten (Schritt „Modelle“ offen) beginnt die Wiederholung neu, mit kurzer Wartezeit;
     * sonst ein einzelner Versuch.
     */
    void loadModels() {
        if (autoRetry) {
            modelRetry.start();
        } else {
            attemptModels(null);
        }
    }

    /**
     * Fragt die Modelle mit den aktuellen Eingaben ab; der API-Key bleibt im Speicher.
     *
     * @param outcome erfährt das Ende, wenn die Wiederholung den Versuch startet; sonst {@code null}
     */
    private void attemptModels(ModelRetry.Outcome outcome) {
        AiDraft draft = draft();
        if (blankToNull(draft.baseUrl()) == null) {
            modelsFeedback.warning("Basisadresse fehlt", "Erst die Adresse des KI-Dienstes eintragen.");
            failed(outcome, false);
            return;
        }
        if (!draft.problems().isEmpty()) {
            modelsFeedback.warning("Bitte prüfen", String.join(" ", draft.problems()));
            failed(outcome, false);
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            modelsFeedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Allgemein.");
            failed(outcome, false);
            return;
        }
        char[] typed = typedKey();
        AiAccess chosenAccess = accessDraft();
        boolean knownEntry = chosenAccess.entry() != null && titles.contains(chosenAccess.entry()) || chosenAccess.equals(savedAccess());
        int request = ++modelsRequest;
        String service = serviceKey(draft, chosenAccess);
        loadModels.setEnabled(false);
        modelsFeedback.working(outcome != null && modelRetry.attempts() > 1
                ? "Modelle werden geladen … (Versuch " + modelRetry.attempts() + ")" : "Modelle werden geladen …");
        boolean[] keyGiven = {false};
        boolean ollama = draft.ollama();
        Background.run(() -> {
            char[] key = ollama ? null : currentKey(typed, chosenAccess, knownEntry);
            keyGiven[0] = key != null && key.length > 0;
            try {
                return runtime.workspace().aiModels(service(draft, key));
            } finally {
                if (key != null) {
                    Arrays.fill(key, '\0');
                }
            }
        }, loaded -> {
            Arrays.fill(typed, '\0');
            if (stale(request, service, outcome)) {
                return;
            }
            loadModels.setEnabled(true);
            showModels(loaded);
            loadedService = service;
            if (outcome != null) {
                outcome.succeeded();
            }
        }, error -> {
            Arrays.fill(typed, '\0');
            if (stale(request, service, outcome)) {
                return;
            }
            loadModels.setEnabled(true);
            modelsFailed(error, keyGiven[0], outcome);
        });
    }

    /** Wofür eine Modellliste gilt: Adresse und Bezugsart des Keys (nie der Key selbst). */
    private static String serviceKey(AiDraft draft, AiAccess access) {
        return draft.provider().key() + "|" + blankToNull(draft.baseUrl()) + "|" + access.mode() + "|"
                + access.entry();
    }

    private String currentService() {
        return serviceKey(draft(), accessDraft());
    }

    /**
     * Ob eine Antwort veraltet ist: Eine neuere Anfrage läuft, oder die Eingaben nennen inzwischen einen anderen
     * Dienst. Dann zeigt sie nichts und wählt nichts vor; war es die jüngste Anfrage, gilt sie als Fehlschlag, nach
     * dem die Wiederholung den neuen Dienst fragt.
     */
    private boolean stale(int request, String service, ModelRetry.Outcome outcome) {
        boolean newest = request == modelsRequest;
        if (newest && service.equals(currentService())) {
            return false;
        }
        discardedModelAnswers++;
        if (newest) {
            loadModels.setEnabled(true);
            failed(outcome, true);
        }
        return true;
    }

    /** Zeigt, warum „Modelle laden“ scheiterte, und sagt der Wiederholung, ob Warten helfen kann. */
    void modelsFailed(Throwable error, boolean keyGiven, ModelRetry.Outcome outcome) {
        if (error instanceof AiServiceException ai) {
            lastFailure = reason(ai, keyGiven);
            modelsFeedback.warning("Modelle nicht geladen", lastFailure + ".");
        } else {
            lastFailure = Problems.describe(error).title();
            modelsFeedback.problem(error);
        }
        failed(outcome, retryable(error));
    }

    private static void failed(ModelRetry.Outcome outcome, boolean retryable) {
        if (outcome != null) {
            outcome.failed(retryable);
        }
    }

    /** Statuszeile der Wiederholung: „Versuch 3 – nächster in 20 s …“ mit dem Grund. */
    private void retrying(int attempt, Duration next) {
        modelsFeedback.warning("Versuch " + attempt + " – nächster in " + next.toSeconds() + " s …",
                "Modelle nicht geladen: " + lastFailure + ". „Modelle laden“ versucht es sofort.");
    }

    /** Beendet die Wiederholung, etwa beim Verlassen von „Modelle“ oder Schließen des Assistenten. */
    void stopModels() {
        autoRetry = false;
        modelRetry.stop();
    }

    /** Vergisst die geladene Liste, damit der Assistent beim nächsten Betreten von „Modelle“ neu lädt. */
    private void forgetModels() {
        loaded = List.of();
        loadedService = "";
        // Eine noch laufende Anfrage gilt dem alten Dienst; ihre Antwort verfällt.
        modelsRequest++;
        loadModels.setEnabled(true);
        pickChat.setEnabled(false);
        pickSimplify.setEnabled(false);
        pickCheck.setEnabled(false);
        pickEmbedding.setEnabled(false);
        pickRerank.setEnabled(false);
        pickVision.setEnabled(false);
        pickImage.setEnabled(false);
        pickSummary.setEnabled(false);
        pickSummaryPlan.setEnabled(false);
        pickPodcast.setEnabled(false);
        pickImagePlan.setEnabled(false);
        pickDeadline.setEnabled(false);
        pickDiagnosis.setEnabled(false);
        pickPodcastSpeech.setEnabled(false);
        pickReadAloud.setEnabled(false);
    }

    /** Übernimmt eine geladene Liste; für Tests auch direkt. */
    /** Erweitert auf- oder zuklappen; eigene Werte bleiben sichtbar, damit nichts verdeckt wirkt. */
    /** Zeigt den Stand des Parsermodells in der Zeile; Fortschritt und Fehler im Statusbereich der Modelle. */
    void parserModel(ParserModelSetup.Status status) {
        switch (status.state()) {
            case INSTALLED -> parserStatus.setText(ParserModelSetup.INSTALLED);
            case NOT_INSTALLED -> parserStatus.setText(ParserModelSetup.NOT_INSTALLED);
            case INSTALLING -> parserStatus.setText(status.percent() >= 0
                    ? "Wird installiert … " + status.percent() + " %" : "Wird installiert …");
            case UNAVAILABLE -> parserStatus.setText("Nicht verfügbar");
        }
        boolean installing = status.state() == ParserModelSetup.State.INSTALLING;
        parserInstall.setText(installing ? ParserModelSetup.CANCEL : ParserModelSetup.INSTALL);
        parserInstall.setVisible(status.state() == ParserModelSetup.State.NOT_INSTALLED || installing);
        if (installing) {
            modelsFeedback.working(LABEL_PARSER + status.message());
        } else if (status.message() != null && status.failed()) {
            modelsFeedback.warning("Parsermodell nicht installiert", status.message());
        } else if (status.message() != null) {
            modelsFeedback.success(status.message());
        }
    }

    /** Zeigt Auswahl und Stand des Whisper-Modells; Fortschritt und Fehler im Statusbereich der Modelle. */
    void whisperModel(WhisperModelSetup.Status status) {
        boolean installing = status.state() == WhisperModelSetup.State.INSTALLING;
        showingWhisper = true;
        try {
            whisperChoice.setSelectedIndex(context.whisper().ordinal());
            dictationChoice.setSelectedIndex(context.dictationWhisper().ordinal());
        } finally {
            showingWhisper = false;
        }
        // Während eines Downloads bleiben beide Auswahlen stehen, damit klar ist, welches Modell lädt.
        whisperChoice.setEnabled(!installing);
        dictationChoice.setEnabled(!installing);
        whisperRow(whisperInstall, context.whisper(), status);
        whisperRow(dictationInstall, context.dictationWhisper(), status);
        if (installing) {
            modelsFeedback.working(WhisperModelSetup.LABEL + " „" + status.model().label() + "“: "
                    + status.message());
        } else if (status.message() != null && status.failed()) {
            modelsFeedback.warning("Whisper-Modell nicht geladen", status.message());
        } else if (status.message() != null) {
            modelsFeedback.success(status.message());
        }
    }

    /**
     * Knopf einer Whisper-Zeile: „Laden“, solange ihr Modell fehlt; „Abbrechen“, während gerade ihr Modell lädt;
     * gesperrt, während ein anderes lädt (es läuft immer nur ein Download); unsichtbar, sobald das Modell da ist.
     */
    private void whisperRow(ComposerButton button, com.aresstack.spaceharvester.app.transcription.WhisperModel model, WhisperModelSetup.Status status) {
        boolean installing = status.state() == WhisperModelSetup.State.INSTALLING;
        boolean mine = installing && status.model() == model;
        button.setText(mine ? WhisperModelSetup.CANCEL : WhisperModelSetup.INSTALL);
        button.setEnabled(!installing || mine);
        button.setToolTipText(installing && !mine ? WHISPER_BUSY_TIP : WHISPER_INSTALL_TIP);
        button.setVisible(mine || !context.whisperModel().installed(model));
    }

    /** „Laden“ oder „Abbrechen“ einer Whisper-Zeile. */
    private void installWhisper(com.aresstack.spaceharvester.app.transcription.WhisperModel model) {
        WhisperModelSetup setup = context.whisperModel();
        if (setup.status().state() == WhisperModelSetup.State.INSTALLING) {
            if (setup.status().model() == model) {
                setup.cancel();
            }
        } else {
            setup.install(model);
        }
    }

    private static final String LABEL_PARSER = "Parsermodell: ";

    void advanced(boolean open) {
        modelRows.visible(dimensions, open);
        modelRows.visible(chunkTokens, open);
        modelRows.visible(chunkOverlap, open);
        modelRows.visible(simplifyParallel, open);
        modelRows.visible(checkParallel, open);
        modelRows.visible(podcastPause, open);
        modelRows.visible(liveDictationStep, open);
        modelRows.visible(readAloudRateMin, open);
        Comic.disclose(advanced, open);
        models.repaint();
        models.revalidate();
    }

    /** Ob „Erweitert“ aufgeklappt ist. */
    boolean advancedOpen() {
        return modelRows.shows(chunkTokens);
    }

    /**
     * Lädt die Modelle von selbst, wenn ein Dienst eingetragen ist und noch nichts geladen wurde, und wiederholt das
     * bei nicht erreichbarem Dienst langsam ({@link ModelRetry}), bis es gelingt oder {@link #stopModels()} kommt; der
     * Assistent ruft es beim Betreten von „Modelle“ auf, wie der Enterprise AI Client nach dem Verbindungstest.
     */
    void prepareModels() {
        autoRetry = true;
        if ((loaded.isEmpty() || !loadedService.equals(currentService())) && !modelRetry.active()
                && blankToNull(baseUrl.getText()) != null) {
            modelRetry.start();
        }
    }

    void showModels(List<AiModel> loaded) {
        this.loaded = List.copyOf(loaded);
        preselect(chatModel, AiModel.CHAT, "chat");
        preselect(embeddingModel, AiModel.EMBEDDING, "embedding");
        preselectIfPresent(visionModel, "vision");
        preselectIfPresent(rerankModel, "rerank");
        pickChat.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickSimplify.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickCheck.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickEmbedding.setEnabled(!choices(AiModel.EMBEDDING).isEmpty());
        pickRerank.setEnabled(!choices(null).isEmpty());
        pickVision.setEnabled(!choices(AiModel.VISION).isEmpty());
        pickImage.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickSummary.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickSummaryPlan.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickPodcast.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickImagePlan.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickDeadline.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickDiagnosis.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickPodcastSpeech.setEnabled(!choices(AiModel.TTS).isEmpty());
        pickReadAloud.setEnabled(!choices(AiModel.TTS).isEmpty());
        long embeddings = this.loaded.stream().filter(m -> m.categories().contains(AiModel.EMBEDDING)).count();
        if (this.loaded.isEmpty()) {
            modelsFeedback.warning("Keine Modelle", "Der Dienst meldet keine Modelle; Namen bitte von Hand eintragen.");
        } else {
            modelsFeedback.success(Texts.count(this.loaded.size(), "Modell", "Modelle") + " geladen"
                    + (embeddings == 0 ? "" : ", davon " + embeddings + " für Embeddings") + "; Auswahl über das Auswahlmenü rechts");
        }
    }

    /**
     * Modelle für ein Feld: die passende Kategorie zuerst, Modelle ohne Kategorie danach; {@code null} (Rerank, der
     * Dienst kennt keine eigene Kategorie dafür) bietet alle an.
     */
    List<AiModel> choices(String category) {
        return choices(loaded, category);
    }

    /**
     * Die Modelle aus {@code loaded}, die zu {@code category} passen, danach die ohne Kategorie; ohne Kategorie alle.
     * Dieselbe Auswahl in den Einstellungen und in der Modellwahl am Dreieck.
     */
    public static List<AiModel> choices(List<AiModel> loaded, String category) {
        if (category == null) {
            return loaded;
        }
        List<AiModel> fitting = new ArrayList<>();
        List<AiModel> unknown = new ArrayList<>();
        for (AiModel model : loaded) {
            if (model.categories().contains(category)) {
                fitting.add(model);
            } else if (model.categories().isEmpty()) {
                unknown.add(model);
            }
        }
        fitting.addAll(unknown);
        return fitting;
    }

    private void pick(JComponent anchor, JTextField field, String category) {
        JPopupMenu menu = new JPopupMenu();
        for (AiModel model : choices(category)) {
            JMenuItem item = new JMenuItem(model.displayName().equals(model.id()) ? model.id()
                    : model.displayName() + " (" + model.id() + ")");
            item.addActionListener(e -> choose(field, model.id()));
            menu.add(item);
        }
        if (anchor instanceof javax.swing.AbstractButton button) {
            PopupHover.clearOnClose(menu, button);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * Wählt wie der Enterprise AI Client ({@link ModelPreselection}) vor: eine Wahl, die der Dienst noch anbietet,
     * bleibt; sonst das Vorzugsmodell aus {@code setup-models.properties}, sonst das erste passende Modell.
     */
    private void preselect(JTextField field, String category, String kind) {
        List<String> ids = choices(category).stream().map(AiModel::id).toList();
        String chosen = ModelPreselection.choose(ids, field.getText(), preferred.model(kind), preferred.match(kind));
        if (!chosen.isEmpty() && !chosen.equals(field.getText().strip())) {
            choose(field, chosen);
        }
    }

    /**
     * Vision und Rerank: nur ein leeres Feld, und nur mit dem Vorzugsmodell aus {@code setup-models.properties}, wenn
     * der Dienst es anbietet ({@link ModelPreselection#present}); keine Ersatzwahl, eine Eingabe bleibt immer.
     */
    private void preselectIfPresent(JTextField field, String kind) {
        if (!field.getText().isBlank()) {
            return;
        }
        String chosen = ModelPreselection.present(loaded.stream().map(AiModel::id).toList(), preferred.model(kind));
        if (!chosen.isEmpty()) {
            choose(field, chosen);
        }
    }

    /** Setzt ein Modell; ein anderes Embedding-Modell leert die Dimension, damit Speichern sie neu misst. */
    void choose(JTextField field, String id) {
        if (field == embeddingModel && !id.equals(embeddingModel.getText().strip())) {
            dimensions.setText("");
        }
        field.setText(id);
    }

    private void removeKey() {
        if (!Dialogs.confirm(root, "API-Key entfernen?", "API-Key wirklich entfernen? Ohne API-Key "
                + "bleibt die semantische Suche aus.")) {
            return;
        }
        Background.execute(() -> context.aiVault().remove(KEY_ID), () -> {
            context.sessionAiKey(null);
            render();
            feedback.success("API-Key entfernt");
        }, feedback::problem);
    }
}
