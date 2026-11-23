package com.aresstack.spaceharvester.app.speech;

import com.aresstack.enterpriseai.astu.acropolis.speech.PcmTimeStretch;
import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechRate;
import com.aresstack.spaceharvester.application.podcast.ReadAloud;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Spielt WAV über eine {@link SourceDataLine} des Standardausgabegeräts ab (wie {@code playWav} in askai arch, ohne
 * Audio-DSP). Aufeinanderfolgende Stücke gleichen Formats laufen über dieselbe offene Leitung: kein Öffnen, Leerlaufen
 * und Schließen je Stück, also keine Lücke zwischen Absätzen oder Sätzen. {@link #finish()} lässt die Folge
 * ausklingen und schließt die Leitung, {@link #stop()} bricht ab und verwirft den Puffer. Ein Stopp gilt bis zum
 * nächsten {@link #begin()}: ein Stück, dessen Wiedergabe erst nach dem Stopp beginnen wollte, erklingt nicht mehr
 * (sonst spräche nach einem Modellwechsel noch ein Absatz der alten Stimme neben der neuen).
 *
 * <p>Unverändert aus dem Enterprise AI Client ({@code proasteion/exedra/speech/JavaSoundAudioPlayback}); nur die
 * Schnittstelle ist die {@link ReadAloud.Playback} des Core statt {@code AudioPlayback} (Angelo 23.11.2026: „du musst
 * die funktionierende Logik aus dem Enterprise Client kopieren“).
 *
 * <p>Pause stops the line without flushing it, so {@link #resume()} continues at the same sample; a write blocks
 * while the line is paused. 16-bit PCM goes through {@link PcmTimeStretch} at the current {@link #setRate rate}
 * (block by block, so a new rate applies mid-chunk); audio that cannot be converted to 16-bit PCM plays unchanged.
 */
public final class JavaSoundAudioPlayback implements ReadAloud.Playback {

    private final Object lock = new Object();
    private SourceDataLine line;
    private AudioFormat lineFormat;
    /** Die Leitung, die {@link #finish()} gerade ausklingen lässt; {@link #stop()} hält auch sie an. */
    private SourceDataLine draining;
    private boolean stopped;
    private boolean paused;
    private volatile double rate = SpeechRate.NORMAL;

    /** Frames per stretch block: a twentieth of a second, so a rate change is heard almost at once. */
    private static final int BLOCKS_PER_SECOND = 20;

    @Override
    public void begin() {
        synchronized (lock) {
            stopped = false;
            paused = false;
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            paused = true;
            if (line != null) {
                line.stop();
            }
            if (draining != null) {
                draining.stop();
            }
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            paused = false;
            if (!stopped) {
                if (line != null) {
                    line.start();
                }
                if (draining != null) {
                    draining.start();
                }
            }
        }
    }

    @Override
    public void setRate(double rate) {
        this.rate = SpeechRate.clamp(rate);
    }

    @Override
    public void play(byte[] wav) throws IOException {
        if (isStopped()) {
            return;
        }
        AudioInputStream audio;
        try {
            audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Audioformat nicht lesbar", e);
        }
        try {
            AudioInputStream pcm = pcm16(audio);
            if (pcm != null) {
                playStretched(pcm);
                return;
            }
            AudioFormat format = audio.getFormat();
            SourceDataLine target = open(format);
            byte[] buffer = new byte[Math.max(4096, format.getFrameSize() * 1024)];
            int read;
            while ((read = audio.read(buffer)) > 0) {
                if (isStopped()) {
                    return;
                }
                target.write(buffer, 0, read); // blockiert nur, solange der Puffer der Leitung voll ist
            }
        } finally {
            audio.close();
        }
    }

    /** The audio as signed 16-bit PCM, converted if needed; {@code null} when Java Sound cannot convert it. */
    private static AudioInputStream pcm16(AudioInputStream audio) {
        AudioFormat format = audio.getFormat();
        if (AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) && format.getSampleSizeInBits() == 16) {
            return audio;
        }
        if (format.getSampleRate() == AudioSystem.NOT_SPECIFIED || format.getChannels() == AudioSystem.NOT_SPECIFIED) {
            return null;
        }
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
                format.getChannels(), format.getChannels() * 2, format.getSampleRate(), false);
        if (!AudioSystem.isConversionSupported(target, format)) {
            return null;
        }
        return AudioSystem.getAudioInputStream(target, audio);
    }

    /** Plays 16-bit PCM through the time stretcher, reading the rate before every block. */
    private void playStretched(AudioInputStream pcm) throws IOException {
        AudioFormat format = pcm.getFormat();
        SourceDataLine target = open(format);
        int channels = format.getChannels();
        boolean bigEndian = format.isBigEndian();
        PcmTimeStretch stretch = new PcmTimeStretch(Math.round(format.getSampleRate()), channels);
        int frameSize = format.getFrameSize();
        int blockFrames = Math.max(256, Math.round(format.getSampleRate()) / BLOCKS_PER_SECOND);
        byte[] buffer = new byte[blockFrames * frameSize];
        short[] samples = new short[blockFrames * channels];
        int filled = 0;
        int read;
        while ((read = pcm.read(buffer, filled, buffer.length - filled)) > 0 || filled > 0) {
            if (read > 0) {
                filled += read;
                if (filled < buffer.length) {
                    continue; // collect a whole block
                }
            }
            if (isStopped()) {
                return;
            }
            int frames = filled / frameSize;
            toSamples(buffer, frames * channels, bigEndian, samples);
            write(target, stretch.process(samples, frames, rate), bigEndian);
            int rest = filled - frames * frameSize;
            System.arraycopy(buffer, frames * frameSize, buffer, 0, rest);
            filled = rest;
            if (read <= 0) {
                break;
            }
        }
        if (!isStopped()) {
            write(target, stretch.flush(rate), bigEndian);
        }
    }

    private static void toSamples(byte[] bytes, int count, boolean bigEndian, short[] samples) {
        for (int i = 0; i < count; i++) {
            int high = bigEndian ? bytes[2 * i] : bytes[2 * i + 1];
            int low = (bigEndian ? bytes[2 * i + 1] : bytes[2 * i]) & 0xff;
            samples[i] = (short) ((high << 8) | low);
        }
    }

    private void write(SourceDataLine target, short[] samples, boolean bigEndian) {
        if (samples.length == 0 || isStopped()) {
            return;
        }
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = samples[i];
            bytes[2 * i] = (byte) (bigEndian ? value >> 8 : value);
            bytes[2 * i + 1] = (byte) (bigEndian ? value : value >> 8);
        }
        target.write(bytes, 0, bytes.length); // blocks while the line buffer is full or the line is paused
    }

    /** Die offene Leitung, wenn das Format passt; sonst wird die alte ausgespielt und eine neue geöffnet. */
    private SourceDataLine open(AudioFormat format) throws IOException {
        synchronized (lock) {
            if (line != null && line.isOpen() && lineFormat != null && lineFormat.matches(format)) {
                if (!stopped && !paused) {
                    line.start();
                }
                return line;
            }
        }
        finish();
        SourceDataLine opened;
        try {
            opened = AudioSystem.getSourceDataLine(format);
            opened.open(format);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new IOException("Kein Audioausgabegerät verfügbar", e);
        }
        synchronized (lock) {
            line = opened;
            lineFormat = format;
            if (stopped || paused) {
                opened.stop(); // während des Öffnens gestoppt oder pausiert: nichts ausgeben
            } else {
                opened.start();
            }
        }
        return opened;
    }

    private boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    @Override
    public void finish() {
        SourceDataLine current;
        boolean drain;
        synchronized (lock) {
            current = line;
            drain = !stopped;
            line = null;
            lineFormat = null;
            draining = drain ? current : null;
        }
        if (current == null) {
            return;
        }
        try {
            if (drain) {
                current.drain(); // endet auch, wenn stop() die Leitung anhält
            }
        } finally {
            synchronized (lock) {
                draining = null;
            }
            current.stop();
            current.close();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopped = true;
            paused = false;
            if (line != null) {
                line.stop();
                line.flush();
            }
            if (draining != null) {
                draining.stop();
                draining.flush();
            }
        }
    }
}
