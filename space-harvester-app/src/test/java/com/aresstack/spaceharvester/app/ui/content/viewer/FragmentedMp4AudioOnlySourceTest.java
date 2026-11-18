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

final class FragmentedMp4AudioOnlySourceTest {

    @Test
    void remuxesOnlyAudioFragmentsAndNeverReadsVideoSampleBytes() throws Exception {
        Fixture fixture = Fixture.create();
        List<long[]> reads = new ArrayList<>();
        Mp4AudioStream result = Mp4AudioOnlySource.open((offset, count) -> {
            reads.add(new long[]{offset, count});
            return Arrays.copyOfRange(fixture.bytes, (int) offset, (int) offset + count);
        }, fixture.bytes.length);

        byte[] remuxed = collect(result);
        String ascii = new String(remuxed, StandardCharsets.ISO_8859_1);
        assertThat(ascii).contains("soun", "mvex", "moof", "mdat", "AUD1AUD22");
        assertThat(ascii).doesNotContain("vide", "VIDE");
        assertThat(result.length()).isLessThan(fixture.bytes.length);

        for (long[] read : reads) {
            long begin = read[0];
            long end = begin + read[1];
            assertThat(overlaps(begin, end, fixture.videoStart, fixture.videoEnd)).isFalse();
        }
    }

    @Test
    void canSeekAcrossAudioFragmentsWithoutFetchingEitherVideoRun() throws Exception {
        Fixture first = Fixture.create();
        byte[] temporary = moof(0, 0);
        int payload = temporary.length + 8;
        byte[] secondMoof = moof(payload, payload + 4);
        byte[] secondMdat = box("mdat", "VIDE".getBytes(StandardCharsets.US_ASCII),
                "AUD1".getBytes(StandardCharsets.US_ASCII),
                "AUD22".getBytes(StandardCharsets.US_ASCII));
        byte[] source = Arrays.copyOf(first.bytes,
                first.bytes.length + secondMoof.length + secondMdat.length);
        System.arraycopy(secondMoof, 0, source, first.bytes.length, secondMoof.length);
        System.arraycopy(secondMdat, 0, source, first.bytes.length + secondMoof.length, secondMdat.length);
        long secondVideoStart = first.bytes.length + secondMoof.length + 8;
        List<long[]> reads = new ArrayList<>();
        Mp4AudioStream remuxed = Mp4AudioOnlySource.open((offset, count) -> {
            reads.add(new long[]{offset, count});
            return Arrays.copyOfRange(source, (int) offset, (int) offset + count);
        }, source.length);

        String content = new String(collect(remuxed), StandardCharsets.ISO_8859_1);
        assertThat(content.split("AUD1AUD22", -1)).hasSize(3);
        assertThat(content).doesNotContain("VIDE");
        assertThat(new String(remuxed.read(remuxed.length() - 5, 5), StandardCharsets.US_ASCII))
                .isEqualTo("AUD22");

        for (long[] read : reads) {
            long begin = read[0];
            long end = begin + read[1];
            assertThat(overlaps(begin, end, first.videoStart, first.videoEnd)).isFalse();
            assertThat(overlaps(begin, end, secondVideoStart, secondVideoStart + 4)).isFalse();
        }
    }

    private static byte[] collect(Mp4AudioStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (long position = 0; position < stream.length();) {
            int count = (int) Math.min(11, stream.length() - position);
            byte[] next = stream.read(position, count);
            output.write(next);
            position += next.length;
        }
        return output.toByteArray();
    }

    private static boolean overlaps(long firstStart, long firstEnd, long secondStart, long secondEnd) {
        return firstStart < secondEnd && secondStart < firstEnd;
    }

    private static byte[] box(String name, byte[]... parts) {
        int size = 8;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        result.putInt(size);
        result.put(name.getBytes(StandardCharsets.US_ASCII));
        for (byte[] part : parts) {
            result.put(part);
        }
        return result.array();
    }

    private static byte[] numbers(int... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (int number : values) {
            result.putInt(number);
        }
        return result.array();
    }

    private static byte[] track(int id, String handler) {
        return box("trak", box("tkhd", numbers(0, 0, 0, id, 0, 0)),
                box("mdia", box("hdlr", numbers(0, 0),
                        handler.getBytes(StandardCharsets.US_ASCII))));
    }

    private static byte[] trex(int id) {
        return box("trex", numbers(0, id, 1, 1024, 0, 0));
    }

    private static byte[] traf(int id, int dataOffset, int... samples) {
        return box("traf",
                box("tfhd", numbers(0x20000, id)),
                box("tfdt", numbers(0, 0)),
                box("trun", numbers(0x201, samples.length, dataOffset), numbers(samples)));
    }

    private static byte[] moof(int videoOffset, int audioOffset) {
        return box("moof", box("mfhd", numbers(0, 1)),
                traf(1, videoOffset, 4), traf(2, audioOffset, 4, 5));
    }

    private static final class Fixture {
        private final byte[] bytes;
        private final long videoStart;
        private final long videoEnd;

        private Fixture(byte[] bytes, long videoStart, long videoEnd) {
            this.bytes = bytes;
            this.videoStart = videoStart;
            this.videoEnd = videoEnd;
        }

        private static Fixture create() {
            byte[] ftyp = box("ftyp", "iso6".getBytes(StandardCharsets.US_ASCII),
                    numbers(0), "iso6".getBytes(StandardCharsets.US_ASCII));
            byte[] moov = box("moov", box("mvhd", numbers(0, 0)),
                    track(1, "vide"), track(2, "soun"), box("mvex", trex(1), trex(2)));
            byte[] temporaryMoof = moof(0, 0);
            int dataOffset = temporaryMoof.length + 8;
            byte[] moof = moof(dataOffset, dataOffset + 4);
            byte[] mdat = box("mdat", "VIDE".getBytes(StandardCharsets.US_ASCII),
                    "AUD1".getBytes(StandardCharsets.US_ASCII),
                    "AUD22".getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(ftyp, 0, ftyp.length);
            result.write(moov, 0, moov.length);
            result.write(moof, 0, moof.length);
            result.write(mdat, 0, mdat.length);
            long videoStart = ftyp.length + moov.length + moof.length + 8;
            return new Fixture(result.toByteArray(), videoStart, videoStart + 4);
        }
    }
}
