package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.nlp.ParserModelInstaller;
import com.aresstack.spaceharvester.nlp.malt.GermanParserModelPin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserModelSetupTest {

    @TempDir
    Path data;

    private final ParserModelSetup.Runner direct = new ParserModelSetup.Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    };

    @Test
    void withoutPinnedModelTheSetupIsUnavailable() {
        ParserModelSetup setup = new ParserModelSetup(Optional::empty, direct);
        assertThat(setup.status().state()).isEqualTo(ParserModelSetup.State.UNAVAILABLE);
        assertThat(setup.releasePage()).isEmpty();
    }

    @Test
    void failedDownloadIsReportedAndKeepsNotInstalled() {
        GermanParserModelPin.Release release = new GermanParserModelPin.Release("aresstack/space-harvester",
                "maltparser-de-gsd-v1.0.0", "maltparser-de-gsd-v1.0.0.zip", "0".repeat(64));
        ParserModelInstaller installer = new ParserModelInstaller(data.resolve("m"), release, target -> {
            throw new IOException("offline");
        });
        ParserModelSetup setup = new ParserModelSetup(() -> Optional.of(installer), direct);
        List<ParserModelSetup.State> states = new ArrayList<>();
        setup.listen(status -> states.add(status.state()));

        setup.install();

        // Schrittmeldungen kommen über invokeLater; der Test läuft nicht auf dem EDT, daher nur Anfang und Ende.
        assertThat(states).startsWith(ParserModelSetup.State.NOT_INSTALLED, ParserModelSetup.State.INSTALLING)
                .endsWith(ParserModelSetup.State.NOT_INSTALLED);
        assertThat(setup.status().failed()).isTrue();
        assertThat(setup.status().message()).contains("Installation fehlgeschlagen");
        assertThat(setup.releasePage()).contains(release.page());
    }

    @Test
    void successfulInstallReportsInstalled() throws Exception {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(raw)) {
            out.putNextEntry(new java.util.zip.ZipEntry("model-manifest.json"));
            out.write("{}".getBytes());
            out.putNextEntry(new java.util.zip.ZipEntry("m.mco"));
            out.write("x".getBytes());
        }
        byte[] zip = raw.toByteArray();
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(zip));
        GermanParserModelPin.Release release = new GermanParserModelPin.Release("aresstack/space-harvester",
                "maltparser-de-gsd-v1.0.0", "maltparser-de-gsd-v1.0.0.zip", sha);
        ParserModelInstaller installer = new ParserModelInstaller(data.resolve("m"), release,
                target -> new ParserModelInstaller.Body(new ByteArrayInputStream(zip), zip.length));
        ParserModelSetup setup = new ParserModelSetup(() -> Optional.of(installer), direct);

        setup.install();

        assertThat(setup.status().state()).isEqualTo(ParserModelSetup.State.INSTALLED);
        assertThat(setup.status().failed()).isFalse();
    }
}
