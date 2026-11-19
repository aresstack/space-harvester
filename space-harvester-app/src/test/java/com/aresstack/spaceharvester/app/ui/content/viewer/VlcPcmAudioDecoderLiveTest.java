package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.transcription.VlcPcmAudioDecoder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise actual native decoding locally without requiring BSCW or a Whisper model. */
final class VlcPcmAudioDecoderLiveTest {

    @TempDir
    Path workspace;

    @Test
    void decodesARealWavIntoMonoPcmThroughLibVlc() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("spaceharvester.test.vlcpcm"),
                "Enable only on a workstation with libVLC installed");
        Assumptions.assumeTrue(VlcRuntimeLocator.configure(),
                "A working libVLC installation is required");

        Path audioFile = workspace.resolve("reference.wav");
        writeTone(audioFile);
        Path decoded = new VlcPcmAudioDecoder().decode(audioFile.toUri(), () -> false);
        try {
            long bytes = Files.size(decoded);
            assertThat(bytes).as("Decoded PCM must contain nonzero audio").isGreaterThan(16_000);
            assertThat(bytes).as("The test source lasts just one second").isLessThan(64_000);
        } finally {
            Files.deleteIfExists(decoded);
        }
    }

    private static void writeTone(Path file) throws Exception {
        int sampleRate = 16_000;
        byte[] samples = new byte[sampleRate * 2];
        for (int index = 0; index < sampleRate; index++) {
            short value = (short) Math.round(Math.sin(2 * Math.PI * 440 * index / sampleRate) * 12_000);
            samples[index * 2] = (byte) value;
            samples[index * 2 + 1] = (byte) (value >>> 8);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream audio = new AudioInputStream(
                new ByteArrayInputStream(samples), format, sampleRate)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
