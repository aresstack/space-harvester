package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.speech.RecordedSpeech;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Nimmt vom Standard-Mikrofon über Java Sound auf (reines Java 8, kein externes Programm, keine DSP-Kette): bevorzugt
 * 16 kHz mono, sonst das erste Format, das das Gerät annimmt; danach Abmischen auf mono und Umrechnen auf 16 kHz
 * (Mittelwert je Fenster beim Heruntertakten, sonst linear). Eine Instanz je Aufnahme; {@link #stop()} liefert die Aufnahme.
 */
final class JavaSoundRecorder {

    /** Länger nimmt der Client nicht auf (10 Minuten); danach wird still abgeschnitten. */
    private static final int MAX_SECONDS = 600;
    private static final float[][] CANDIDATES = {
            {16000f, 1}, {48000f, 1}, {44100f, 1}, {32000f, 1}, {22050f, 1}, {48000f, 2}, {44100f, 2}};

    private final TargetDataLine line;
    private final AudioFormat format;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final Thread reader;
    private volatile boolean running = true;
    /** Spitzenwert seit dem letzten {@link #consumeRecentPeak()} (16 Bit, Betrag) für die Pegelanzeige. */
    private volatile int recentPeak;
    private volatile boolean clipped;

    private JavaSoundRecorder(TargetDataLine line, AudioFormat format) {
        this.line = line;
        this.format = format;
        this.reader = new Thread(new Runnable() {
            @Override
            public void run() {
                read();
            }
        }, "enterprise-ai-microphone");
        reader.setDaemon(true);
    }

    /** Öffnet das Mikrofon und startet die Aufnahme. */
    static JavaSoundRecorder start() throws LineUnavailableException {
        LineUnavailableException last = null;
        for (float[] candidate : CANDIDATES) {
            AudioFormat format = new AudioFormat(candidate[0], 16, (int) candidate[1], true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                JavaSoundRecorder recorder = new JavaSoundRecorder(line, format);
                recorder.reader.start();
                return recorder;
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException failed) {
                last = failed instanceof LineUnavailableException ? (LineUnavailableException) failed
                        : new LineUnavailableException(failed.getMessage());
            }
        }
        throw last != null ? last : new LineUnavailableException("Kein Mikrofon gefunden");
    }

    private void read() {
        byte[] buffer = new byte[Math.max(4096, line.getBufferSize() / 4)];
        long limit = (long) (MAX_SECONDS * format.getSampleRate() * format.getFrameSize());
        while (running) {
            int count = line.read(buffer, 0, buffer.length);
            if (count > 0) {
                measure(buffer, count);
            }
            if (count > 0 && captured.size() < limit) {
                synchronized (captured) {
                    captured.write(buffer, 0, count);
                }
            }
        }
    }

    /** Pegel wie arch {@code AudioLevelMeter}: Spitzenbetrag je Puffer, übersteuert ab Vollausschlag. */
    private void measure(byte[] pcm, int count) {
        int peak = recentPeak;
        for (int offset = 0; offset + 1 < count; offset += 2) {
            int magnitude = Math.abs((int) (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8)));
            if (magnitude > peak) {
                peak = magnitude;
            }
            if (magnitude >= Short.MAX_VALUE) {
                clipped = true;
            }
        }
        recentPeak = peak;
    }

    /** Der Spitzenwert seit dem letzten Aufruf (live, nicht das Maximum der ganzen Aufnahme). */
    int consumeRecentPeak() {
        int value = recentPeak;
        recentPeak = 0;
        return value;
    }

    /** Ob die Aufnahme irgendwann übersteuert war. */
    boolean clipped() {
        return clipped;
    }

    /** Beendet die Aufnahme; 16 kHz mono. */
    RecordedSpeech stop() {
        close();
        byte[] bytes;
        synchronized (captured) {
            bytes = captured.toByteArray();
        }
        return RecordedSpeech.of(toMono16k(bytes, format.getChannels(), format.getSampleRate()));
    }

    /** Verwirft die Aufnahme; idempotent. */
    void close() {
        running = false;
        line.stop();
        line.close();
        try {
            reader.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 16 Bit little-endian, verschachtelte Kanäle zu mono in [-1, 1], auf 16 kHz umgerechnet. */
    static float[] toMono16k(byte[] pcm, int channels, float sampleRate) {
        int frames = pcm.length / (2 * channels);
        float[] mono = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int offset = (frame * channels + channel) * 2;
                sum += (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8)) / 32768f;
            }
            mono[frame] = sum / channels;
        }
        if (Math.abs(sampleRate - RecordedSpeech.SAMPLE_RATE) < 1f || frames == 0) {
            return mono;
        }
        double ratio = sampleRate / RecordedSpeech.SAMPLE_RATE;
        int length = (int) Math.floor(frames / ratio);
        float[] resampled = new float[length];
        for (int i = 0; i < length; i++) {
            if (ratio > 1) {
                // Mittelwert über das Quellfenster: grober Tiefpass gegen Aliasing beim Heruntertakten.
                int from = (int) Math.floor(i * ratio);
                int to = Math.min(frames, Math.max(from + 1, (int) Math.floor((i + 1) * ratio)));
                float sum = 0;
                for (int k = from; k < to; k++) {
                    sum += mono[k];
                }
                resampled[i] = sum / (to - from);
            } else {
                double position = i * ratio;
                int index = (int) position;
                double fraction = position - index;
                float next = index + 1 < frames ? mono[index + 1] : mono[index];
                resampled[i] = (float) (mono[index] * (1 - fraction) + next * fraction);
            }
        }
        return resampled;
    }
}
