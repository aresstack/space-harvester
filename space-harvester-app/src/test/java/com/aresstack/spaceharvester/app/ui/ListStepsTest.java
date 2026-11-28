package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.ListSteps.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Schritt von „Weitere laden“ ist einstellbar statt fest 120 (Angelo: keine fest eingebauten Werte). */
class ListStepsTest {

    @TempDir
    Path dir;

    @Test
    void theDefaultIsTheFormerFixedValueAndAChangeIsReadOnTheNextUse() {
        AppState state = new AppState(dir.resolve(AppState.FILE_NAME));
        assertThat(new ListSteps(state).get(Value.TERMS)).isEqualTo(120);

        new ListSteps(state).save(Map.of(Value.TERMS, 40L));
        assertThat(new ListSteps(state).get(Value.TERMS)).isEqualTo(40);
        assertThat(state.get("app.lists.terms-step")).isEqualTo("40");

        new ListSteps(state).save(Map.of(Value.TERMS, 120L));
        assertThat(state.get("app.lists.terms-step")).as("die Vorgabe wird nicht gemerkt").isNull();
        assertThatThrownBy(() -> new ListSteps(state).save(Map.of(Value.TERMS, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
