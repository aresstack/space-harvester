package com.aresstack.spaceharvester.application.configuration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportSettingsVideoTest {

    @Test
    void excludesVideoAttachmentsWithoutSuppressingOfficeDocumentsByDefault() {
        assertThat(ExportSettings.DEFAULT.permits("video/mp4")).isFalse();
        assertThat(ExportSettings.DEFAULT.permits("video/webm")).isFalse();
        assertThat(ExportSettings.DEFAULT.permits("application/pdf")).isTrue();
        assertThat(ExportSettings.DEFAULT.permits(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation")).isTrue();
    }

    @Test
    void permitsVideoOnlyAfterExplicitMediaTypeOptIn() {
        List<String> accepted = new ArrayList<>(ExportSettings.DEFAULT_MEDIA_TYPES);
        accepted.add("video/*");
        ExportSettings backup = new ExportSettings(true, accepted, ExportSettings.DEFAULT_MAX_SIZE);

        assertThat(backup.permits("video/mp4")).isTrue();
        assertThat(backup.permits("video/webm")).isTrue();
        assertThat(ExportSettings.DEFAULT.permits("video/mp4")).isFalse();
    }
}
