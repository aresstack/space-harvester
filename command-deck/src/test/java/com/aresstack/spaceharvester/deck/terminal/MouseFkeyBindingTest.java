package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.Modifier;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.MouseAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Maus → F-Taste wie in MainframeMate: Vorgaben, Zusatztasten, Merken. */
class MouseFkeyBindingTest {

    @Test
    void defaultsScrollWithF7AndF8LikeMainframeMate() {
        List<MouseFkeyBinding> defaults = MouseFkeyBinding.defaults();
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.SCROLL_UP, false, false, false)).isEqualTo(7);
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.SCROLL_DOWN, false, false, false)).isEqualTo(8);
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.SCROLL_UP, true, false, false)).isEqualTo(10);
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.SCROLL_DOWN, true, false, false)).isEqualTo(11);
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.BACK, false, false, false)).isEqualTo(3);
        // Nicht belegt bzw. andere Zusatztaste: nichts.
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.FORWARD, false, false, false)).isZero();
        assertThat(MouseFkeyBinding.fkeyFor(defaults, MouseAction.SCROLL_UP, false, true, false)).isZero();
    }

    @Test
    void modifiersMustMatchExactly() {
        assertThat(Modifier.SHIFT.matches(true, false, false)).isTrue();
        assertThat(Modifier.SHIFT.matches(true, true, false)).isFalse();
        assertThat(Modifier.CTRL_ALT.matches(false, true, true)).isTrue();
        assertThat(Modifier.NONE.matches(false, false, true)).isFalse();
    }

    @Test
    void disabledRowsTriggerNothingAndEverythingSurvivesEncoding() {
        List<MouseFkeyBinding> bindings = List.of(new MouseFkeyBinding(MouseAction.SCROLL_UP, Modifier.NONE, 7, false),
                new MouseFkeyBinding(MouseAction.FORWARD, Modifier.CTRL, 12));
        assertThat(MouseFkeyBinding.fkeyFor(bindings, MouseAction.SCROLL_UP, false, false, false)).isZero();
        assertThat(MouseFkeyBinding.decode(MouseFkeyBinding.encode(bindings))).isEqualTo(bindings);
        assertThat(MouseFkeyBinding.decode(null)).isEqualTo(MouseFkeyBinding.defaults());
        assertThat(MouseFkeyBinding.decode("")).isEmpty();
        assertThat(MouseFkeyBinding.decode("KAPUTT;SCROLL_UP:NONE:7:true")).hasSize(1);
        assertThatThrownBy(() -> new MouseFkeyBinding(MouseAction.BACK, Modifier.NONE, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
