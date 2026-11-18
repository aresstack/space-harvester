package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class Mp4AudioOnlySourceTest {

    @Test
    void readsOnlyIndexedAudioChunksAndExposesSeekableAudioOnlyMp4() throws Exception {
        Fixture fixture = Fixture.create();
        List<String> originalReads = new ArrayList<>();
        MediaStreamBridge.ChunkSource remote = (offset, count) -> {
            assertThat(offset).isGreaterThanOrEqualTo(0);
            assertThat(offset + count).isLessThanOrEqualTo(fixture.mp4.length);
            originalReads.add(offset + ":" + count);
            return Arrays.copyOfRange(fixture.mp4, (int) offset, (int) offset + count);
        };

        Mp4AudioStream audio = Mp4AudioOnlySource.open(remote, fixture.mp4.length);
        assertThat(audio.length()).isLessThan(fixture.mp4.length);

        byte[] remuxed = collect(audio);
        String printable = new String(remuxed, StandardCharsets.ISO_8859_1);
        assertThat(printable).contains("ftyp", "moov", "soun", "mdat", "AUD1AUD22");
        assertThat(printable).doesNotContain("vide", "VIDE", "VIDEO");
        assertThat(remuxed).endsWith("AUD1AUD22".getBytes(StandardCharsets.US_ASCII));

        for (String entry : originalReads) {
            String[] values = entry.split(":");
            long start = Long.parseLong(values[0]);
            long end = start + Integer.parseInt(values[1]);
            assertThat(overlaps(start, end, fixture.videoStart1, fixture.videoStart1 + 4)
                    && start >= fixture.mediaStart).isFalse();
            assertThat(overlaps(start, end, fixture.videoStart2, fixture.videoStart2 + 5)
                    && start >= fixture.mediaStart).isFalse();
        }
    }

    @Test
    void rejectsMp4WithoutAudioTrackInsteadOfFallingBackToOriginalVideo() throws Exception {
        Fixture fixture = Fixture.create();
        byte[] noAudio = fixture.mp4.clone();
        int audioHandler = indexOf(noAudio, "soun".getBytes(StandardCharsets.US_ASCII));
        assertThat(audioHandler).isGreaterThanOrEqualTo(0);
        System.arraycopy("vide".getBytes(StandardCharsets.US_ASCII), 0, noAudio, audioHandler, 4);

        assertThatThrownBy(() -> Mp4AudioOnlySource.open(
                (offset, count) -> Arrays.copyOfRange(noAudio, (int) offset, (int) offset + count),
                noAudio.length)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("no indexed audio track");
    }

    private static byte[] collect(Mp4AudioStream source) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (long offset = 0; offset < source.length();) {
            int count = (int) Math.min(3, source.length() - offset);
            byte[] part = source.read(offset, count);
            result.write(part);
            offset += part.length;
        }
        return result.toByteArray();
    }

    private static boolean overlaps(long firstStart, long firstEnd, long secondStart, long secondEnd) {
        return firstStart < secondEnd && secondStart < firstEnd;
    }

    private static int indexOf(byte[] body, byte[] needle) {
        for (int i = 0; i <= body.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && body[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] box(String name, byte[]... parts) {
        int length = 8;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        output.putInt(length);
        output.put(name.getBytes(StandardCharsets.US_ASCII));
        for (byte[] part : parts) {
            output.put(part);
        }
        return output.array();
    }

    private static byte[] ints(int... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (int value : values) {
            result.putInt(value);
        }
        return result.array();
    }

    private static byte[] handler(String type) {
        return box("hdlr", ints(0, 0), type.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Fixture {
        private final byte[] mp4;
        private final int mediaStart;
        private final int videoStart1;
        private final int videoStart2;

        private Fixture(byte[] mp4, int mediaStart, int videoStart1, int videoStart2) {
            this.mp4 = mp4;
            this.mediaStart = mediaStart;
            this.videoStart1 = videoStart1;
            this.videoStart2 = videoStart2;
        }

        private static Fixture create() {
            byte[] ftyp = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII), ints(0),
                    "isom".getBytes(StandardCharsets.US_ASCII));
            byte[] video = box("trak", box("mdia", handler("vide")));
            byte[] stsc = box("stsc", ints(0, 1, 1, 1, 1));
            byte[] stsz = box("stsz", ints(0, 0, 2, 4, 5));
            byte[] stco = box("stco", ints(0, 2, 0, 0));
            byte[] audio = box("trak", box("mdia", handler("soun"),
                    box("minf", box("stbl", stsc, stsz, stco))));
            byte[] moov = box("moov", box("mvhd", ints(0, 0)), video, audio);
            int mediaStart = ftyp.length + moov.length + 8;
            int firstAudio = mediaStart + 4;
            int secondVideo = firstAudio + 4;
            int secondAudio = secondVideo + 5;
            int stcoStart = indexOf(moov, "stco".getBytes(StandardCharsets.US_ASCII));
            ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
                    .putInt(stcoStart + 12, firstAudio).putInt(stcoStart + 16, secondAudio);
            byte[] mdat = box("mdat", "VIDE".getBytes(StandardCharsets.US_ASCII),
                    "AUD1".getBytes(StandardCharsets.US_ASCII),
                    "VIDEO".getBytes(StandardCharsets.US_ASCII),
                    "AUD22".getBytes(StandardCharsets.US_ASCII));
            byte[] mp4 = new byte[ftyp.length + moov.length + mdat.length];
            System.arraycopy(ftyp, 0, mp4, 0, ftyp.length);
            System.arraycopy(moov, 0, mp4, ftyp.length, moov.length);
            System.arraycopy(mdat, 0, mp4, ftyp.length + moov.length, mdat.length);
            return new Fixture(mp4, mediaStart, mediaStart, secondVideo);
        }
    }
}
