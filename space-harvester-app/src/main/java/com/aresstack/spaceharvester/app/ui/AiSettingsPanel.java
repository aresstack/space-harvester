package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
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
    final JTextField baseUrl = Comic.field(32);
    final JPasswordField apiKey = new JPasswordField(32);
    final JTextField chatModel = Comic.field(24);
    final JTextField embeddingModel = Comic.field(24);
    final JTextField dimensions = Comic.field(6);
    /** Wie viele Quellen Inhalte an den Dienst geben dürfen; festgelegt im Formular jeder Quelle. */
    final JLabel released = Comic.text(" ");
    /** Die Freigaben aus der Datei; dieses Panel ändert sie nicht. */
    private List<String> releasedIds = List.of();
    private Duration timeout;
    final JTextField rerankModel = Comic.field(24);
    final JTextField chunkTokens = Comic.field(6);
    final JTextField chunkOverlap = Comic.field(6);
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    private final SettingDialogs.Rows modelRows;
    private final JPanel models = new JPanel(new BorderLayout(0, 10));
    final ComposerButton save = Comic.primary("Speichern", "KI-Einstellungen übernehmen und neu starten");
    final ComposerButton saveModels = Comic.primary("Speichern", "Modelle übernehmen und neu starten");
    final ComposerButton removeKey = Comic.button("API-Key entfernen", "Gespeicherten API-Key löschen");
    /** Meldungen im Reiter „KI-Dienst“ (im Assistenten die einzige). */
    final Feedback feedback = new Feedback();
    /** Meldungen im Reiter „Modelle“; im Assistenten dieselbe wie {@link #feedback}. */
    final Feedback modelsFeedback;
    final ComposerButton loadModels = Comic.button("Modelle laden", "Die Modelle des KI-Dienstes abfragen");
    final ComposerButton pickChat = Comic.button("▾", "Text-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickEmbedding = Comic.button("▾", "Embedding-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickRerank = Comic.button("▾", "Rerank-Modell aus der Liste des Dienstes wählen");
    /** Klappt Abschnittsgröße und Überlappung auf; sie sind keine Modelle, sondern gehören zur Zerlegung. */
    final ComposerButton advanced = Comic.button("▸ Erweitert", "Abschnittsgröße und Überlappung für Embeddings");
    private final PreferredModels preferred = PreferredModels.bundled();
    /** Die zuletzt geladenen Modelle; leer bis „Modelle laden“. */
    List<AiModel> loaded = List.of();

    AiSettingsPanel(AppContext context) {
        this.context = context;
        this.modelsFeedback = new Feedback();
        this.modelRows = new SettingDialogs.Rows();
        root.setOpaque(false);
        Comic.style(apiKey);
        rows.add("Basisadresse", baseUrl, "OpenAI-kompatibel, z. B. https://api.openai.com/v1");
        rows.add("API-Key", apiKey, " ");
        modelRows.add("Modelle", loadModels, "Fragt die Modelle beim Dienst ab und wählt die Vorzugsmodelle vor; andere über ▾");
        modelRows.add("Text-Modell", Comic.row(chatModel, pickChat),
                "Für spätere Suchbegriffe aus einer Frage; optional");
        modelRows.add("Embedding-Modell", Comic.row(embeddingModel, pickEmbedding),
                "Für die semantische Suche, z. B. text-embedding-3-small");
        modelRows.add("Dimension", dimensions, "Leer: wird beim Speichern beim Dienst gemessen");
        {
            modelRows.add("Rerank-Modell", Comic.row(rerankModel, pickRerank),
                    "Cross-Encoder des Dienstes (/rerank); ordnet die Treffer aus dem Cache neu, leer: Reihenfolge "
                            + "bleibt");
            modelRows.add("", advanced, "Zerlegung der Inhalte für Embeddings; die Vorgaben passen meistens");
            modelRows.add("Abschnittsgröße", chunkTokens, "Höchstens so viele Tokens je Abschnitt für Embeddings; "
                    + "leer: 350");
            modelRows.add("Überlappung", chunkOverlap, "So viele Sätze wiederholen sich zwischen Abschnitten; leer: 1");
            advanced.addActionListener(e -> advanced(!modelRows.shows(chunkTokens)));
            advanced(false);
        }
        pickChat.setEnabled(false);
        pickEmbedding.setEnabled(false);
        pickRerank.setEnabled(false);
        loadModels.addActionListener(e -> loadModels());
        pickChat.addActionListener(e -> pick(pickChat, chatModel, AiModel.CHAT));
        pickEmbedding.addActionListener(e -> pick(pickEmbedding, embeddingModel, AiModel.EMBEDDING));
        pickRerank.addActionListener(e -> pick(pickRerank, rerankModel, null));
        rows.add("Freigegeben", released, "Je Quelle im Formular unter Wissensquellen › Bearbeiten …; ohne Freigabe "
                + "keine Embeddings und kein Reranking");
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
        save.addActionListener(e -> save(save, feedback));
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
            saveModels.addActionListener(e -> save(saveModels, modelsFeedback));
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
        baseUrl.setText(current.baseUrl() == null ? "" : current.baseUrl());
        chatModel.setText(current.chatModel() == null ? "" : current.chatModel());
        embeddingModel.setText(current.embeddingModel() == null ? "" : current.embeddingModel());
        dimensions.setText(current.embeddingDimensions() == null ? "" : current.embeddingDimensions());
        rerankModel.setText(current.rerankModel() == null ? "" : current.rerankModel());
        chunkTokens.setText(current.chunkTokens() == null ? "" : current.chunkTokens());
        chunkOverlap.setText(current.chunkOverlapSentences() == null ? "" : current.chunkOverlapSentences());
        advanced(current.chunkTokens() != null || current.chunkOverlapSentences() != null);
        timeout = current.timeout();
        // Freigaben gehören zur Quelle; dieses Panel zeigt nur ihre Zahl und schreibt sie unverändert zurück.
        releasedIds = current.externalProcessingSources();
        released.setText(releasedText(settings, releasedIds));
        apiKey.setText("");
        boolean stored = context.aiKeyAvailable();
        rows.hint(apiKey, stored ? "Gespeichert; leer lassen, um ihn zu behalten"
                : context.aiVault().available() ? "Fehlt; wird verschlüsselt im Tresor abgelegt"
                : "Fehlt; ohne Windows-Tresor gilt er nur bis zum Schließen der App");
        removeKey.setVisible(stored);
    }

    AiDraft draft() {
        return new AiDraft(baseUrl.getText(), chatModel.getText(), embeddingModel.getText(), dimensions.getText(),
                timeout, releasedIds, rerankModel.getText(), chunkTokens.getText(), chunkOverlap.getText());
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
        return !draft().equals(current) || apiKey.getPassword().length > 0;
    }

    private void save(ComposerButton save, Feedback feedback) {
        AiDraft draft = draft();
        if (!draft.problems().isEmpty()) {
            feedback.warning("Bitte prüfen", String.join(" ", draft.problems()));
            return;
        }
        char[] key = apiKey.getPassword();
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        if (draft.equals(current) && key.length == 0) {
            feedback.info("Nichts geändert", null);
            return;
        }
        if (!Dialogs.restartAllowed(root, context)) {
            Arrays.fill(key, '\0');
            return;
        }
        save.setEnabled(false);
        feedback.working("Wird gespeichert …");
        boolean vault = context.aiVault().available();
        AppRuntime runtime = context.runtime();
        Background.run(() -> {
            Measured measured = measure(context, runtime, draft, key);
            store(context, measured.draft(), vault ? key.clone() : new char[0]);
            return measured;
        }, measured -> {
            if (!vault && key.length > 0) {
                context.sessionAiKey(key);
            }
            Arrays.fill(key, '\0');
            save.setEnabled(true);
            context.reload(() -> {
                render();
                if (measured.problem() == null) {
                    feedback.success("KI-Einstellungen übernommen");
                } else {
                    feedback.warning("Gespeichert, semantische Suche bleibt aus", measured.problem());
                }
            });
        }, error -> {
            Arrays.fill(key, '\0');
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    /**
     * Erst der Tresor, dann die Datei; scheitert die Datei, kommt der bisherige Tresoreintrag zurück. Läuft im
     * Hintergrund.
     *
     * @param key eingegebener API-Key, leer zum Behalten; wird geleert
     */
    static void store(AppContext context, AiDraft draft, char[] key) throws IOException {
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        String text = Files.isRegularFile(context.configFile())
                ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
        ConfigFileEditor.Draft written = editor.withAi(context.configFile(), new ConfigFileEditor.Draft(text, null),
                draft);
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

    /** Die Eingaben mit Dimension, falls gemessen; {@code problem} sagt, warum nicht. */
    record Measured(AiDraft draft, String problem) {
    }

    /**
     * Misst die Dimension des Embedding-Modells, wenn sie fehlt oder das Modell gegenüber der Datei wechselt und die
     * Dimension noch die alte ist. Läuft im Hintergrund (Tresor, Netz); scheitert die Messung, bleibt das Feld leer
     * und die semantische Suche aus.
     *
     * @param key eingegebener API-Key oder leer für den gespeicherten; bleibt unverändert
     */
    static Measured measure(AppContext context, AppRuntime runtime, AiDraft draft, char[] key) {
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
        char[] stored = key.length > 0 ? null : context.storedAiKey();
        try {
            int measured = runtime.workspace().aiEmbeddingDimension(service(without, key.length > 0 ? key : stored),
                    model);
            return new Measured(withDimensions(draft, String.valueOf(measured)), null);
        } catch (AiServiceException e) {
            return new Measured(without, "Die Dimension ließ sich nicht messen: " + reason(e) + ".");
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
        return new AiServiceSettings(URI.create(draft.baseUrl().strip()), key == null || key.length == 0 ? null
                : new String(key), blankToNull(draft.chatModel()), blankToNull(draft.embeddingModel()),
                draft.dimensions(), draft.timeout(), null, Set.of());
    }

    /** {@code AiServiceException.Reason} verständlich, ohne Adresse oder Schlüssel. */
    static String reason(AiServiceException e) {
        return switch (e.reason()) {
            case NOT_CONFIGURED -> "kein Dienst eingetragen";
            case UNAUTHORIZED -> "API-Key abgelehnt";
            case UNREACHABLE -> "Dienst nicht erreichbar";
            case TIMEOUT -> "keine Antwort in der Frist";
            case INVALID_RESPONSE -> "unerwartete Antwort des Dienstes";
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Fragt die Modelle mit den aktuellen Eingaben ab; der API-Key bleibt im Speicher. */
    private void loadModels() {
        AiDraft draft = draft();
        if (blankToNull(draft.baseUrl()) == null) {
            modelsFeedback.warning("Basisadresse fehlt", "Erst die Adresse des KI-Dienstes eintragen.");
            return;
        }
        if (!draft.problems().isEmpty()) {
            modelsFeedback.warning("Bitte prüfen", String.join(" ", draft.problems()));
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            modelsFeedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Allgemein.");
            return;
        }
        char[] typed = apiKey.getPassword();
        loadModels.setEnabled(false);
        modelsFeedback.working("Modelle werden geladen …");
        Background.run(() -> {
            char[] key = typed.length > 0 ? typed : context.storedAiKey();
            try {
                return runtime.workspace().aiModels(service(draft, key));
            } finally {
                if (key != null) {
                    Arrays.fill(key, '\0');
                }
            }
        }, loaded -> {
            Arrays.fill(typed, '\0');
            loadModels.setEnabled(true);
            showModels(loaded);
        }, error -> {
            Arrays.fill(typed, '\0');
            loadModels.setEnabled(true);
            if (error instanceof AiServiceException ai) {
                modelsFeedback.warning("Modelle nicht geladen", reason(ai) + ".");
            } else {
                modelsFeedback.problem(error);
            }
        });
    }

    /** Übernimmt eine geladene Liste; für Tests auch direkt. */
    /** Erweitert auf- oder zuklappen; eigene Werte bleiben sichtbar, damit nichts verdeckt wirkt. */
    void advanced(boolean open) {
        modelRows.visible(chunkTokens, open);
        modelRows.visible(chunkOverlap, open);
        advanced.setText((open ? "▾" : "▸") + " Erweitert");
        models.revalidate();
    }

    /** Ob „Erweitert“ aufgeklappt ist. */
    boolean advancedOpen() {
        return modelRows.shows(chunkTokens);
    }

    /**
     * Lädt die Modelle einmal von selbst, wenn ein Dienst eingetragen ist und noch nichts geladen wurde; der
     * Assistent ruft es beim Betreten von „Modelle“ auf, wie der Enterprise AI Client nach dem Verbindungstest.
     */
    void prepareModels() {
        if (loaded.isEmpty() && loadModels.isEnabled() && blankToNull(baseUrl.getText()) != null) {
            loadModels();
        }
    }

    void showModels(List<AiModel> loaded) {
        this.loaded = List.copyOf(loaded);
        preselect(chatModel, AiModel.CHAT, "chat");
        preselect(embeddingModel, AiModel.EMBEDDING, "embedding");
        pickChat.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickEmbedding.setEnabled(!choices(AiModel.EMBEDDING).isEmpty());
        pickRerank.setEnabled(!choices(null).isEmpty());
        long embeddings = this.loaded.stream().filter(m -> m.categories().contains(AiModel.EMBEDDING)).count();
        if (this.loaded.isEmpty()) {
            modelsFeedback.warning("Keine Modelle", "Der Dienst meldet keine Modelle; Namen bitte von Hand eintragen.");
        } else {
            modelsFeedback.success(Texts.count(this.loaded.size(), "Modell", "Modelle") + " geladen"
                    + (embeddings == 0 ? "" : ", davon " + embeddings + " für Embeddings") + "; Auswahl über ▾");
        }
    }

    /**
     * Modelle für ein Feld: die passende Kategorie zuerst, Modelle ohne Kategorie danach; {@code null} (Rerank, der
     * Dienst kennt keine eigene Kategorie dafür) bietet alle an.
     */
    List<AiModel> choices(String category) {
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

    /** Setzt ein Modell; ein anderes Embedding-Modell leert die Dimension, damit Speichern sie neu misst. */
    void choose(JTextField field, String id) {
        if (field == embeddingModel && !id.equals(embeddingModel.getText().strip())) {
            dimensions.setText("");
        }
        field.setText(id);
    }

    private void removeKey() {
        if (!Dialogs.confirm(root, "API-Key entfernen?", "Ohne API-Key bleibt die semantische Suche aus.",
                "Entfernen")) {
            return;
        }
        Background.execute(() -> context.aiVault().remove(KEY_ID), () -> {
            context.sessionAiKey(null);
            render();
            feedback.success("API-Key entfernt");
        }, feedback::problem);
    }
}
