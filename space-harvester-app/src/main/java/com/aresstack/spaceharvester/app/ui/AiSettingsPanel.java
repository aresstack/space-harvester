package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Einstellungen des KI-Dienstes (Reiter „KI“ unter Einstellungen, ADR 0011): Basisadresse eines OpenAI-kompatiblen
 * Dienstes, API-Key, Modelle für Text und Embeddings wie im Enterprise AI Client und die Wissensquellen, deren Inhalte
 * an den Dienst gehen dürfen (Vorgabe keine). Adresse und Modelle stehen unter
 * {@code space-harvester.ai} in der Datei; der API-Key nur im Tresor der App (unter Windows DPAPI, sonst für die
 * Sitzung), nie in der Datei, im Protokoll oder in der Oberfläche. Die semantische Suche wird erst damit möglich.
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
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    final ComposerButton save = Comic.primary("Speichern", "KI-Einstellungen übernehmen und neu starten");
    final ComposerButton removeKey = Comic.button("API-Key entfernen", "Gespeicherten API-Key löschen");
    final Feedback feedback = new Feedback();

    AiSettingsPanel(AppContext context) {
        this.context = context;
        root.setOpaque(false);
        Comic.style(apiKey);
        rows.add("Basisadresse", baseUrl, "OpenAI-kompatibel, z. B. https://api.openai.com/v1");
        rows.add("API-Key", apiKey, " ");
        rows.add("Text-Modell", chatModel, "Für spätere Suchbegriffe aus einer Frage; optional");
        rows.add("Embedding-Modell", embeddingModel, "Für die semantische Suche, z. B. text-embedding-3-small");
        rows.add("Dimension", dimensions, "Optional; leer übernimmt die Dimension aus der ersten Antwort");
        releasedPanel.setOpaque(false);
        rows.add("Freigegebene Quellen", releasedPanel, "Nur Inhalte angehakter Wissensquellen gehen an den "
                + "KI-Dienst; Vorgabe keine");
        root.add(Comic.note("Die semantische Suche (experimentell) braucht einen KI-Dienst mit Embedding-Modell. "
                + "Der API-Key liegt nur im Tresor dieses Windows-Kontos, nie in der Konfigurationsdatei."),
                BorderLayout.NORTH);
        rows.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        root.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save, removeKey), BorderLayout.NORTH);
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
        Background.run(() -> {
            store(context, draft, vault ? key : new char[0]);
            return Boolean.TRUE;
        }, ignored -> {
            if (!vault && key.length > 0) {
                context.sessionAiKey(key);
            }
            Arrays.fill(key, '\0');
            save.setEnabled(true);
            context.reload(() -> {
                render();
                feedback.success("KI-Einstellungen übernommen");
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
