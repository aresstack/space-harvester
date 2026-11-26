package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.ui.content.DictationControl;
import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;
import com.aresstack.windirectml.inference.whisper.WhisperIncrementalTranscription;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handle chat dictation using the original Enterprise AI Client recording and callback workflow.
 * Replace only the speech recognition backend with the Whisper checkpoint chosen in the settings.
 *
 * <p>Zwei Arten, umschaltbar auch während der Aufnahme (Klick auf die Wellen, {@link #setLive}):
 * <ul>
 *   <li>„erst komplett“ (Vorgabe): Ein Hintergrund-Worker erkennt jedes fertige 30-Sekunden-Fenster und meldet es über
 *       {@link Result#partial}; nach dem Stopp wird nur der Rest erkannt.</li>
 *   <li>„live mitschreiben“: {@link LiveTranscript} erkennt in einem gleitenden Fenster unter 30 Sekunden; was zwei
 *       Erkennungen gleich lesen, steht fest, der Rest ist vorläufig ({@link Result#live}).</li>
 * </ul>
 * Ein Wechsel beginnt eine neue Phase dort, wo die alte Text übernommen hatte; nichts wird doppelt erkannt. Die
 * Aufnahme wartet nie auf den Decoder. Schlägt die Erkennung während der Aufnahme fehl, wird der Rest nach dem Stopp
 * vollständig erkannt.
 */
public final class Dictation implements DictationControl {

    private static final Logger LOG = Logger.getLogger(Dictation.class.getName());

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "space-harvester-dictation");
        thread.setDaemon(true);
        return thread;
    });
    private final WhisperDictationService service = new WhisperDictationService();
    private final String description = "Whisper + Ask AI DSP/VAD (lokal, CPU)";
    private JavaSoundRecorder recorder;
    private Result pending;
    /** Die laufende Phase der aktuellen Aufnahme; nur auf dem Worker-Thread. */
    private Phase phase;
    private ScheduledFuture<?> livePolling;
    /** „live mitschreiben“ an; vom EDT gesetzt, vom Worker gelesen. */
    private volatile boolean liveMode;
    private LiveDictationSettings.Tuning tuning = LiveDictationSettings.Tuning.DEFAULT;
    /** Die zuletzt fehlgeschlagene Aufnahme (für „Erneut“); weg nach Erfolg, Verwerfen oder neuer Aufnahme. */
    private RecordedSpeech failedSpeech;
    /** Jede Aufnahme und jedes Verwerfen zählt hoch; ein altes Ergebnis kommt nicht mehr an. */
    private volatile long generation;

    public String currentDescription() {
        return description;
    }

    @Override
    public boolean isAvailable() {
        return service != null;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void setLive(boolean live) {
        liveMode = live;
    }

    @Override
    public void start(Result result) {
        cancel();
        if (service == null) {
            result.failed(description);
            return;
        }
        try {
            recorder = JavaSoundRecorder.start();
            pending = result;
        } catch (LineUnavailableException | RuntimeException failed) {
            LOG.log(Level.WARNING, "Mikrofon nicht verfügbar", failed);
            result.failed("Mikrofon nicht verfügbar: " + failed.getMessage());
            return;
        }
        final JavaSoundRecorder active = recorder;
        final long expected = generation;
        liveMode = LiveDictationSettings.live();
        tuning = LiveDictationSettings.tuning();
        final LiveDictationSettings.Tuning current = tuning;
        livePolling = worker.scheduleWithFixedDelay(() -> decodeWhileRecording(active, result, expected, current),
                current.stepMillis(), current.stepMillis(), TimeUnit.MILLISECONDS);
    }

    /** Worker thread: switch phase if the mode changed, then decode what the current phase can. */
    private void decodeWhileRecording(JavaSoundRecorder active, Result result, long expected,
                                      LiveDictationSettings.Tuning current) {
        if (expected != generation) {
            return;
        }
        try {
            WhisperAudioSource source = active.liveAudio();
            boolean wantLive = liveMode;
            if (phase == null || phase.generation != expected) {
                phase = wantLive ? Phase.live(expected, 0, current) : complete(source, result, expected, 0);
            } else if (phase.failed) {
                return;
            } else if (phase.live != null && !wantLive) {
                String fixed = phase.live.committed();
                long from = phase.live.start();
                edt(result, expected, () -> result.liveDone(fixed));
                phase = complete(source, result, expected, from);
            } else if (phase.live == null && wantLive) {
                phase = Phase.live(expected, phase.offset + phase.transcription.committedSamples(), current);
            }
            if (phase.live != null) {
                LiveTranscript live = phase.live;
                LiveTranscript.Window window = live.next(source.sampleCount());
                if (window != null) {
                    live.accept(window, service.decode(source, window.start(), window.end()));
                    String committed = live.committed();
                    String provisional = live.provisional();
                    edt(result, expected, () -> result.live(committed, provisional));
                }
            } else {
                phase.transcription.decodeAvailable();
            }
        } catch (Exception | OutOfMemoryError failed) {
            if (expected != generation) {
                return; // verworfen: die Aufnahme ist geschlossen, das Lesen bricht absichtlich ab
            }
            LOG.log(Level.WARNING, "Spracheingabe: Erkennung während der Aufnahme fehlgeschlagen, "
                    + "der Rest wird nach dem Stopp vollständig erkannt", failed);
            if (phase != null && phase.generation == expected) {
                phase.failed = true;
            } else {
                phase = Phase.failed(expected);
            }
        }
    }

    private Phase complete(WhisperAudioSource source, Result result, long expected, long offset) throws IOException {
        WhisperAudioSource from = offset == 0 ? source : AudioWindow.from(source, offset);
        return Phase.complete(expected, offset, service.startLive(from, partial(result, expected)));
    }

    private void edt(Result result, long expected, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (expected == generation) {
                action.run();
            }
        });
    }

    private java.util.function.Consumer<String> partial(Result result, long expected) {
        return piece -> edt(result, expected, () -> result.partial(piece));
    }

    @Override
    public void finish() {
        final JavaSoundRecorder active = recorder;
        final Result result = pending;
        final WhisperDictationService current = service;
        recorder = null;
        pending = null;
        stopLivePolling();
        if (active == null) {
            return;
        }
        if (result == null) {
            active.close();
            return;
        }
        if (current == null) {
            active.close();
            result.failed(description);
            return;
        }
        try {
            transcribe(active.stop(), result, current, true);
        } catch (IOException failed) {
            active.close();
            LOG.log(Level.WARNING, "Mikrofonaufnahme fehlgeschlagen", failed);
            result.failed(failed.getMessage() == null ? "Mikrofonaufnahme fehlgeschlagen" : failed.getMessage());
        }
    }

    @Override
    public boolean canRetry() {
        return failedSpeech != null && service != null;
    }

    @Override
    public void retry(Result result) {
        RecordedSpeech speech = failedSpeech;
        failedSpeech = null;
        WhisperDictationService current = service;
        cancel();
        if (speech == null || current == null) {
            result.failed(current == null ? description : "Keine Aufnahme zum Wiederholen");
            return;
        }
        transcribe(speech, result, current, false);
    }

    @Override
    public int level() {
        JavaSoundRecorder active = recorder;
        return active == null ? 0 : DecibelLevelScale.percentFromPeak(active.consumeRecentPeak());
    }

    @Override
    public boolean clipping() {
        JavaSoundRecorder active = recorder;
        return active != null && active.clipped();
    }

    /**
     * Erkennt im Hintergrund; Ergebnis auf dem EDT, nur wenn inzwischen nichts verworfen oder neu begonnen wurde.
     * Nach einer Aufnahme mit Erkennung während der Aufnahme bleibt nur der Rest der laufenden Phase zu erkennen.
     */
    private void transcribe(final RecordedSpeech speech, final Result result, final WhisperDictationService current,
                            final boolean continueLive) {
        failedSpeech = null;
        final long expected = generation;
        worker.execute(() -> {
            String text = null;
            String problem = null;
            try {
                long started = System.currentTimeMillis();
                Phase session = phase;
                phase = null;
                boolean ours = continueLive && session != null && session.generation == expected;
                if (ours && !session.failed && session.live != null) {
                    LiveTranscript live = session.live;
                    text = live.finish(current.decode(speech, live.start(), speech.sampleCount()));
                    String done = text;
                    edt(result, expected, () -> result.liveDone(done));
                } else if (ours && !session.failed && session.transcription != null) {
                    text = current.finishLive(session.transcription, speech);
                } else {
                    long from = 0;
                    if (ours && session.live != null) {
                        String fixed = session.live.committed();
                        from = session.live.start();
                        edt(result, expected, () -> result.liveDone(fixed));
                    } else if (ours && session.transcription != null) {
                        from = session.offset; // was davor lag, steht schon im Entwurf
                    }
                    text = current.transcribe(speech, from, partial(result, expected));
                }
                LOG.info("Spracheingabe: " + speech.durationMillis() + " ms Audio, nach dem Stopp "
                        + (System.currentTimeMillis() - started) + " ms bis zum Ergebnis");
            } catch (Exception failed) {
                LOG.log(Level.WARNING, "Spracheingabe fehlgeschlagen", failed);
                problem = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            } catch (OutOfMemoryError memory) {
                LOG.log(Level.SEVERE, "Spracheingabe: zu wenig Speicher", memory);
                problem = "zu wenig Arbeitsspeicher für das Modell";
            }
            final String recognized = text;
            final String message = problem;
            SwingUtilities.invokeLater(() -> {
                if (expected != generation) {
                    speech.close();
                    return;
                }
                if (message != null) {
                    discardFailedSpeech();
                    failedSpeech = speech;
                    result.failed(message);
                } else {
                    speech.close();
                    result.recognized(recognized);
                }
            });
        });
    }

    private void stopLivePolling() {
        if (livePolling != null) {
            livePolling.cancel(false);
            livePolling = null;
        }
    }

    private void discardFailedSpeech() {
        if (failedSpeech != null) {
            failedSpeech.close();
            failedSpeech = null;
        }
    }

    @Override
    public void cancel() {
        generation++;
        stopLivePolling();
        discardFailedSpeech();
        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
        pending = null;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Beendet Aufnahme und Erkennung beim Herunterfahren; idempotent. */
    public void close() {
        cancel();
        worker.shutdownNow();
    }

    /**
     * Eine Phase der Aufnahme ab {@code offset}: entweder „erst komplett“ ({@code transcription}, 30-Sekunden-Fenster)
     * oder „live“ ({@code live}); nur auf dem Worker-Thread.
     */
    private static final class Phase {
        final long generation;
        final long offset;
        final WhisperIncrementalTranscription transcription;
        final LiveTranscript live;
        boolean failed;

        private Phase(long generation, long offset, WhisperIncrementalTranscription transcription,
                      LiveTranscript live) {
            this.generation = generation;
            this.offset = offset;
            this.transcription = transcription;
            this.live = live;
        }

        static Phase complete(long generation, long offset, WhisperIncrementalTranscription transcription) {
            return new Phase(generation, offset, transcription, null);
        }

        static Phase live(long generation, long offset, LiveDictationSettings.Tuning tuning) {
            return new Phase(generation, offset, null, new LiveTranscript(offset, tuning));
        }

        static Phase failed(long generation) {
            Phase phase = new Phase(generation, 0, null, null);
            phase.failed = true;
            return phase;
        }
    }
}
