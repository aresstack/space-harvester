package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoFileDownloaderTest {

    @TempDir
    Path directory;

    @Test
    void downloadsOnlyAfterExplicitCallAndUsesBoundedRanges() throws Exception {
        byte[] content = new byte[MediaStreamBridge.CHUNK_BYTES + 12];
        java.util.Arrays.fill(content, (byte) 7);
        AtomicInteger requests = new AtomicInteger();
        List<Long> progress = new ArrayList<>();
        MediaStreamBridge.ChunkSource source = (offset, length) -> {
            requests.incrementAndGet();
            byte[] part = new byte[length];
            System.arraycopy(content, (int) offset, part, 0, length);
            return part;
        };
        Path result = directory.resolve("lecture.mp4");
        assertThat(requests).hasValue(0);
        VideoFileDownloader.download(source, content.length, result, () -> false, progress::add);
        assertThat(Files.readAllBytes(result)).isEqualTo(content);
        assertThat(requests).hasValue(2);
        assertThat(progress).containsExactly((long) MediaStreamBridge.CHUNK_BYTES, (long) content.length);
    }

    @Test
    void failedRangeDoesNotOverwriteExistingVideoOrLeavePartialFiles() throws Exception {
        Path result = directory.resolve("existing.mp4");
        byte[] existing = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(result, existing);

        assertThatThrownBy(() -> VideoFileDownloader.download((offset, count) -> new byte[0],
                1000, result, () -> false, bytes -> { }))
                .isInstanceOf(IOException.class).hasMessageContaining("Incomplete media range");

        assertThat(Files.readAllBytes(result)).isEqualTo(existing);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(Path::getFileName).map(Path::toString)).containsExactly("existing.mp4");
        }
    }

    @Test
    void cancellationPreventsReadingAndPublishing() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Path result = directory.resolve("cancelled.mp4");
        assertThatThrownBy(() -> VideoFileDownloader.download((offset, count) -> {
            requests.incrementAndGet();
            return new byte[count];
        }, 1024, result, () -> true, bytes -> { }))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(requests).hasValue(0);
        assertThat(Files.exists(result)).isFalse();
    }
}
