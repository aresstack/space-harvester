package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class VideoTranscriptSourceTest {

    @Test
    void suppliesAudioOnlyMp4WithoutReadingVideoPayload() throws Exception {
        TestMp4 fixture = TestMp4.create();
        List<long[]> remoteReads = new ArrayList<>();
        VideoTranscriptSource source = new VideoTranscriptSource(fixture.data.length, (offset, length) -> {
            remoteReads.add(new long[]{offset, length});
            return Arrays.copyOfRange(fixture.data, (int) offset, (int) offset + length);
        });
        try {
            assertThat(source.openAudioOnly()).isEqualTo(source.openAudioOnly());
            HttpURLConnection connection = (HttpURLConnection) source.openAudioOnly().toURL().openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            String response;
            try (InputStream content = connection.getInputStream()) {
                assertThat(connection.getResponseCode()).isEqualTo(200);
                response = new String(content.readAllBytes(), StandardCharsets.ISO_8859_1);
            } finally {
                connection.disconnect();
            }
            assertThat(response).contains("soun", "AUD1");
            assertThat(response).doesNotContain("vide", "VIDE");
            for (long[] range : remoteReads) {
                long start = range[0];
                long end = start + range[1];
                assertThat(start < fixture.videoEnd && fixture.videoStart < end)
                        .as("Never download remote video sample bytes").isFalse();
            }
        } finally {
            source.close();
        }
        assertThatThrownBy(source::openAudioOnly).isInstanceOf(java.io.IOException.class);
    }

    private static byte[] box(String name, byte[]... payload) {
        int size = 8;
        for (byte[] part : payload) {
            size += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(size);
        buffer.put(name.getBytes(StandardCharsets.US_ASCII));
        for (byte[] part : payload) {
            buffer.put(part);
        }
        return buffer.array();
    }

    private static byte[] numbers(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    private static int find(byte[] data, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        for (int start = 0; start <= data.length - expected.length; start++) {
            boolean match = true;
            for (int i = 0; i < expected.length; i++) {
                if (data[start + i] != expected[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return start;
            }
        }
        return -1;
    }

    private static final class TestMp4 {
        private final byte[] data;
        private final long videoStart;
        private final long videoEnd;

        private TestMp4(byte[] data, long videoStart, long videoEnd) {
            this.data = data;
            this.videoStart = videoStart;
            this.videoEnd = videoEnd;
        }

        private static TestMp4 create() {
            byte[] ftyp = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII), numbers(0));
            byte[] video = box("trak", box("mdia", box("hdlr", numbers(0, 0),
                    "vide".getBytes(StandardCharsets.US_ASCII))));
            byte[] stsc = box("stsc", numbers(0, 1, 1, 1, 1));
            byte[] stsz = box("stsz", numbers(0, 0, 1, 4));
            byte[] stco = box("stco", numbers(0, 1, 0));
            byte[] audio = box("trak", box("mdia", box("hdlr", numbers(0, 0),
                    "soun".getBytes(StandardCharsets.US_ASCII)),
                    box("minf", box("stbl", stsc, stsz, stco))));
            byte[] moov = box("moov", box("mvhd", numbers(0, 0)), video, audio);
            int videoPosition = ftyp.length + moov.length + 8;
            int audioPosition = videoPosition + 4;
            ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
                    .putInt(find(moov, "stco") + 12, audioPosition);
            byte[] mdat = box("mdat", "VIDE".getBytes(StandardCharsets.US_ASCII),
                    "AUD1".getBytes(StandardCharsets.US_ASCII));
            byte[] data = new byte[ftyp.length + moov.length + mdat.length];
            System.arraycopy(ftyp, 0, data, 0, ftyp.length);
            System.arraycopy(moov, 0, data, ftyp.length, moov.length);
            System.arraycopy(mdat, 0, data, ftyp.length + moov.length, mdat.length);
            return new TestMp4(data, videoPosition, audioPosition);
        }
    }
}
