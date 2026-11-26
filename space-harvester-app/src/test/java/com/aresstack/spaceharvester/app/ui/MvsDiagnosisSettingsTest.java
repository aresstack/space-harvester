package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings;
import com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MvsDiagnosisSettingsTest {

    @TempDir
    Path dir;

    @Test
    void withoutEntriesEveryValueHasItsDefault() {
        MvsDiagnosisSettings settings = new MvsDiagnosisSettings(new AppState(dir.resolve(AppState.FILE_NAME)));

        for (Value value : Value.values()) {
            assertThat(settings.get(value)).isEqualTo(value.defaultValue());
        }
    }

    @Test
    void aSavedValueSurvivesARestartAndADefaultIsNotStored() {
        Path file = dir.resolve(AppState.FILE_NAME);
        new MvsDiagnosisSettings(new AppState(file)).save(Map.of(Value.SAMPLE_BYTES, 32L * 1024 * 1024,
                Value.READ_BYTES, Value.READ_BYTES.defaultValue()));

        AppState restarted = new AppState(file);
        assertThat(new MvsDiagnosisSettings(restarted).get(Value.SAMPLE_BYTES)).isEqualTo(32L * 1024 * 1024);
        assertThat(restarted.get("deck.ftp.diagnosis.read-bytes")).isNull();
    }

    @Test
    void anInvalidStoredValueFallsBackToTheDefaultAndAnInvalidNewValueIsRefused() {
        AppState state = new AppState(dir.resolve(AppState.FILE_NAME));
        state.put("deck.ftp.diagnosis.budget-bytes", "viel");
        MvsDiagnosisSettings settings = new MvsDiagnosisSettings(state);

        assertThat(settings.get(Value.BUDGET_BYTES)).isEqualTo(Value.BUDGET_BYTES.defaultValue());
        assertThatThrownBy(() -> settings.save(Map.of(Value.TIMEOUT_SECONDS, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
