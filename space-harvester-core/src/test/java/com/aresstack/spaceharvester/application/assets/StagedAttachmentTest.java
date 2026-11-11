package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagedAttachmentTest {

    @TempDir
    Path directory;

    @Test
    void writesTheFileAndCountsHashSizeAndHeadOnTheWay() throws IOException {
        Path file = Files.createFile(directory.resolve("a.part"));
        byte[] content = new byte[StagedAttachment.HEAD_BYTES + 10];
        Arrays.fill(content, (byte) 'x');
        content[0] = '%';

        StagedAttachment sink = new StagedAttachment(file, 1_000_000);
        sink.write(content, 0, 100);
        sink.write(content, 100, content.length - 100);
        sink.close();

        assertThat(Files.readAllBytes(file)).isEqualTo(content);
        assertThat(sink.size()).isEqualTo(content.length);
        assertThat(sink.sha256()).isEqualTo(Assets.sha256(content));
        assertThat(sink.head()).isEqualTo(Arrays.copyOf(content, StagedAttachment.HEAD_BYTES));
        assertThat(sink.exceeded()).isFalse();
        assertThat(sink.failure()).isNull();
    }

    @Test
    void aRestartDropsEverythingWrittenBefore() throws IOException {
        Path file = Files.createFile(directory.resolve("a.part"));
        StagedAttachment sink = new StagedAttachment(file, 1000);
        sink.write("abgebrochener Versuch".getBytes(StandardCharsets.UTF_8));

        sink.restart();
        sink.write("Bericht".getBytes(StandardCharsets.UTF_8));
        sink.close();

        assertThat(Files.readString(file)).isEqualTo("Bericht");
        assertThat(sink.size()).isEqualTo(7);
        assertThat(sink.sha256()).isEqualTo(Assets.sha256("Bericht".getBytes(StandardCharsets.UTF_8)));
        assertThat(sink.head()).isEqualTo("Bericht".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void writingPastTheLimitFailsWithoutTruncatingAndSaysSo() throws IOException {
        Path file = Files.createFile(directory.resolve("a.part"));
        StagedAttachment sink = new StagedAttachment(file, 3);
        sink.write("abc".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> sink.write('d')).isInstanceOf(IOException.class);

        assertThat(sink.exceeded()).isTrue();
        assertThat(sink.failure()).as("kein Fehler der Platte").isNull();
        assertThat(sink.size()).isEqualTo(3);
    }

    @Test
    void aFullDiskIsRememberedAsALocalFailure() throws IOException {
        Path full = Path.of("/dev/full");
        assumeTrue(Files.isWritable(full), "nur wo es /dev/full gibt");
        StagedAttachment sink = new StagedAttachment(full, 1000);

        assertThatThrownBy(() -> sink.write("abc".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);

        assertThat(sink.failure()).isNotNull();
        assertThat(sink.exceeded()).isFalse();
        sink.close();
    }

    @Test
    void theHashIsOnlyThereOnceTheFileIsComplete() throws IOException {
        Path file = Files.createFile(directory.resolve("a.part"));
        StagedAttachment sink = new StagedAttachment(file, 10);

        assertThatThrownBy(sink::sha256).isInstanceOf(IllegalStateException.class);
        sink.close();
        assertThat(sink.sha256()).isEqualTo(Assets.sha256(new byte[0]));
    }
}
