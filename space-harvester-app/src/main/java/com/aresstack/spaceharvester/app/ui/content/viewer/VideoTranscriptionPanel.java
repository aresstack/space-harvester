package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.TranscriptionStatusListener;
import com.aresstack.spaceharvester.app.transcription.TranscriptSegment;
import com.aresstack.spaceharvester.app.transcription.WhisperSpeechTranscriber;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Display provisional Whisper recognition results separately from media playback.
 * Keep remote credentials outside the transcript view and never index draft text automatically.
 */
final class VideoTranscriptionPanel extends JPanel implements AutoCloseable {

    private static final Color BACKGROUND = new Color(0x171E27);
    private static final Color FOREGROUND = new Color(0xE5EDF6);

    private final TranscribeAudioUseCase transcription;
    private final JTextArea text = new JTextArea();
    private final JButton chooseModel = new JButton("Whisper-Modell wählen");
    private final JButton transcribe = new JButton("Transkribieren");
    private final JButton cancel = new JButton("Abbrechen");
    private final JLabel status = new JLabel("Whisper-Transkription ist noch nicht gestartet.");
    private Path modelDirectory = defaultModelDirectory();
    private URI audioSource;
    private SwingWorker<Void, TranscriptSegment> worker;
    private boolean closed;

    VideoTranscriptionPanel(TranscribeAudioUseCase transcription) {
        super(new BorderLayout(0, 8));
        this.transcription = transcription;
        setOpaque(true);
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 15, 10, 15));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        actions.add(chooseModel);
        actions.add(transcribe);
        actions.add(cancel);
        add(actions, BorderLayout.NORTH);

        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(new Color(0x253241));
        text.setForeground(FOREGROUND);
        text.setCaretColor(FOREGROUND);
        text.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        text.setText("Die Sprache wird lokal mit Whisper erkannt. "
                + "Die Zeitmarken kennzeichnen 30-Sekunden-Abschnitte, nicht einzelne Wörter.");
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        status.setForeground(FOREGROUND);
        add(status, BorderLayout.SOUTH);

        chooseModel.addActionListener(event -> selectModelDirectory());
        transcribe.addActionListener(event -> startTranscription());
        cancel.addActionListener(event -> cancelTranscription());
        cancel.setEnabled(false);
        updateAvailableActions();
    }

    void setAudioSource(URI audioSource) {
        if (worker != null) {
            cancelTranscription();
        }
        this.audioSource = audioSource;
        updateAvailableActions();
    }

    private void selectModelDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (modelDirectory != null) {
            chooser.setCurrentDirectory(modelDirectory.toFile());
        }
        chooser.setDialogTitle("Ordner mit dem Whisper-Tiny-Modell auswählen");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        modelDirectory = chooser.getSelectedFile().toPath();
        if (WhisperSpeechTranscriber.isModelAvailable(modelDirectory)) {
            status.setText("Whisper-Modell bereit: " + modelDirectory.getFileName());
        } else {
            status.setText("Modell fehlt: config.json, model.safetensors, tokenizer.json oder vocab.json");
        }
        updateAvailableActions();
    }

    private void startTranscription() {
        if (closed || worker != null || audioSource == null) {
            return;
        }
        if (!WhisperSpeechTranscriber.isModelAvailable(modelDirectory)) {
            selectModelDirectory();
            if (!WhisperSpeechTranscriber.isModelAvailable(modelDirectory)) {
                return;
            }
        }
        URI requestedSource = audioSource;
        Path requestedModel = modelDirectory;
        text.setText("");
        status.setText("Audio-only-MP4 wird lokal zu 16-kHz-PCM dekodiert …");
        updateAvailableActions();
        worker = new SwingWorker<Void, TranscriptSegment>() {
            @Override
            protected Void doInBackground() throws Exception {
                SwingWorker<Void, TranscriptSegment> active = this;
                transcription.execute(requestedSource, requestedModel, this::isCancelled,
                        segment -> publish(segment), new TranscriptionStatusListener() {
                            @Override
                            public void onDecodedSeconds(long seconds) {
                                SwingUtilities.invokeLater(() -> {
                                    if (!closed && worker == active) {
                                        status.setText(String.format(java.util.Locale.ROOT,
                                                "Audio dekodiert: %d:%02d Minuten", seconds / 60, seconds % 60));
                                    }
                                });
                            }

                            @Override
                            public void onRecognitionStarted() {
                                SwingUtilities.invokeLater(() -> {
                                    if (!closed && worker == active) {
                                        status.setText("Whisper transkribiert die Audiospur …");
                                    }
                                });
                            }
                        });
                return null;
            }

            @Override
            protected void process(List<TranscriptSegment> segments) {
                if (closed || isCancelled()) {
                    return;
                }
                for (TranscriptSegment segment : segments) {
                    text.append("[" + segment.timeLabel() + "] " + segment.text() + "\n\n");
                }
                status.setText("Whisper erkennt die gesprochenen Inhalte …");
            }

            @Override
            protected void done() {
                if (closed || worker != this) {
                    return;
                }
                worker = null;
                updateAvailableActions();
                try {
                    get();
                    status.setText("Transkription abgeschlossen. Der Text ist noch nicht indexiert.");
                    if (text.getText().isEmpty()) {
                        text.setText("(Keine Sprache erkannt)");
                    }
                } catch (CancellationException cancelled) {
                    status.setText("Transkription abgebrochen.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status.setText("Transkription unterbrochen.");
                } catch (ExecutionException failure) {
                    String reason = failure.getCause() == null ? "Unbekannter Fehler"
                            : failure.getCause().getMessage();
                    status.setText("Transkription fehlgeschlagen.");
                    text.append("\nFehler: " + reason);
                }
            }
        };
        updateAvailableActions();
        worker.execute();
    }

    private void cancelTranscription() {
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        status.setText("Transkription abgebrochen.");
        updateAvailableActions();
    }

    private void updateAvailableActions() {
        chooseModel.setEnabled(!closed && worker == null);
        transcribe.setEnabled(!closed && worker == null && audioSource != null
                && WhisperSpeechTranscriber.isModelAvailable(modelDirectory));
        cancel.setEnabled(!closed && worker != null);
    }

    private static Path defaultModelDirectory() {
        String configured = System.getProperty("spaceharvester.whisper.model");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        Path personal = Paths.get(System.getProperty("user.home", "."), ".space-harvester",
                "models", "whisper-tiny");
        if (WhisperSpeechTranscriber.isModelAvailable(personal)) {
            return personal;
        }
        return Paths.get("model", "whisper-tiny").toAbsolutePath();
    }

    @Override
    public void close() {
        closed = true;
        cancelTranscription();
        audioSource = null;
        updateAvailableActions();
    }
}
