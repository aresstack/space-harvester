package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedPcmAudioSourceTest {

    @Test
    void spillsThePreviousChunkOnlyWhenTheNextTwoMinuteChunkIsComplete() throws Exception {
        int sourceRate = 16000;
        int fullChunkBytes = sourceRate * 120 * 2;

        try (ChunkedPcmAudioSource recording = new ChunkedPcmAudioSource(sourceRate)) {
            recording.append(constantPcm(fullChunkBytes, (short) 8192), 0, fullChunkBytes);
            assertEquals(0, recording.spilledChunkCount());
            assertEquals(fullChunkBytes, recording.residentAudioBytes());

            byte[] secondChunk = constantPcm(fullChunkBytes, (short) -8192);
            recording.append(secondChunk, 0, fullChunkBytes / 2);
            assertEquals(0, recording.spilledChunkCount());
            assertEquals(fullChunkBytes + fullChunkBytes / 2, recording.residentAudioBytes());

            recording.append(secondChunk, fullChunkBytes / 2, fullChunkBytes / 2);
            assertEquals(1, recording.spilledChunkCount());
            assertEquals(fullChunkBytes, recording.residentAudioBytes());

            byte[] finalChunk = constantPcm(16000 * 8 * 2, (short) 16384);
            recording.append(finalChunk, 0, finalChunk.length);
            recording.finish();

            assertEquals(248 * 16000L, recording.sampleCount());
            assertEquals(1, recording.spilledChunkCount());
            assertEquals(fullChunkBytes + finalChunk.length, recording.residentAudioBytes());

            float[] crossingFirstBoundary = recording.readSamples(120 * 16000L - 4, 8);
            for (int i = 0; i < 4; i++) {
                assertEquals(0.25f, crossingFirstBoundary[i], 0.0001f);
                assertEquals(-0.25f, crossingFirstBoundary[i + 4], 0.0001f);
            }

            float[] crossingSecondBoundary = recording.readSamples(240 * 16000L - 4, 8);
            for (int i = 0; i < 4; i++) {
                assertEquals(-0.25f, crossingSecondBoundary[i], 0.0001f);
                assertEquals(0.5f, crossingSecondBoundary[i + 4], 0.0001f);
            }
        }
    }

    @Test
    void retainsMoreThanTenMinutesWithoutGrowingResidentAudio() throws Exception {
        int sourceRate = 16000;
        int fullChunkBytes = sourceRate * 120 * 2;
        byte[] chunk = constantPcm(fullChunkBytes, (short) 4096);
        try (ChunkedPcmAudioSource recording = new ChunkedPcmAudioSource(sourceRate)) {
            for (int minute = 0; minute < 5; minute++) {
                recording.append(chunk, 0, chunk.length);
            }
            byte[] finalMinute = constantPcm(sourceRate * 60 * 2, (short) 8192);
            recording.append(finalMinute, 0, finalMinute.length);
            recording.finish();

            assertEquals(660 * 16000L, recording.sampleCount());
            assertEquals(4, recording.spilledChunkCount());
            assertEquals(fullChunkBytes + finalMinute.length, recording.residentAudioBytes());
            assertEquals(0.125f, recording.readSamples(590 * 16000L, 160)[0], 0.0001f);
            assertEquals(0.25f, recording.readSamples(650 * 16000L, 160)[0], 0.0001f);
        }
    }

    @Test
    void readsUnalignedSixteenKilohertzWindowsWithoutChangingTheirPosition() throws Exception {
        try (ChunkedPcmAudioSource recording = new ChunkedPcmAudioSource(16000)) {
            byte[] input = constantPcm(32000 * 2, (short) 4096);
            recording.append(input, 0, input.length);
            recording.finish();

            float[] window = recording.readSamples(177, 234);
            assertEquals(234, window.length);
            for (float sample : window) {
                assertEquals(0.125f, sample, 0.0001f);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> recording.readSamples(recording.sampleCount() - 10, 11));
        }
    }

    @Test
    void freesCapturedAudioAfterClosingARecordedSpeech() throws Exception {
        ChunkedPcmAudioSource audio = new ChunkedPcmAudioSource(16000);
        byte[] pcm = constantPcm(32000, (short) 1024);
        audio.append(pcm, 0, pcm.length);
        audio.finish();
        RecordedSpeech speech = new RecordedSpeech(audio);

        assertEquals(1000L, speech.durationMillis());
        assertEquals(0.03125f, speech.readSamples(1, 20)[0], 0.0001f);
        speech.close();
        speech.close();

        assertThrows(java.io.IOException.class, () -> speech.readSamples(0, 1));
    }

    @Test
    void resamplesFortyFourKilohertzAudioWithoutReadingTheEntireRecording() throws Exception {
        try (ChunkedPcmAudioSource recording = new ChunkedPcmAudioSource(44100)) {
            byte[] input = constantPcm(44100 * 3 * 2, (short) 8192);
            recording.append(input, 0, input.length);
            recording.finish();

            assertEquals(48000, recording.sampleCount());
            float[] window = recording.readSamples(16000 + 123, 500);
            assertEquals(500, window.length);
            for (float sample : window) {
                assertTrue(Math.abs(sample - 0.25f) < 0.001f);
            }
        }
    }

    private static byte[] constantPcm(int byteCount, short sample) {
        byte[] pcm = new byte[byteCount];
        for (int i = 0; i < byteCount; i += 2) {
            pcm[i] = (byte) sample;
            pcm[i + 1] = (byte) (sample >>> 8);
        }
        return pcm;
    }

    @Test
    void liveViewReturnsExactlyTheSamplesOfTheStoppedRecording() throws Exception {
        int sourceRate = 48000;
        int chunkBytes = sourceRate * 120 * 2;
        java.util.Random random = new java.util.Random(5);
        byte[] pcm = new byte[chunkBytes * 2 + 48000 * 2 * 7];
        random.nextBytes(pcm);
        java.util.List<float[]> liveWindows = new java.util.ArrayList<>();
        java.util.List<long[]> requested = new java.util.ArrayList<>();
        try (ChunkedPcmAudioSource recording = new ChunkedPcmAudioSource(sourceRate)) {
            com.aresstack.windirectml.inference.whisper.WhisperAudioSource live = recording.liveView();
            for (int offset = 0; offset < pcm.length; offset += 9600) {
                recording.append(pcm, offset, Math.min(9600, pcm.length - offset));
                long available = live.sampleCount();
                if (available >= 16000 * 30 && liveWindows.size() < 12 && offset % (9600 * 50) == 0) {
                    long start = available - 16000 * 30;
                    liveWindows.add(live.readSamples(start, 16000 * 30));
                    requested.add(new long[]{start});
                }
            }
            assertTrue(recording.spilledChunkCount() >= 1, "The test must cover a spilled chunk");
            assertTrue(live.sampleCount() < recording.sampleCount(), "Unsettled samples are withheld while recording");
            recording.finish();
            assertEquals(recording.sampleCount(), live.sampleCount());
            assertTrue(liveWindows.size() > 3);
            for (int i = 0; i < liveWindows.size(); i++) {
                float[] stopped = recording.readSamples(requested.get(i)[0], 16000 * 30);
                assertTrue(java.util.Arrays.equals(stopped, liveWindows.get(i)),
                        "Live window " + i + " differs from the stopped recording");
            }
        }
    }
}
