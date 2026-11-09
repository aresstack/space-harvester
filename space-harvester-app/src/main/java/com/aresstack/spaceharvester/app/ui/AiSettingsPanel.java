package com.aresstack.spaceharvester.app.ui;

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
    /** „Zugangsdaten beziehen über“: KeePass (Vorgabe) oder Manuell, wie im Enterprise AI Client. */
    final JComboBox<String> access = Comic.combo();
    static final String KEEPASS = "KeePass";
    static final String MANUAL = "Manuell";
    final JLabel keePassStatus = Comic.text("Nicht gekoppelt");
    final ComposerButton pair = Comic.button("Koppeln", "Mit KeePass koppeln (KeePassRPC); KeePass zeigt dabei ein "
            + "Einmal-Passwort");
    final JPanel keePassRow = Comic.row(keePassStatus, pair);
    /** Editierbar: einen bestehenden Eintrag wählen oder einen neuen Titel eintippen. */
    final JComboBox<String> entry = Comic.combo();
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
    final javax.swing.JButton advanced = Comic.disclosure("▸ Erweitert",
            "Dimension, Abschnittsgröße und Überlappung für Embeddings");
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
        access.addItem(KEEPASS);
        access.addItem(MANUAL);
        entry.setEditable(true);
        rows.add("Zugangsdaten beziehen über", access, "KeePass liest Basisadresse und API-Key aus einem Eintrag; "
                + "Manuell legt den Key im Tresor dieses Windows-Kontos ab");
        rows.add("KeePass", keePassRow, "KeePass muss laufen, mit dem Plugin KeePassRPC");
        rows.add("Eintrag", entry, "Bestehenden Eintrag wählen oder einen neuen Namen eintippen; ein neuer Eintrag "
                + "entsteht beim Speichern mit Basisadresse und API-Key");
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
        rows.add("Basisadresse", baseUrl, "OpenAI-kompatibel, z. B. https://api.openai.com/v1");
        rows.add("API-Key", apiKey, " ");
        modelRows.add("Modelle", loadModels, "Fragt die Modelle beim Dienst ab und wählt die Vorzugsmodelle vor; andere über ▾");
        modelRows.add("Text-Modell", Comic.fieldWithButton(chatModel, pickChat),
                "Für spätere Suchbegriffe aus einer Frage; optional");
        modelRows.add("Embedding-Modell", Comic.fieldWithButton(embeddingModel, pickEmbedding),
                "Für die semantische Suche, z. B. text-embedding-3-small");
        modelRows.add("Vision-Modell", Comic.fieldWithButton(visionModel, pickVision),
                "Beschreibt die Bilder einer Seite (Leseansicht › Bilder); leer: keine Bildbeschreibung");
        {
            modelRows.add("Rerank-Modell", Comic.fieldWithButton(rerankModel, pickRerank),
                    "Cross-Encoder des Dienstes (/rerank); ordnet die Treffer aus dem Cache neu, leer: Reihenfolge "
                            + "bleibt");
            // Wie im Assistenten von AskAI: linksbündige Überschrift, die aufgeklappten Felder im selben Raster.
            modelRows.wide(advanced);
            modelRows.add("Dimension", dimensions, "Vektorlänge des Embedding-Modells; leer: wird beim Übernehmen "
                    + "beim Dienst gemessen");
            modelRows.add("Abschnittsgröße", chunkTokens, "Höchstens so viele Tokens je Abschnitt für Embeddings; "
                    + "leer: 350; wirkt nach Neustart");
            modelRows.add("Überlappung", chunkOverlap, "So viele Sätze wiederholen sich zwischen Abschnitten; leer: 1; "
                    + "wirkt nach Neustart");
            advanced.addActionListener(e -> advanced(!advancedOpen()));
            advanced(false);
        }
        pickChat.setEnabled(false);
        pickEmbedding.setEnabled(false);
        pickRerank.setEnabled(false);
        pickVision.setEnabled(false);
        loadModels.addActionListener(e -> loadModels());
        pickChat.addActionListener(e -> pick(pickChat, chatModel, AiModel.CHAT));
        pickEmbedding.addActionListener(e -> pick(pickEmbedding, embeddingModel, AiModel.EMBEDDING));
        pickRerank.addActionListener(e -> pick(pickRerank, rerankModel, null));
        pickVision.addActionListener(e -> pick(pickVision, visionModel, AiModel.VISION));
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
        baseUrl.setText(current.baseUrl() == null ? "" : current.baseUrl());
        chatModel.setText(current.chatModel() == null ? "" : current.chatModel());
        embeddingModel.setText(current.embeddingModel() == null ? "" : current.embeddingModel());
        dimensions.setText(current.embeddingDimensions() == null ? "" : current.embeddingDimensions());
        rerankModel.setText(current.rerankModel() == null ? "" : current.rerankModel());
        visionModel.setText(current.visionModel() == null ? "" : current.visionModel());
        chunkTokens.setText(current.chunkTokens() == null ? "" : current.chunkTokens());
        chunkOverlap.setText(current.chunkOverlapSentences() == null ? "" : current.chunkOverlapSentences());
        advanced(current.chunkTokens() != null || current.chunkOverlapSentences() != null);
        timeout = current.timeout();
        // Freigaben gehören zur Quelle; dieses Panel zeigt nur ihre Zahl und schreibt sie unverändert zurück.
        releasedIds = current.externalProcessingSources();
        released.setText(releasedText(settings, releasedIds));
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
        keePassStatus.setText(context.keePass().paired() ? "Gekoppelt" : "Nicht gekoppelt");
        clearKeePassKey();
        apiKey.setText("");
        // Setzt auch den Hinweis zum Key und liest bei KeePass den gewählten Eintrag.
        updateAccess();
        shownAccess = accessDraft();
        removeKey.setVisible(context.aiKeyAvailable() && !keePassChosen());
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

    private void updateAccess() {
        boolean keePass = keePassChosen();
        rows.visible(keePassRow, keePass);
        rows.visible(entry, keePass);
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
        keePassStatus.setText("Verbinde mit KeePass …");
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
            keePassStatus.setText("Gekoppelt · " + Texts.count(read.size(), "Eintrag", "Einträge") + " lesbar");
            entryChosen();
        }, error -> {
            pair.setEnabled(true);
            keePassStatus.setText("Nicht gekoppelt");
            feedback.warning("KeePass nicht erreichbar", keePassReason(error));
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
        // Die Freigaben aus der Datei, nicht vom letzten render(): Das Quellenformular ändert sie unabhängig davon.
        AppSettings settings = context.settings();
        List<String> released = settings == null ? releasedIds : settings.ai().externalProcessingSources();
        return new AiDraft(baseUrl.getText(), chatModel.getText(), embeddingModel.getText(), dimensions.getText(),
                timeout, released, rerankModel.getText(), chunkTokens.getText(), chunkOverlap.getText(),
                visionModel.getText());
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
        return !draft().equals(current) || typedKey().length > 0 || !accessDraft().equals(shownAccess);
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
        char[] key = typedKey();
        AppSettings settings = context.settings();
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        AiAccess chosen = accessDraft();
        if (draft.equals(current) && key.length == 0 && chosen.equals(shownAccess)) {
            if (!quiet) {
                feedback.info("Nichts geändert", null);
            }
            then.run();
            return;
        }
        if (chosen.mode() == AiAccess.Mode.KEEPASS && chosen.entry() == null) {
            Arrays.fill(key, '\0');
            feedback.warning("Eintrag fehlt", "Bitte einen KeePass-Eintrag wählen oder einen neuen Namen eintippen.");
            return;
        }
        boolean known = chosen.entry() != null && titles.contains(chosen.entry()) || chosen.equals(savedAccess());
        if (chosen.fromKeePass() && !known && key.length == 0) {
            feedback.warning("API-Key fehlt", "Für den neuen KeePass-Eintrag „" + chosen.entry() + "“ bitte den "
                    + "API-Key eingeben; er wird nur in KeePass gespeichert.");
            return;
        }
        if (chosen.fromKeePass() && known && Boolean.FALSE.equals(keePassKeyPresent)) {
            feedback.warning("API-Key fehlt", "Der KeePass-Eintrag „" + chosen.entry() + "“ hat kein Passwort. "
                    + "Bitte den API-Key dort als Passwort eintragen.");
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
            if (chosen.fromKeePass()) {
                // Neuer Eintrag: Basisadresse und Key gehen nach KeePass, nie in Tresor oder Datei.
                if (!known) {
                    context.keePass().create(chosen.entry(), draft.baseUrl(), key);
                }
                char[] resolved = currentKey(key, chosen, true);
                try {
                    Measured measured = measure(context, runtime, draft, resolved == null ? new char[0] : resolved,
                            false);
                    store(context, measured.draft(), new char[0], chosen);
                    return measured;
                } finally {
                    if (resolved != null) {
                        Arrays.fill(resolved, '\0');
                    }
                }
            }
            Measured measured = measure(context, runtime, draft, key, true);
            store(context, measured.draft(), vault ? key.clone() : new char[0], chosen);
            return measured;
        }, measured -> {
            if (!chosen.fromKeePass() && !vault && key.length > 0) {
                context.sessionAiKey(key);
            }
            boolean serviceChanged = key.length > 0 || !chosen.equals(shownAccess)
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
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        String text = Files.isRegularFile(context.configFile())
                ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
        ConfigFileEditor.Draft written = editor.withAi(context.configFile(), new ConfigFileEditor.Draft(text, null),
                draft);
        written = editor.withAiAccess(context.configFile(), written, access);
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
        return new AiServiceSettings(URI.create(draft.baseUrl().strip()), key == null || key.length == 0 ? null
                : new String(key), blankToNull(draft.chatModel()), blankToNull(draft.embeddingModel()),
                draft.dimensions(), draft.timeout(), null, Set.of());
    }

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
        Background.run(() -> {
            char[] key = currentKey(typed, chosenAccess, knownEntry);
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
        return String.valueOf(blankToNull(draft.baseUrl())) + "|" + access.mode() + "|" + access.entry();
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
        pickEmbedding.setEnabled(false);
        pickRerank.setEnabled(false);
        pickVision.setEnabled(false);
    }

    /** Übernimmt eine geladene Liste; für Tests auch direkt. */
    /** Erweitert auf- oder zuklappen; eigene Werte bleiben sichtbar, damit nichts verdeckt wirkt. */
    void advanced(boolean open) {
        modelRows.visible(dimensions, open);
        modelRows.visible(chunkTokens, open);
        modelRows.visible(chunkOverlap, open);
        advanced.setText((open ? "▾" : "▸") + " Erweitert");
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
        pickEmbedding.setEnabled(!choices(AiModel.EMBEDDING).isEmpty());
        pickRerank.setEnabled(!choices(null).isEmpty());
        pickVision.setEnabled(!choices(AiModel.VISION).isEmpty());
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
