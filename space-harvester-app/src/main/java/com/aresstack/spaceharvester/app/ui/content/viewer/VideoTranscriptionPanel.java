package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.TranscriptSegment;
import com.aresstack.spaceharvester.app.transcription.TranscriptionStatusListener;
import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
    private final JButton start = new JButton("Transkript erzeugen");
    private final JButton cancel = new JButton("Abbrechen");
    private final JTextArea transcript = new JTextArea();
    private final JLabel status = new JLabel("Transkription noch nicht gestartet.");
    private VideoTranscriptSource videoSource;
    private SwingWorker<Void, TranscriptSegment> worker;

    public VideoTranscriptionPanel(TranscribeAudioUseCase transcription) {
        super(new BorderLayout(0, 12));
        this.transcription = transcription;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(start);
        actions.add(cancel);
        add(actions, BorderLayout.NORTH);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(transcript.getFont().deriveFont(15f));
        transcript.setText("Klicke auf „Transkript erzeugen“, um die MP4-Audiospur lokal "
                + "mit Whisper zu transkribieren.");
        add(new JScrollPane(transcript), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        start.addActionListener(event -> startTranscription());
        cancel.addActionListener(event -> cancelTranscription());
        updateActions();
    }

    public void setVideoSource(VideoTranscriptSource source) {
        cancelTranscription();
        if (videoSource != null) {
            videoSource.close();
        }
        videoSource = source;
        transcript.setText("Klicke auf „Transkript erzeugen“, um die MP4-Audiospur lokal "
                + "mit Whisper zu transkribieren.");
        status.setText("Transkription noch nicht gestartet.");
        updateActions();
    }

    private void startTranscription() {
        if (worker != null || videoSource == null) {
            return;
        }
        VideoTranscriptSource requestedSource = videoSource;
        transcript.setText("");
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
                java.net.URI audioOnly = requestedSource.openAudioOnly();
                if (isCancelled()) {
                    return null;
                }
                SwingWorker<Void, TranscriptSegment> active = this;
                transcription.execute(audioOnly, model, this::isCancelled, this::publish,
                        new TranscriptionStatusListener() {
                            @Override
                            public void onDecodedSeconds(long seconds) {
                                SwingUtilities.invokeLater(() -> {
                                    if (worker == active) {
                                        status.setText(String.format(java.util.Locale.ROOT,
                                                "Audiodekodierung: %d:%02d Minuten", seconds / 60, seconds % 60));
                                    }
                                });
                            }

                            @Override
                            public void onRecognitionStarted() {
                                SwingUtilities.invokeLater(() -> {
                                    if (worker == active) {
                                        status.setText("Whisper transkribiert die Audiospur …");
                                    }
                                });
                            }
                        });
                return null;
            }

            @Override
            protected void process(List<TranscriptSegment> segments) {
                if (worker != this || isCancelled()) {
                    return;
                }
                for (TranscriptSegment segment : segments) {
                    transcript.append("[" + segment.timeLabel() + "] " + segment.text() + "\n\n");
                }
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                updateActions();
                try {
                    get();
                    status.setText("Transkription abgeschlossen (noch nicht im Suchindex).");
                    if (transcript.getText().isEmpty()) {
                        transcript.setText("(Keine Sprache erkannt)");
                    }
                } catch (CancellationException cancelled) {
                    status.setText("Transkription abgebrochen.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status.setText("Transkription unterbrochen.");
                } catch (ExecutionException failure) {
                    status.setText("Transkription fehlgeschlagen.");
                    Throwable cause = failure.getCause();
                    transcript.append("\nFehler: " + (cause == null ? "Unbekannter Fehler" : cause.getMessage()));
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
            status.setText("Transkription abgebrochen.");
        }
        updateActions();
    }

    private void updateActions() {
        start.setEnabled(worker == null && videoSource != null);
        cancel.setEnabled(worker != null);
    }
}
