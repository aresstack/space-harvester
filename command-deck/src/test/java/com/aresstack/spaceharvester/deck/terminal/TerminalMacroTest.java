package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Aufzeichnen und Abspielen wie TerminalMacroRecorder/-Player in MainframeMate. */
class TerminalMacroTest {

    @Test
    void textIsMergedUntilAnAidKey() {
        TerminalMacro macro = new TerminalMacro();
        macro.typed("h");
        macro.typed("allo");
        macro.aid(AidKey.ENTER);
        macro.aid(AidKey.PF3);
        macro.typed("x");

        assertThat(macro.steps()).containsExactly(TerminalMacro.Step.text("hallo"),
                TerminalMacro.Step.aid(AidKey.ENTER), TerminalMacro.Step.aid(AidKey.PF3),
                TerminalMacro.Step.text("x"));
        assertThat(macro.steps()).extracting(TerminalMacro.Step::display)
                .containsExactly("\"hallo\"", "⏎ Enter", "F3", "\"x\"");
        macro.clear();
        assertThat(macro.steps()).isEmpty();
        macro.aid(AidKey.CLEAR);
        assertThat(macro.steps()).extracting(TerminalMacro.Step::display).containsExactly("⌧ Clear");
    }

    @Test
    void playerTypesAndPressesInOrder() throws Exception {
        List<String> done = new ArrayList<>();
        TerminalKeyboard keyboard = new TerminalKeyboard() {
            @Override
            public boolean awaitUnlocked(Duration timeout) {
                return true;
            }

            @Override
            public void press(AidKey key) {
                done.add(key.name());
            }

            @Override
            public boolean firstInputField() {
                return true;
            }

            @Override
            public boolean nextInputField() {
                return true;
            }

            @Override
            public void type(char[] text) {
                done.add(new String(text));
            }
        };
        assertThat(new MacroPlayer(() -> Duration.ZERO).play(keyboard, List.of(TerminalMacro.Step.text("abc"),
                new TerminalMacro.Step(TerminalMacro.Step.Kind.AID, "UNBEKANNT"), TerminalMacro.Step.aid(AidKey.PF8))))
                .isEmpty();
        assertThat(done).containsExactly("abc", "PF8");
    }
}
