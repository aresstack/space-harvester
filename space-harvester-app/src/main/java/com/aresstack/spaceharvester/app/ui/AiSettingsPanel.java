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

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Einstellungen des KI-Dienstes (Reiter „KI“ unter Einstellungen, ADR 0011): Basisadresse eines OpenAI-kompatiblen
 * Dienstes, API-Key, Modelle für Text und Embeddings wie im Enterprise AI Client und die Wissensquellen, deren Inhalte
 * an den Dienst gehen dürfen (Vorgabe keine). Adresse und Modelle stehen unter
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
    /** Je Wissensquelle ein Häkchen: Inhalte dürfen an den KI-Dienst gehen. */
    final Map<String, JCheckBox> released = new LinkedHashMap<>();
    private final JPanel releasedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private Duration timeout;
    private final boolean wizard;
    /** Nur im Assistenten: Inhalte der neuen Quelle dürfen an den KI-Dienst gehen. */
    final JCheckBox releaseThis = new JCheckBox("Inhalte dieser Quelle an den KI-Dienst geben");
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    final ComposerButton save = Comic.primary("Speichern", "KI-Einstellungen übernehmen und neu starten");
    final ComposerButton removeKey = Comic.button("API-Key entfernen", "Gespeicherten API-Key löschen");
    final Feedback feedback = new Feedback();
    final ComposerButton loadModels = Comic.button("Modelle laden", "Die Modelle des KI-Dienstes abfragen");
    final ComposerButton pickChat = Comic.button("▾", "Text-Modell aus der Liste des Dienstes wählen");
    final ComposerButton pickEmbedding = Comic.button("▾", "Embedding-Modell aus der Liste des Dienstes wählen");
    /** Die zuletzt geladenen Modelle; leer bis „Modelle laden“. */
    List<AiModel> models = List.of();

    AiSettingsPanel(AppContext context) {
        this(context, false);
    }

    /**
     * @param wizard als optionale Seite im Erst-Assistenten: ohne eigene Knöpfe, statt der Quellenliste ein Häkchen
     *               für die neue Quelle; gespeichert wird mit der Quelle ({@link #draftFor})
     */
    AiSettingsPanel(AppContext context, boolean wizard) {
        this.context = context;
        this.wizard = wizard;
        root.setOpaque(false);
        Comic.style(apiKey);
        rows.add("Basisadresse", baseUrl, "OpenAI-kompatibel, z. B. https://api.openai.com/v1");
        rows.add("API-Key", apiKey, " ");
        rows.add("Modelle", loadModels, "Fragt die Modelle beim Dienst ab; danach Auswahl über ▾");
        rows.add("Text-Modell", Comic.row(chatModel, pickChat), "Für spätere Suchbegriffe aus einer Frage; optional");
        rows.add("Embedding-Modell", Comic.row(embeddingModel, pickEmbedding),
                "Für die semantische Suche, z. B. text-embedding-3-small");
        rows.add("Dimension", dimensions, "Leer: wird beim Speichern beim Dienst gemessen");
        pickChat.setEnabled(false);
        pickEmbedding.setEnabled(false);
        loadModels.addActionListener(e -> loadModels());
        pickChat.addActionListener(e -> pick(pickChat, chatModel, AiModel.CHAT));
        pickEmbedding.addActionListener(e -> pick(pickEmbedding, embeddingModel, AiModel.EMBEDDING));
        releasedPanel.setOpaque(false);
        if (wizard) {
            releaseThis.setOpaque(false);
            releaseThis.setFont(Comic.regular(13f));
            releaseThis.setForeground(Comic.INK);
            rows.add("Freigabe", releaseThis, "Weitere Quellen gibst du unter Einstellungen → KI frei; Vorgabe keine");
        } else {
            rows.add("Freigegebene Quellen", releasedPanel, "Nur Inhalte angehakter Wissensquellen gehen an den "
                    + "KI-Dienst; Vorgabe keine");
        }
        root.add(Comic.note(wizard
                ? "Optional: Für die spätere semantische Suche (experimentell) einen KI-Dienst mit Embedding-Modell "
                + "eintragen. Leer lassen und „Weiter“ überspringt diesen Schritt; nachholen unter Einstellungen → KI."
                : "Die semantische Suche (experimentell) braucht einen KI-Dienst mit Embedding-Modell. Der API-Key "
                + "liegt nur im Tresor dieses Windows-Kontos, nie in der Konfigurationsdatei."),
                BorderLayout.NORTH);
        rows.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        root.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        if (!wizard) {
            south.add(Comic.row(save, removeKey), BorderLayout.NORTH);
        }
        south.add(feedback, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        save.addActionListener(e -> save());
        removeKey.addActionListener(e -> removeKey());
        render();
    }

    JComponent component() {
        return root;
    }

    /** Übernimmt die Werte der Datei in die Felder; der API-Key erscheint nur als „gespeichert“ oder „fehlt“. */
    void render() {
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        baseUrl.setText(current.baseUrl() == null ? "" : current.baseUrl());
        chatModel.setText(current.chatModel() == null ? "" : current.chatModel());
        embeddingModel.setText(current.embeddingModel() == null ? "" : current.embeddingModel());
        dimensions.setText(current.embeddingDimensions() == null ? "" : current.embeddingDimensions());
        timeout = current.timeout();
        released.clear();
        releasedPanel.removeAll();
        List<String> ids = new ArrayList<>();
        Map<String, String> titles = new LinkedHashMap<>();
        if (settings != null) {
            settings.sources().forEach(source -> {
                ids.add(source.id());
                String title = source.config().title();
                titles.put(source.id(), title == null || title.isBlank() ? source.id() : title);
            });
        }
        // Freigaben für Quellen, die (noch) fehlen, gehen beim Speichern nicht verloren.
        current.externalProcessingSources().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        for (String id : ids) {
            JCheckBox box = new JCheckBox(titles.getOrDefault(id, id));
            box.setToolTipText("Quelle " + id);
            box.setOpaque(false);
            box.setFont(Comic.regular(13f));
            box.setForeground(Comic.INK);
            box.setSelected(current.externalProcessingSources().contains(id));
            released.put(id, box);
            releasedPanel.add(box);
        }
        if (ids.isEmpty()) {
            releasedPanel.add(Comic.muted("Noch keine Wissensquelle eingerichtet"));
        }
        releasedPanel.revalidate();
        releasedPanel.repaint();
        apiKey.setText("");
        boolean stored = context.aiKeyAvailable();
        rows.hint(apiKey, stored ? "Gespeichert; leer lassen, um ihn zu behalten"
                : context.aiVault().available() ? "Fehlt; wird verschlüsselt im Tresor abgelegt"
                : "Fehlt; ohne Windows-Tresor gilt er nur bis zum Schließen der App");
        removeKey.setVisible(stored);
    }

    AiDraft draft() {
        List<String> ids = released.entrySet().stream().filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey).toList();
        return new AiDraft(baseUrl.getText(), chatModel.getText(), embeddingModel.getText(), dimensions.getText(),
                timeout, ids);
    }

    /** Für den Assistenten: die Eingaben, mit der neuen Quelle unter den Freigaben, wenn angehakt. */
    AiDraft draftFor(String sourceId) {
        AiDraft draft = draft();
        if (!releaseThis.isSelected() || sourceId == null || sourceId.isBlank()) {
            return draft;
        }
        List<String> ids = new ArrayList<>(draft.externalProcessingSources());
        ids.add(sourceId);
        return new AiDraft(draft.baseUrl(), draft.chatModel(), draft.embeddingModel(), draft.embeddingDimensions(),
                draft.timeout(), ids);
    }

    /** Ob seit dem Öffnen etwas eingetragen wurde (Felder oder API-Key). */
    boolean changed() {
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        return !draft().equals(current) || apiKey.getPassword().length > 0 || releaseThis.isSelected();
    }

    private void save() {
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
        return new AiDraft(draft.baseUrl(), draft.chatModel(), draft.embeddingModel(), dimensions, draft.timeout(),
                draft.externalProcessingSources());
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
            feedback.warning("Basisadresse fehlt", "Erst die Adresse des KI-Dienstes eintragen.");
            return;
        }
        if (!draft.problems().isEmpty()) {
            feedback.warning("Bitte prüfen", String.join(" ", draft.problems()));
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Allgemein.");
            return;
        }
        char[] typed = apiKey.getPassword();
        loadModels.setEnabled(false);
        feedback.working("Modelle werden geladen …");
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
                feedback.warning("Modelle nicht geladen", reason(ai) + ".");
            } else {
                feedback.problem(error);
            }
        });
    }

    /** Übernimmt eine geladene Liste; für Tests auch direkt. */
    void showModels(List<AiModel> loaded) {
        models = List.copyOf(loaded);
        pickChat.setEnabled(!choices(AiModel.CHAT).isEmpty());
        pickEmbedding.setEnabled(!choices(AiModel.EMBEDDING).isEmpty());
        long embeddings = models.stream().filter(m -> m.categories().contains(AiModel.EMBEDDING)).count();
        if (models.isEmpty()) {
            feedback.warning("Keine Modelle", "Der Dienst meldet keine Modelle; Namen bitte von Hand eintragen.");
        } else {
            feedback.success(Texts.count(models.size(), "Modell", "Modelle") + " geladen"
                    + (embeddings == 0 ? "" : ", davon " + embeddings + " für Embeddings") + "; Auswahl über ▾");
        }
    }

    /** Modelle für ein Feld: die passende Kategorie zuerst, Modelle ohne Kategorie danach. */
    List<AiModel> choices(String category) {
        List<AiModel> fitting = new ArrayList<>();
        List<AiModel> unknown = new ArrayList<>();
        for (AiModel model : models) {
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
