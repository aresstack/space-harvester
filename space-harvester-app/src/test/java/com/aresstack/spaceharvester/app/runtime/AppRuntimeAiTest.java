package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppRuntimeAiTest {

    @Test
    void theRerankModelReachesTheCoreOnlyWhenSet() {
        AppSettings.AiSettings with = new AppSettings.AiSettings("https://ki.firma.de/v1", null,
                "text-embedding-3-small", 1536, null, List.of(), "bge-reranker", null, null);
        AppSettings.AiSettings without = new AppSettings.AiSettings("https://ki.firma.de/v1", null,
                "text-embedding-3-small", 1536, null, List.of());

        assertThat(AppRuntime.aiService(with, "key", "test").rerankModel()).isEqualTo("bge-reranker");
        assertThat(AppRuntime.aiService(without, "key", "test").rerankModel()).isNull();
        assertThat(AppRuntime.aiService(AppSettings.AiSettings.NONE, null, "test")).isEqualTo(AiServiceSettings.NONE);
    }

    @Test
    void theVisionModelReachesTheCoreOnlyWhenSet() {
        AppSettings.AiSettings with = new AppSettings.AiSettings("https://ki.firma.de/v1", null, null, null, null,
                List.of(), null, null, null, "gpt-4o-mini");
        AppSettings.AiSettings without = new AppSettings.AiSettings("https://ki.firma.de/v1", null, null, null, null,
                List.of());

        assertThat(AppRuntime.aiService(with, "key", "test").visionModel()).isEqualTo("gpt-4o-mini");
        assertThat(AppRuntime.aiService(without, "key", "test").visionModel()).isNull();
    }

    @Test
    void theSetupWizardMarkerAloneNeedsNoRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        // Nur die Oberfläche liest den Merker; nach dem Assistenten darf eine Änderung ohne Rückfrage (Frist,
        // Synchronisierung) die Quellen nicht still neu starten.
        AppSettings before = new com.aresstack.spaceharvester.app.settings.AppSettingsLoader(
                new com.aresstack.spaceharvester.app.settings.Placeholders(name -> null), home)
                .load(home.resolve("fehlt.yml"));
        assertThat(before.setupCompleted()).isFalse();

        assertThat(AppRuntime.liveChange(before, before.withSetupCompleted())).isTrue();
    }

    @Test
    void parallelLimitsChangeLiveWithoutARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        // Die Auswahl in der Auftragsliste ändert die Grenzen sofort; eine spätere Live-Änderung (Frist o. Ä.) darf
        // die Laufzeit deshalb nicht still neu starten.
        AppSettings before = new com.aresstack.spaceharvester.app.settings.AppSettingsLoader(
                new com.aresstack.spaceharvester.app.settings.Placeholders(name -> null), home)
                .load(home.resolve("fehlt.yml"));
        AppSettings after = before.withParallel(3, 0);

        assertThat(after.jobLimits().parallelPreparations()).isEqualTo(3);
        assertThat(after.jobLimits().parallelDownloads()).isZero();
        assertThat(AppRuntime.liveChange(before, after)).isTrue();
        assertThat(AppRuntime.liveChange(after, after.withSetupCompleted())).isTrue();
        // Das Verbindungsbudget rechts neben „Downloads“ gilt ebenso live (Angelo 11.11.2026).
        AppSettings closed = after.withParallel(3, 2, 0);
        assertThat(closed.jobLimits().downloadConnections()).isZero();
        assertThat(AppRuntime.liveChange(after, closed)).isTrue();
    }
}
