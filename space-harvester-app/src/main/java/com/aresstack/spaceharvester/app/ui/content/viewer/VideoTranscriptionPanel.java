package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.TranscriptSegment;
import com.aresstack.spaceharvester.app.transcription.TranscriptionStatusListener;
import com.aresstack.spaceharvester.app.transcription.WhisperModel;
import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Offer explicit local transcription from the text tab for one selected MP4.
 * Never fetch the original video or automatically index the resulting text.
 */
public final class VideoTranscriptionPanel extends JPanel {

    private final TranscribeAudioUseCase transcription;
    /** Beschriftung (Tooltip) des Knopfs, solange keine Transkription läuft. */
    public static final String START_TIP = "Transkript erzeugen";
    /** Beschriftung des Knopfs über einem unvollständigen Transkript; dahinter die Stelle. */
    public static final String RESUME_TIP = "Transkript fortsetzen ab ";
    /** Ein Abschnitt im Text: {@code [00:01:13 – 00:01:40]} oder (ältere) {@code [00:01:13]}. */
    private static final java.util.regex.Pattern SECTION = java.util.regex.Pattern.compile(
            "^\\[(\\d{2}):(\\d{2}):(\\d{2})(?:" + java.util.regex.Pattern.quote(TranscriptSegment.RANGE_SEPARATOR)
                    + "(\\d{2}):(\\d{2}):(\\d{2}))?\\] ", java.util.regex.Pattern.MULTILINE);
    /** Statuszeile nach Abbrechen; das bisher erkannte Transkript bleibt stehen. */
    static final String CANCELLED_STATUS = "Transkription abgebrochen. Das bisher Erkannte bleibt stehen; das "
            + "Mikrofon setzt am Ende des letzten Abschnitts fort.";
    /** Beschriftung (Tooltip) desselben Knopfs während der Transkription. */
    public static final String CANCEL_TIP = "Transkription abbrechen";

    /**
     * Ein Symbolknopf für Start und Abbruch (Angelo, 19.11.2026): Mikrofon startet, während der Transkription wird er zu
     * Stopp. Er steht in der Werkzeugleiste unten neben der Lupe ({@link #action()}), nicht im Panel.
     */
    private final ComposerButton toggle = ComposerButton.iconButton(new TranscribeIcon(), START_TIP);
    /** Tooltip des Knopfs „Korrigieren“. */
    public static final String CORRECT_TIP = "Transkript korrigieren: mit den Folienbildern wie vor „In PPTX "
            + "umwandeln“, als Aufträge unter „Aufträge“";
    /** Tooltip des Knopfs „Löschen“. */
    public static final String DELETE_TIP = "Transkript samt allen Korrekturen löschen";
    /**
     * Neben dem Mikrofon (Angelo 20.11.2026): Korrigieren startet die Korrektursitzung mit allen Bildern, der
     * Papierkorb löscht Transkript und Korrekturen, der Kopierknopf kopiert die gezeigte Fassung.
     */
    private final com.aresstack.spaceharvester.app.ui.IconButton correct =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.PENCIL,
                    CORRECT_TIP);
    private final com.aresstack.spaceharvester.app.ui.IconButton delete =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.TRASH,
                    DELETE_TIP);
    private final com.aresstack.spaceharvester.app.ui.controls.CopyButton copy =
            new com.aresstack.spaceharvester.app.ui.controls.CopyButton(this::copyText);
    private Runnable onCorrect = () -> { };
    private Runnable onDelete = () -> { };
    /** Tooltip der Auswahl des Originals. */
    public static final String ORIGINAL_TIP = "Original-Transkript je Whisper-Modell (✓ = vorhanden); zu einem Modell "
            + "ohne Transkript erzeugt das Mikrofon eines";
    /**
     * Die Originale je Whisper-Modell (Angelo 20.11.2026): die Auswahl zeigt das Original eines Modells samt seinen
     * Korrekturen; zu einem Modell ohne Transkript erzeugt das Mikrofon eines mit diesem Modell.
     */
    private final javax.swing.JComboBox<Original> originals = com.aresstack.spaceharvester.app.ui.Comic.combo();
    /** Während die Auswahl von außen gefüllt wird, löst sie nichts aus. */
    private boolean fillingOriginals;
    private java.util.function.Consumer<String> onOriginal = whisper -> { };
    /**
     * Die Leiste mit der Lupe (Angelo, 20.11.2026): links der Knopf samt Fortschritt, mittig die Fassungen des
     * Transkripts zum Umschalten; alles senkrecht mittig zur Lupe.
     */
    private final JPanel action = new JPanel(new java.awt.BorderLayout(8, 0));
    /** Die Fassungen: „Original“ und je Modell eine Korrektur. */
    private final JPanel versionBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private java.util.List<Version> versions = java.util.List.of();
    private int shownVersion;
    private final JTextArea transcript = new JTextArea();
    private final JLabel status = new JLabel("Transkription noch nicht gestartet.");
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay storedLoading =
            new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(
                    com.aresstack.spaceharvester.app.ui.Comic.comicBars(new JScrollPane(transcript)));
    private VideoTranscriptSource videoSource;
    private ModelGate modelGate = (model, progress, then) -> then.run();

    /** Stellt vor einer Transkription das Whisper-Modell bereit, nötigenfalls nach Rückfrage von Hugging Face. */
    public interface ModelGate {
        /**
         * @param model    das in der Auswahl gewählte Modell
         * @param progress bekommt Fortschritt oder den Grund, warum nicht geladen wurde
         * @param then     läuft, sobald das Modell da ist
         */
        void open(WhisperModel model, java.util.function.Consumer<String> progress, Runnable then);
    }

    public void modelGate(ModelGate gate) {
        modelGate = java.util.Objects.requireNonNull(gate, "gate");
    }
    private SwingWorker<Void, TranscriptSegment> worker;
    /** Was Whisper bisher erkannt hat, ohne Fehlermeldungen: so wird es gespeichert. */
    private final StringBuilder recognized = new StringBuilder();
    /** Bekommt das bisher erkannte Transkript nach jedem neuen Abschnitt und am Ende, um es zu speichern. */
    private java.util.function.BiConsumer<String, String> onTranscript = (whisper, text) -> { };
    /**
     * Wo eine gestoppte Transkription fortsetzt: am Ende des zuletzt erkannten Abschnitts, wie Whisper sein Fenster
     * auch während eines Laufs verschiebt; {@code 0} ohne Transkript oder nach einem abgeschlossenen Lauf.
     */
    private long resumeMillis;
    /** Statuszeile über einem gespeicherten Transkript. */
    public static final String STORED_STATUS = "";
    /** Hinweis, solange es kein Transkript gibt. */
    static final String START_TEXT = "Klicke unten neben der Lupe auf „Transkript erzeugen“, um die MP4-Audiospur "
            + "lokal mit Whisper zu transkribieren.";

    /**
     * Eine Fassung des Transkripts zum Umschalten.
     *
     * @param label Name in der Leiste, etwa {@code Original} oder {@code Korrektur gemma4:12b}
     * @param text  ihr Text
     */
    public record Version(String label, String text) {
    }

    /**
     * Ein Eintrag der Auswahl der Originale.
     *
     * @param whisper Kennung des Whisper-Modells, etwa {@code tiny}; leer für ein älteres Transkript ohne Modell
     * @param stored  ob es zu diesem Modell schon ein Transkript gibt
     */
    public record Original(String whisper, boolean stored) {

        /** Der Name in der Auswahl, etwa {@code Whisper Tiny ✓}. */
        @Override
        public String toString() {
            WhisperModel model = WhisperModel.of(whisper);
            String name = whisper.isEmpty() ? "Älteres Transkript"
                    : "Whisper " + (model == null ? whisper : model.label());
            return stored ? name + " ✓" : name;
        }
    }

    public VideoTranscriptionPanel(TranscribeAudioUseCase transcription) {
        super(new BorderLayout());
        this.transcription = transcription;

        action.setOpaque(false);
        JPanel left = new JPanel(new java.awt.GridBagLayout());
        left.setOpaque(false);
        java.awt.GridBagConstraints at = new java.awt.GridBagConstraints();
        at.anchor = java.awt.GridBagConstraints.CENTER;
        originals.setToolTipText(ORIGINAL_TIP);
        originals.getAccessibleContext().setAccessibleName("Original-Transkript");
        left.add(originals, at);
        at.insets = new java.awt.Insets(0, 4, 0, 0);
        left.add(toggle, at);
        left.add(correct, at);
        left.add(delete, at);
        left.add(copy, at);
        at.insets = new java.awt.Insets(0, 8, 0, 0);
        status.setFont(status.getFont().deriveFont(12f));
        status.setForeground(com.aresstack.spaceharvester.app.ui.Comic.MUTED);
        left.add(status, at);
        action.add(left, BorderLayout.WEST);
        versionBar.setOpaque(false);
        JPanel middle = new JPanel(new java.awt.GridBagLayout());
        middle.setOpaque(false);
        middle.add(versionBar);
        action.add(middle, BorderLayout.CENTER);
        action.putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, Boolean.TRUE);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(transcript.getFont().deriveFont(15f));
        transcript.setText(START_TEXT);
        // Solange das gespeicherte Transkript lädt, dreht sich die Sanduhr der Suche darüber, ohne Esc.
        storedLoading.setHighlight(com.aresstack.spaceharvester.app.ui.Comic.BLUE);
        storedLoading.setVeilColor(com.aresstack.spaceharvester.app.ui.Comic.SURFACE);
        // Das Transkript füllt die ganze Fläche; Fortschritt und Fassungen stehen unten in der Leiste mit der Lupe.
        add(storedLoading, BorderLayout.CENTER);

        originals.addActionListener(event -> {
            if (!fillingOriginals) {
                originalChosen();
            }
        });
        showOriginals(null, java.util.List.of(), null);
        correct.addActionListener(event -> onCorrect.run());
        delete.addActionListener(event -> onDelete.run());
        toggle.addActionListener(event -> {
            if (worker == null) {
                VideoTranscriptSource wanted = videoSource;
                // Fehlt das eingestellte Modell, fragt die App erst und lädt es; danach beginnt die Transkription.
                modelGate.open(transcriptionModel(), status::setText, () -> {
                    if (wanted == videoSource) {
                        startTranscription();
                    }
                });
            } else {
                cancelTranscription();
            }
        });
        updateActions();
    }

    public void setVideoSource(VideoTranscriptSource source) {
        cancelTranscription();
        if (videoSource != null) {
            videoSource.close();
        }
        videoSource = source;
        following = false;
        storedLoading.setBusy(false);
        resumeMillis = 0;
        recognized.setLength(0);
        transcript.setText(START_TEXT);
        status.setText("");
        showVersions(source, java.util.List.of());
        showOriginals(source, java.util.List.of(), null);
        updateActions();
    }

    /**
     * Füllt die Auswahl der Originale: alle Whisper-Modelle, die mit Transkript markiert, davor ein älteres ohne
     * Modell, wenn es eines gibt. Gewählt ist {@code chosen}, ohne Transkript das eingestellte Modell.
     *
     * @param stored die Modelle mit Transkript ({@code ""}: ein älteres ohne Modell)
     * @param chosen das gewählte Original; {@code null} ohne Transkript
     */
    public void showOriginals(VideoTranscriptSource source, java.util.List<String> stored, String chosen) {
        if (source != videoSource) {
            return;
        }
        java.util.List<Original> items = new java.util.ArrayList<>();
        if (stored.contains("")) {
            items.add(new Original("", true));
        }
        for (WhisperModel model : WhisperModel.values()) {
            items.add(new Original(model.id(), stored.contains(model.id())));
        }
        for (String other : stored) {
            if (!other.isEmpty() && WhisperModel.of(other) == null) {
                items.add(new Original(other, true));
            }
        }
        String wanted = chosen != null ? chosen : WhisperModelLocation.selected().id();
        fillingOriginals = true;
        try {
            originals.setModel(new javax.swing.DefaultComboBoxModel<>(items.toArray(Original[]::new)));
            items.stream().filter(item -> item.whisper().equals(wanted)).findFirst()
                    .ifPresent(originals::setSelectedItem);
        } finally {
            fillingOriginals = false;
        }
        updateActions();
    }

    /** Das gewählte Original ({@code ""}: ein älteres ohne Modell). */
    public String whisper() {
        Original item = (Original) originals.getSelectedItem();
        return item == null ? WhisperModelLocation.selected().id() : item.whisper();
    }

    /** Das Modell, mit dem das Mikrofon transkribiert: das gewählte, bei einem älteren Transkript das eingestellte. */
    public WhisperModel transcriptionModel() {
        WhisperModel model = WhisperModel.of(whisper());
        return model == null ? WhisperModelLocation.selected() : model;
    }

    /** Was die Wahl eines Originals mit Transkript tut: es laden und wählen ({@link #showOriginals}). */
    public void onOriginal(java.util.function.Consumer<String> action) {
        onOriginal = java.util.Objects.requireNonNull(action, "action");
    }

    /** Für Tests: die Auswahl der Originale. */
    javax.swing.JComboBox<Original> originalChoice() {
        return originals;
    }

    private void originalChosen() {
        Original item = (Original) originals.getSelectedItem();
        if (item == null || worker != null || following) {
            return;
        }
        if (item.stored()) {
            // Ein anderes Original beginnt mit seinem Original, nicht mit einer gleichnamigen Korrektur. Bis es geladen
            // ist, gehört nichts mehr vom vorigen dazu: Mikrofon, Korrigieren und Löschen warten (Sanduhr).
            shownVersion = 0;
            recognized.setLength(0);
            resumeMillis = 0;
            storedLoading.setBusy(true);
            updateActions();
            onOriginal.accept(item.whisper());
            return;
        }
        // Noch kein Transkript zu diesem Modell: die Ansicht wie ohne Transkript, das Mikrofon erzeugt es.
        recognized.setLength(0);
        resumeMillis = 0;
        transcript.setText(START_TEXT);
        status.setText("Noch kein Transkript mit " + item + ".");
        showVersions(videoSource, java.util.List.of());
        updateActions();
    }

    /** Markiert das gewählte Original als vorhanden, sobald das Mikrofon den ersten Abschnitt gespeichert hat. */
    private void markStored(String whisper) {
        for (int i = 0; i < originals.getItemCount(); i++) {
            Original item = originals.getItemAt(i);
            if (item.whisper().equals(whisper) && !item.stored()) {
                fillingOriginals = true;
                try {
                    boolean selected = originals.getSelectedIndex() == i;
                    javax.swing.DefaultComboBoxModel<Original> model =
                            (javax.swing.DefaultComboBoxModel<Original>) originals.getModel();
                    model.removeElementAt(i);
                    model.insertElementAt(new Original(whisper, true), i);
                    if (selected) {
                        originals.setSelectedIndex(i);
                    }
                } finally {
                    fillingOriginals = false;
                }
                return;
            }
        }
    }

    /**
     * Die Fassungen des gezeigten Videos zum Umschalten unten in der Mitte: zuerst das Original, dann die Korrekturen;
     * mit weniger als zwei keine Leiste. Eine gezeigte Korrektur bleibt gewählt und zeigt ihren neuen Text; gibt es sie
     * nicht mehr, zeigt die Ansicht das Original.
     */
    public void showVersions(VideoTranscriptSource source, java.util.List<Version> shown) {
        if (source != videoSource) {
            return;
        }
        int before = shownVersion;
        String label = before > 0 && before < versions.size() ? versions.get(before).label() : null;
        versions = java.util.List.copyOf(shown);
        shownVersion = 0;
        for (int i = 1; label != null && i < versions.size(); i++) {
            if (versions.get(i).label().equals(label)) {
                shownVersion = i;
            }
        }
        if (before > 0 && worker == null && !versions.isEmpty()) {
            String text = versions.get(shownVersion).text();
            if (!text.equals(transcript.getText())) {
                int caret = transcript.getCaretPosition();
                transcript.setText(text);
                transcript.setCaretPosition(Math.min(caret, transcript.getDocument().getLength()));
            }
        }
        renderVersions();
    }

    /** Für Tests: die Knöpfe der Fassungen. */
    java.util.List<ComposerButton> versionButtons() {
        java.util.List<ComposerButton> buttons = new java.util.ArrayList<>();
        for (java.awt.Component component : versionBar.getComponents()) {
            buttons.add((ComposerButton) component);
        }
        return buttons;
    }

    /** Für Tests: wo die Schreibmarke im Transkript steht. */
    int transcriptCaret() {
        return transcript.getCaretPosition();
    }

    /** Für Tests: der gezeigte Text. */
    String shownText() {
        return transcript.getText();
    }

    private void renderVersions() {
        versionBar.removeAll();
        if (versions.size() > 1) {
            for (int i = 0; i < versions.size(); i++) {
                int index = i;
                ComposerButton button = com.aresstack.spaceharvester.app.ui.Comic.button(versions.get(i).label(),
                        "Diese Fassung des Transkripts zeigen");
                button.setEmphasized(i == shownVersion);
                button.setEnabled(worker == null);
                button.addActionListener(event -> selectVersion(index));
                versionBar.add(button);
            }
        }
        versionBar.revalidate();
        versionBar.repaint();
    }

    private void selectVersion(int index) {
        if (worker != null || index < 0 || index >= versions.size()) {
            return;
        }
        shownVersion = index;
        transcript.setText(versions.get(index).text());
        transcript.setCaretPosition(0);
        renderVersions();
    }

    /** Was „Korrigieren“ tut; der Knopf ist nur mit gespeichertem Transkript an. */
    public void onCorrect(Runnable action) {
        onCorrect = java.util.Objects.requireNonNull(action, "action");
    }

    /** Was der Papierkorb tut (fragt selbst nach); nur mit gespeichertem Transkript an. */
    public void onDelete(Runnable action) {
        onDelete = java.util.Objects.requireNonNull(action, "action");
    }

    /** Die gezeigte Fassung für den Kopierknopf; ohne Transkript nichts. */
    String copyText() {
        return recognized.length() == 0 ? null : transcript.getText();
    }

    /** Nach dem Löschen: die Ansicht wie ohne Transkript, das Mikrofon erzeugt es neu. */
    public void cleared(VideoTranscriptSource source) {
        if (source != videoSource || worker != null) {
            return;
        }
        recognized.setLength(0);
        resumeMillis = 0;
        transcript.setText(START_TEXT);
        status.setText("Transkript und Korrekturen gelöscht.");
        showVersions(source, java.util.List.of());
        updateActions();
    }

    /** Für Tests: das Mikrofon. */
    java.awt.Component microphone() {
        return toggle;
    }

    /** Für Tests: Korrigieren, Löschen, Kopieren. */
    java.util.List<javax.swing.JButton> transcriptActions() {
        return java.util.List.of(correct, delete, copy);
    }

    /**
     * {@code listener} bekommt das Whisper-Modell und das bisher erkannte Transkript nach jedem neuen Abschnitt und am
     * Ende (auf dem EDT).
     */
    public void onTranscript(java.util.function.BiConsumer<String, String> listener) {
        onTranscript = java.util.Objects.requireNonNull(listener, "listener");
    }

    /**
     * Zeigt die Sanduhr, solange das gespeicherte Transkript des gezeigten Videos lädt; {@link #showStored} oder
     * {@code false} nimmt sie wieder weg.
     */
    public void loadingStored(VideoTranscriptSource source, boolean loading) {
        if (source == videoSource) {
            storedLoading.setBusy(loading && worker == null);
            updateActions();
        }
    }

    /** Ob gerade die Sanduhr über dem Transkript steht (für Tests). */
    boolean storedLoadingShown() {
        return storedLoading.isBusy();
    }

    /** Zeigt ein gespeichertes Transkript des gezeigten Videos, solange keines erzeugt wird. */
    public void showStored(VideoTranscriptSource source, String text) {
        loadingStored(source, false);
        if (source != videoSource || worker != null || text == null || text.isBlank()) {
            return;
        }
        recognized.setLength(0);
        recognized.append(text);
        transcript.setText(text);
        transcript.setCaretPosition(0);
        resumeMillis = resumePoint(text);
        status.setText(STORED_STATUS);
        updateActions();
    }

    /** Statuszeile, während der Auftrag „Transkript ziehen“ das Transkript im Hintergrund verlängert. */
    static final String FOLLOWING_STATUS = "Wird im Auftrag „Transkript ziehen“ erzeugt (siehe „Aufträge“) …";

    /**
     * Zeigt das gespeicherte Transkript nach einer Änderung durch einen Auftrag im Hintergrund (Angelo 20.11.2026: die
     * offene Ansicht wächst mit). Verlängert es den gezeigten Text, wird nur das Neue angehängt; die Ansicht springt
     * nicht. Nichts, solange die Ansicht selbst transkribiert oder eine Korrektur zeigt.
     *
     * @param running ob der Auftrag noch läuft
     */
    public void followStored(VideoTranscriptSource source, String text, boolean running) {
        followStored(source, null, text, running);
    }

    /**
     * Wie {@link #followStored(VideoTranscriptSource, String, boolean)} für das Original {@code whisper}, in das der
     * Auftrag schreibt; zeigt die Auswahl ein anderes, schaltet sie darauf um.
     */
    public void followStored(VideoTranscriptSource source, String whisper, String text, boolean running) {
        if (source != videoSource) {
            return;
        }
        // Hat der Auftrag in ein Original geschrieben, gibt es das jetzt, auch wenn die Auswahl schon darauf steht.
        if (whisper != null && text != null && !text.isBlank()) {
            markStored(whisper);
        }
        if (whisper != null && worker == null && text != null && !text.isBlank() && !whisper.equals(whisper())) {
            fillingOriginals = true;
            try {
                for (int i = 0; i < originals.getItemCount(); i++) {
                    if (originals.getItemAt(i).whisper().equals(whisper)) {
                        originals.setSelectedIndex(i);
                    }
                }
            } finally {
                fillingOriginals = false;
            }
            recognized.setLength(0);
            showVersions(source, java.util.List.of());
        }
        following = running;
        if (worker != null || text == null || text.isBlank()) {
            updateActions();
            return;
        }
        loadingStored(source, false);
        // Zeigt die Ansicht eine Korrektur, bleibt sie stehen; das Original wächst trotzdem mit, damit es beim
        // Zurückschalten vollständig ist.
        boolean showing = shownVersion == 0;
        String shown = recognized.toString();
        if (shown.isEmpty() && showing) {
            showStored(source, text);
        } else if (!text.equals(shown)) {
            if (text.startsWith(shown)) {
                String added = text.substring(shown.length());
                recognized.append(added);
                if (showing) {
                    transcript.append(added);
                }
            } else {
                recognized.setLength(0);
                recognized.append(text);
                if (showing) {
                    int caret = transcript.getCaretPosition();
                    transcript.setText(text);
                    transcript.setCaretPosition(Math.min(caret, transcript.getDocument().getLength()));
                }
            }
            resumeMillis = com.aresstack.spaceharvester.application.presentation.TranscriptSections
                    .reach(recognized.toString());
        }
        if (!versions.isEmpty()) {
            java.util.List<Version> updated = new java.util.ArrayList<>(versions);
            updated.set(0, new Version(versions.get(0).label(), recognized.toString()));
            versions = java.util.List.copyOf(updated);
        }
        status.setText(running ? FOLLOWING_STATUS : STORED_STATUS);
        updateActions();
    }

    /** Ob ein Auftrag im Hintergrund das Transkript gerade verlängert. */
    private boolean following;

    /**
     * Wo ein Transkript fortgesetzt wird: am Ende seines letzten Abschnitts. Kennt der Text nur Anfänge (ältere
     * Transkripte), wird der letzte Abschnitt verworfen und von seinem Anfang an neu erkannt.
     */
    long resumePoint(String text) {
        java.util.regex.Matcher section = SECTION.matcher(text);
        long point = 0;
        int lastStart = -1;
        boolean ended = false;
        while (section.find()) {
            lastStart = section.start();
            ended = section.group(4) != null;
            point = ended ? millis(section, 4) : millis(section, 1);
        }
        if (lastStart >= 0 && !ended) {
            recognized.setLength(lastStart);
            transcript.setText(recognized.toString());
        }
        return point;
    }

    private static long millis(java.util.regex.Matcher section, int first) {
        return (Long.parseLong(section.group(first)) * 3600 + Long.parseLong(section.group(first + 1)) * 60
                + Long.parseLong(section.group(first + 2))) * 1000;
    }

    private void startTranscription() {
        if (worker != null || videoSource == null) {
            return;
        }
        VideoTranscriptSource requestedSource = videoSource;
        String requestedWhisper = whisper().isEmpty() ? "" : transcriptionModel().id();
        WhisperModel requestedModel = transcriptionModel();
        storedLoading.setBusy(false);
        if (shownVersion != 0 && !versions.isEmpty()) {
            // Whisper schreibt am Original weiter, nicht an einer Korrektur.
            shownVersion = 0;
            transcript.setText(versions.get(0).text());
        }
        // Ein unvollständiges Transkript wird fortgesetzt, nicht neu begonnen; nur ein abgeschlossenes neu erzeugt.
        long startMillis = resumeMillis;
        if (startMillis <= 0) {
            transcript.setText("");
            recognized.setLength(0);
        }
        status.setText("Whisper wird vorbereitet …");
        worker = new SwingWorker<Void, TranscriptSegment>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isCancelled()) {
                    return null;
                }
                Path model = WhisperModelLocation.find(requestedModel).orElseThrow(() ->
                        new com.aresstack.spaceharvester.app.transcription.WhisperModelMissingException(
                                requestedModel));
                if (isCancelled()) {
                    return null;
                }
                SwingUtilities.invokeLater(() -> {
                    if (worker == this) {
                        status.setText("MP4-Audiospur wird ohne Videodaten vorbereitet …");
                    }
                });
                VideoTranscriptSource.AudioOnly audioOnly = requestedSource.openAudioOnly(startMillis);
                if (isCancelled()) {
                    return null;
                }
                SwingWorker<Void, TranscriptSegment> active = this;
                transcription.execute(audioOnly.address(), audioOnly.startMillis(), startMillis, model,
                        this::isCancelled, this::publish,
                        new TranscriptionStatusListener() {
                            @Override
                            public void onDecodedSeconds(long seconds) {
                                SwingUtilities.invokeLater(() -> {
                                    if (worker == active) {
                                        status.setText(String.format(java.util.Locale.ROOT,
                                                "Audio geladen: %d:%02d Minuten · Whisper schreibt mit …",
                                                seconds / 60, seconds % 60));
                                    }
                                });
                            }

                            @Override
                            public void onRecognitionStarted() {
                                SwingUtilities.invokeLater(() -> {
                                    if (worker == active) {
                                        status.setText("Audiospur wird geladen · Whisper schreibt jeden "
                                                + "30-Sekunden-Abschnitt sofort mit …");
                                    }
                                });
                            }
                        });
                return null;
            }

            @Override
            protected void process(List<TranscriptSegment> segments) {
                // Auch nach Abbrechen: was Whisper schon erkannt hat, bleibt stehen (nur nicht nach Videowechsel).
                if (videoSource != requestedSource || (worker != null && worker != this)) {
                    return;
                }
                for (TranscriptSegment segment : segments) {
                    String line = "[" + segment.rangeLabel() + "] " + segment.text() + "\n\n";
                    transcript.append(line);
                    recognized.append(line);
                    resumeMillis = Math.max(resumeMillis, segment.endMillis());
                }
                markStored(requestedWhisper);
                updateActions();
                // Jeder Abschnitt wird sofort gespeichert: nichts geht beim Seitenwechsel verloren.
                onTranscript.accept(requestedWhisper, recognized.toString());
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                try {
                    get();
                    // Bis zum Ende erkannt: das Mikrofon erzeugt es beim nächsten Mal neu.
                    resumeMillis = 0;
                    status.setText("Transkription abgeschlossen und unter Anhänge gespeichert (noch nicht im "
                            + "Suchindex).");
                    if (transcript.getText().isEmpty()) {
                        transcript.setText("(Keine Sprache erkannt)");
                    }
                } catch (CancellationException cancelled) {
                    status.setText(CANCELLED_STATUS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status.setText("Transkription unterbrochen.");
                } catch (ExecutionException failure) {
                    status.setText("Transkription fehlgeschlagen.");
                    Throwable cause = failure.getCause();
                    transcript.append("\nFehler: " + (cause == null ? "Unbekannter Fehler" : cause.getMessage()));
                } finally {
                    updateActions();
                }
            }
        };
        updateActions();
        worker.execute();
    }

    private void cancelTranscription() {
        if (worker != null) {
            worker.cancel(true);
            worker = null;
            status.setText(CANCELLED_STATUS);
        }
        updateActions();
    }

    private void updateActions() {
        boolean running = worker != null;
        toggle.setIcon(running ? ComposerIcons.stop() : new TranscribeIcon());
        String tip = running ? CANCEL_TIP : resumeMillis > 0 ? RESUME_TIP + TranscriptSegment.clock(resumeMillis)
                : START_TIP;
        toggle.setToolTipText(tip);
        toggle.getAccessibleContext().setAccessibleName(tip);
        toggle.setEmphasized(running);
        // Schreibt schon der Auftrag „Transkript ziehen“, startet das Mikrofon keinen zweiten Lauf daneben; lädt das
        // gewählte Original noch, setzt es nichts vom vorigen fort.
        boolean loading = storedLoading.isBusy();
        toggle.setEnabled(running || videoSource != null && !following && !loading);
        boolean stored = videoSource != null && recognized.length() > 0 && !running && !following && !loading;
        correct.setEnabled(stored);
        delete.setEnabled(stored);
        copy.setEnabled(recognized.length() > 0);
        originals.setEnabled(videoSource != null && !running && !following && !loading);
        // Während Whisper schreibt, zeigt die Ansicht das Original, das gerade entsteht.
        renderVersions();
    }

    /** Der Knopf für die Werkzeugleiste unten: links neben der Lupe, wechselt zwischen Start und Abbruch. */
    public JComponent action() {
        return action;
    }

    /** Der Start-/Abbruch-Knopf (für Tests). */
    ComposerButton toggle() {
        return toggle;
    }
}
