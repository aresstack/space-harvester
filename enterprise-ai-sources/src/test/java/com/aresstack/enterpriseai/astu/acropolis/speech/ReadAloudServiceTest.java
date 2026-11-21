package com.aresstack.enterpriseai.astu.acropolis.speech;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisException;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.enterpriseai.speech.api.SynthesizedSpeech;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ein Vorlesevorgang spricht jedes Stück mit dem einen Modell, das beim Start gewählt war, auch mit mehreren Workern. */
public class ReadAloudServiceTest {

    private static final String ORPHEUS = "orpheus-tts-3b-de";
    private static final String FIVE_PARAGRAPHS = "Erster Absatz mit etwas Text.\n\n"
            + "Zweiter Absatz, etwas länger als der erste.\n\n"
            + "Dritter Absatz.\n\n"
            + "Vierter Absatz mit **Fettdruck** im Markdown.\n\n"
            + "Fünfter und letzter Absatz.";

    @Test
    public void everyParagraphOfOneAnswerUsesTheModelAndVoiceSelectedAtStart() {
        RecordingPort port = new RecordingPort(20);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Thomas", playback,
                SpeechChunking.PARAGRAPHS, 4, null);
        final List<ReadAloudService.ChunkTrace> requested = new CopyOnWriteArrayList<ReadAloudService.ChunkTrace>();
        final List<ReadAloudService.ChunkTrace> synthesized = new CopyOnWriteArrayList<ReadAloudService.ChunkTrace>();

        assertTrue(service.speak(FIVE_PARAGRAPHS, new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                throw new AssertionError(message);
            }

            @Override
            public void chunkRequested(ReadAloudService.ChunkTrace trace) {
                requested.add(trace);
            }

            @Override
            public void chunkSynthesized(ReadAloudService.ChunkTrace trace, ReadAloudService.WavFormat wav) {
                synthesized.add(trace);
            }
        }));

        assertEquals(5, port.models.size());
        assertEquals(Collections.singleton(ORPHEUS), new HashSet<String>(port.models));
        assertEquals(Collections.singleton("Thomas"), new HashSet<String>(port.voices));
        assertTrue("mehrere Worker parallel: " + port.workers, port.workers.size() > 1);
        assertEquals(5, playback.played.size());
        for (String played : playback.played) {
            assertTrue(played, played.startsWith(ORPHEUS + ":"));
        }
        assertEquals(5, requested.size());
        assertEquals(5, synthesized.size());
        Set<Integer> indices = new HashSet<Integer>();
        Set<Long> generations = new HashSet<Long>();
        for (ReadAloudService.ChunkTrace trace : requested) {
            assertEquals(ORPHEUS, trace.modelId());
            assertEquals("Thomas", trace.voice());
            assertEquals(5, trace.count());
            assertTrue(trace.worker(), trace.worker().startsWith("enterprise-ai-tts-worker-" + trace.generation()));
            indices.add(trace.index());
            generations.add(trace.generation());
        }
        assertEquals(new HashSet<Integer>(java.util.Arrays.asList(1, 2, 3, 4, 5)), indices);
        assertEquals(1, generations.size());
    }

    @Test
    public void stoppedRunPlaysNothingFurtherAndRequestsNothingNew() throws Exception {
        final CountDownLatch firstPlayed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        BlockingPort port = new BlockingPort(release);
        RecordingPlayback playback = new RecordingPlayback() {
            @Override
            public void play(byte[] wav) {
                super.play(wav);
                firstPlayed.countDown();
            }
        };
        final ReadAloudService service = new ReadAloudService(port, ORPHEUS, playback, SpeechChunking.PARAGRAPHS, 1);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                service.speak(FIVE_PARAGRAPHS, null);
            }
        });
        reader.start();
        assertTrue(firstPlayed.await(5, TimeUnit.SECONDS));
        service.stop();
        release.countDown();
        reader.join(5000);

        assertEquals(1, playback.played.size());
        assertEquals(1, playback.stops);
        assertTrue("höchstens das laufende Stück nach dem ersten: " + port.calls, port.calls.size() <= 2);
    }

    @Test
    public void eachMarkdownSectionIsOneChunkInReadingOrder() {
        RecordingPort port = new RecordingPort(0);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Jana", playback,
                SpeechChunking.PARAGRAPHS, 3, null);

        assertTrue(service.speak(SpeechTextTest.NUMBERED_SECTIONS, failOnMessage()));

        assertEquals(playback.played.toString(), 4, playback.played.size());
        assertEquals(ORPHEUS + ":Einleitung.", playback.played.get(0));
        assertTrue(playback.played.get(1), playback.played.get(1).startsWith(ORPHEUS + ":1. Fragen beantworten"));
        assertTrue(playback.played.get(2), playback.played.get(2).startsWith(ORPHEUS + ":2. Dokumente"));
        assertTrue(playback.played.get(3), playback.played.get(3).startsWith(ORPHEUS + ":3. Wissensdatenbank"));
    }

    @Test
    public void aFailedChunkIsRequestedOnceMore() {
        FlakyPort port = new FlakyPort("Dritter Absatz.", 1);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Jana", playback,
                SpeechChunking.PARAGRAPHS, 2, null);

        assertTrue(service.speak(FIVE_PARAGRAPHS, failOnMessage()));

        assertEquals(1, port.failuresOf("Dritter Absatz."));
        assertEquals(5, playback.played.size());
        assertEquals(ORPHEUS + ":Dritter Absatz.", playback.played.get(2));
    }

    @Test
    public void aChunkThatKeepsFailingStopsReadingAloudInsteadOfSkippingIt() {
        FlakyPort port = new FlakyPort("Dritter Absatz.", Integer.MAX_VALUE);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Jana", playback,
                SpeechChunking.PARAGRAPHS, 1, 2, null);
        final List<String> messages = new CopyOnWriteArrayList<String>();

        service.speak(FIVE_PARAGRAPHS, new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                messages.add(message);
            }
        });

        assertEquals("first two chunks only, never the fourth after a missing third", 2, playback.played.size());
        assertEquals(2, port.failuresOf("Dritter Absatz."));
        assertEquals(messages.toString(), 1, messages.size());
        assertTrue(messages.get(0), messages.get(0).startsWith("Vorlesen abgebrochen: Abschnitt 3 von 5"));
        assertTrue(messages.get(0), messages.get(0).contains("Dienst nicht erreichbar"));
    }

    private static ReadAloudService.Listener failOnMessage() {
        return new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                throw new AssertionError(message);
            }
        };
    }

    @Test
    public void wavFormatReadsTheRiffHeader() {
        byte[] wav = wav(24000, 1, 16);
        ReadAloudService.WavFormat format = ReadAloudService.WavFormat.of(wav);
        assertEquals(24000, format.sampleRate());
        assertEquals(1, format.channels());
        assertEquals(16, format.bitsPerSample());
        assertEquals(wav.length, format.bytes());
        assertEquals(0, ReadAloudService.WavFormat.of("kein wav".getBytes(StandardCharsets.UTF_8)).sampleRate());
    }

    static byte[] wav(int sampleRate, int channels, int bits) {
        byte[] data = new byte[48];
        put(data, 0, "RIFF");
        le(data, 4, 40, 4);
        put(data, 8, "WAVE");
        put(data, 12, "fmt ");
        le(data, 16, 16, 4);
        le(data, 20, 1, 2);
        le(data, 22, channels, 2);
        le(data, 24, sampleRate, 4);
        le(data, 28, sampleRate * channels * bits / 8, 4);
        le(data, 32, channels * bits / 8, 2);
        le(data, 34, bits, 2);
        put(data, 36, "data");
        le(data, 40, 4, 4);
        return data;
    }

    private static void put(byte[] data, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            data[offset + i] = (byte) ascii.charAt(i);
        }
    }

    private static void le(byte[] data, int offset, int value, int length) {
        for (int i = 0; i < length; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    @Test
    public void theGivenPreparationDecidesWhatIsSpokenAndNoInvisibleCharacterReachesThePort() {
        RecordingPort port = new RecordingPort(0);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Jana", playback, SpeechChunking.PARAGRAPHS,
                2, ReadAloudService.DEFAULT_SYNTHESIS_ATTEMPTS, null, new SpeechTextPreparation() {
                    @Override
                    public String spokenText(String markdown) {
                        return "Wie angezeigt\u200B: Zeile\u00A0eins.\n\nZweiter\u00AD Absatz \u2192 Ende.";
                    }
                });

        assertTrue(service.speak("| roh | Markdown |", null));

        assertEquals(java.util.Arrays.asList(ORPHEUS + ":Wie angezeigt: Zeile eins.",
                ORPHEUS + ":Zweiter Absatz Ende."), playback.played);
        assertEquals(Collections.singleton("Jana"), new HashSet<String>(port.voices));
    }

    @Test
    public void voiceStabilityFixKeepsLongAnswerRequestsAroundFiveHundredCharacters() {
        String sentence = "Dieser Satz beschreibt einen längeren technischen Abschnitt und endet an einer "
                + "eindeutigen Satzgrenze für die Sprachausgabe.";
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (answer.length() > 0) {
                answer.append(' ');
            }
            answer.append(sentence);
        }
        RecordingPort port = new RecordingPort(0);
        RecordingPlayback playback = new RecordingPlayback();
        ReadAloudService service = new ReadAloudService(port, ORPHEUS, "Jana", playback, SpeechChunking.ANSWER,
                1, ReadAloudService.DEFAULT_SYNTHESIS_ATTEMPTS, null, null, true);
        final List<ReadAloudService.ChunkTrace> requested =
                new CopyOnWriteArrayList<ReadAloudService.ChunkTrace>();

        assertTrue(service.speak(answer.toString(), new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                throw new AssertionError(message);
            }

            @Override
            public void chunkRequested(ReadAloudService.ChunkTrace trace) {
                requested.add(trace);
            }
        }));

        assertTrue(requested.toString(), requested.size() > 1);
        for (ReadAloudService.ChunkTrace trace : requested) {
            assertTrue(trace.toString(), trace.characters() < SpeechText.VOICE_STABILITY_TARGET_CHARS
                    + SpeechText.MAX_CHUNK_CHARS + 2);
        }
    }

    /** Zeichnet Modell und Worker jedes Aufrufs auf; das „Audio“ nennt Modell und Text. */
    static final class RecordingPort implements SpeechSynthesisPort {

        final List<String> models = new CopyOnWriteArrayList<String>();
        final List<String> voices = new CopyOnWriteArrayList<String>();
        final Set<String> workers = Collections.synchronizedSet(new HashSet<String>());
        private final long delayMillis;

        RecordingPort(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public String catalogId() {
            return "kipitz";
        }

        @Override
        public SynthesizedSpeech synthesize(String modelId, String voice, String text)
                throws SpeechSynthesisException {
            voices.add(String.valueOf(voice));
            models.add(modelId);
            workers.add(Thread.currentThread().getName());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SynthesizedSpeech((modelId + ":" + text).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Fails the given chunk {@code failures} times, then answers like {@link RecordingPort}. */
    static final class FlakyPort implements SpeechSynthesisPort {

        private final String failing;
        private final int failures;
        private final List<String> failed = new CopyOnWriteArrayList<String>();

        FlakyPort(String failing, int failures) {
            this.failing = failing;
            this.failures = failures;
        }

        int failuresOf(String text) {
            return Collections.frequency(failed, text);
        }

        @Override
        public String catalogId() {
            return "kipitz";
        }

        @Override
        public SynthesizedSpeech synthesize(String modelId, String voice, String text)
                throws SpeechSynthesisException {
            if (text.equals(failing) && failuresOf(text) < failures) {
                failed.add(text);
                throw new SpeechSynthesisException("Dienst nicht erreichbar");
            }
            return new SynthesizedSpeech((modelId + ":" + text).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Das erste Stück kommt sofort, alle weiteren erst nach {@code release}. */
    static final class BlockingPort implements SpeechSynthesisPort {

        final List<String> calls = new CopyOnWriteArrayList<String>();
        private final CountDownLatch release;

        BlockingPort(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public String catalogId() {
            return "kipitz";
        }

        @Override
        public SynthesizedSpeech synthesize(String modelId, String voice, String text) {
            calls.add(modelId);
            if (calls.size() > 1) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new SynthesizedSpeech((modelId + ":" + text).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void pauseHoldsThePlaybackWhereItIsAndResumeContinuesWithoutStartingOver() throws Exception {
        final PausablePlayback playback = new PausablePlayback();
        final ReadAloudService service = new ReadAloudService(new RecordingPort(0), ORPHEUS, playback);
        service.setRate(1.5);
        final boolean[] spoke = new boolean[1];
        Thread reader = new Thread(() -> spoke[0] = service.speak(FIVE_PARAGRAPHS, null));
        reader.start();
        assertTrue(playback.firstPlayed.await(5, TimeUnit.SECONDS));
        service.pause();
        assertTrue(service.isPaused());
        playback.goOn.countDown();
        Thread.sleep(200);
        assertEquals("pausiert: kein weiteres Stück", 1, playback.played.size());

        service.resume();
        reader.join(5000);
        assertTrue(spoke[0]);
        assertFalse(service.isPaused());
        assertEquals("nach dem Fortsetzen der Rest, nicht von vorn", 5, playback.played.size());
        assertTrue(playback.played.get(0), playback.played.get(0).contains("Erster Absatz"));
        assertTrue(playback.played.get(1), playback.played.get(1).contains("Zweiter Absatz"));
        assertEquals("Pause ist kein Stopp", 0, playback.stops);
        assertEquals(Double.valueOf(1.5), playback.rates.get(playback.rates.size() - 1));
    }

    @Test
    public void stopOrANewTextDiscardsAPauseAndTheRateIsClamped() {
        PausablePlayback playback = new PausablePlayback();
        playback.goOn.countDown();
        ReadAloudService service = new ReadAloudService(new RecordingPort(0), ORPHEUS, playback);
        service.pause();
        service.stop();
        assertFalse(service.isPaused());
        service.pause();
        assertTrue("ein neuer Text beginnt nicht pausiert", service.speak("Neuer Text.", null));
        assertFalse(service.isPaused());
        assertEquals(1, playback.played.size());

        service.setRate(9);
        assertEquals(SpeechRate.MAX, service.rate(), 0.0);
        service.setRate(0.1);
        assertEquals(SpeechRate.MIN, service.rate(), 0.0);
        service.setRate(Double.NaN);
        assertEquals(SpeechRate.NORMAL, service.rate(), 0.0);
    }

    /** Blocks {@link #play} while paused, like a stopped audio line; the first chunk waits for {@code goOn}. */
    static final class PausablePlayback extends RecordingPlayback {
        final CountDownLatch firstPlayed = new CountDownLatch(1);
        final CountDownLatch goOn = new CountDownLatch(1);
        final List<Double> rates = new CopyOnWriteArrayList<Double>();
        private boolean paused;

        @Override
        public synchronized void begin() {
            paused = false;
        }

        @Override
        public void play(byte[] wav) {
            synchronized (this) {
                while (paused) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            super.play(wav);
            if (played.size() == 1) {
                firstPlayed.countDown();
                try {
                    goOn.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public synchronized void pause() {
            paused = true;
        }

        @Override
        public synchronized void resume() {
            paused = false;
            notifyAll();
        }

        @Override
        public synchronized void stop() {
            super.stop();
            paused = false;
            notifyAll();
        }

        @Override
        public void setRate(double rate) {
            rates.add(rate);
        }
    }

    static class RecordingPlayback implements AudioPlayback {

        final List<String> played = new CopyOnWriteArrayList<String>();
        volatile int stops;

        @Override
        public void play(byte[] wav) {
            played.add(new String(wav, StandardCharsets.UTF_8));
        }

        @Override
        public void finish() {
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
