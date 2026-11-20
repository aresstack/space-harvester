package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.TranscriptSegment;
import com.aresstack.spaceharvester.app.transcription.TranscriptionStatusListener;
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
    private final ComposerButton toggle = ComposerButton.iconButton(ComposerIcons.microphone(), START_TIP);
    private final JPanel action = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JTextArea transcript = new JTextArea();
    private final JLabel status = new JLabel("Transkription noch nicht gestartet.");
    private VideoTranscriptSource videoSource;
    private SwingWorker<Void, TranscriptSegment> worker;
    /** Was Whisper bisher erkannt hat, ohne Fehlermeldungen: so wird es gespeichert. */
    private final StringBuilder recognized = new StringBuilder();
    /** Bekommt das bisher erkannte Transkript nach jedem neuen Abschnitt und am Ende, um es zu speichern. */
    private java.util.function.Consumer<String> onTranscript = text -> { };
    /**
     * Wo eine gestoppte Transkription fortsetzt: am Ende des zuletzt erkannten Abschnitts, wie Whisper sein Fenster
     * auch während eines Laufs verschiebt; {@code 0} ohne Transkript oder nach einem abgeschlossenen Lauf.
     */
    private long resumeMillis;
    /** Statuszeile über einem gespeicherten Transkript. */
    public static final String STORED_STATUS = "Gespeichertes Transkript (auch unter Anhänge) · das Mikrofon "
            + "erzeugt es neu.";

    public VideoTranscriptionPanel(TranscribeAudioUseCase transcription) {
        super(new BorderLayout(0, 12));
        this.transcription = transcription;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        action.setOpaque(false);
        action.add(toggle);
        action.putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, Boolean.TRUE);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(transcript.getFont().deriveFont(15f));
        transcript.setText("Klicke unten neben der Lupe auf das Mikrofon („Transkript erzeugen“), "
                + "um die MP4-Audiospur lokal mit Whisper zu transkribieren.");
        add(new JScrollPane(transcript), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        toggle.addActionListener(event -> {
            if (worker == null) {
                startTranscription();
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
        resumeMillis = 0;
        recognized.setLength(0);
        transcript.setText("Klicke unten neben der Lupe auf das Mikrofon („Transkript erzeugen“), "
                + "um die MP4-Audiospur lokal mit Whisper zu transkribieren.");
        status.setText("Transkription noch nicht gestartet.");
        updateActions();
    }

    /** {@code listener} bekommt das bisher erkannte Transkript nach jedem neuen Abschnitt und am Ende (auf dem EDT). */
    public void onTranscript(java.util.function.Consumer<String> listener) {
        onTranscript = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Zeigt ein gespeichertes Transkript des gezeigten Videos, solange keines erzeugt wird. */
    public void showStored(VideoTranscriptSource source, String text) {
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
        // Ein unvollständiges Transkript wird fortgesetzt, nicht neu begonnen; nur ein abgeschlossenes neu erzeugt.
        long startMillis = resumeMillis;
        if (startMillis <= 0) {
            transcript.setText("");
            recognized.setLength(0);
        }
        status.setText("Whisper-Modell wird aus dem Anwendungs-JAR bereitgestellt …");
        worker = new SwingWorker<Void, TranscriptSegment>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isCancelled()) {
                    return null;
                }
                Path model = WhisperModelLocation.resolve();
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
                updateActions();
                // Jeder Abschnitt wird sofort gespeichert: nichts geht beim Seitenwechsel verloren.
                onTranscript.accept(recognized.toString());
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
        toggle.setIcon(running ? ComposerIcons.stop() : ComposerIcons.microphone());
        String tip = running ? CANCEL_TIP : resumeMillis > 0 ? RESUME_TIP + TranscriptSegment.clock(resumeMillis)
                : START_TIP;
        toggle.setToolTipText(tip);
        toggle.getAccessibleContext().setAccessibleName(tip);
        toggle.setEmphasized(running);
        toggle.setEnabled(running || videoSource != null);
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
