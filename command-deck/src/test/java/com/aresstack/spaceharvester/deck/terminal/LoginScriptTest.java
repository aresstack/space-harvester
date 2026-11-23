package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Automatische Anmeldung wie in MainframeMate; tippt der Mensch selbst, tippt sie nicht dazwischen. */
class LoginScriptTest {

    /** Schein-Tastatur: merkt sich, was getippt wurde; der Test setzt die letzte Taste des Menschen. */
    private static final class Keys implements TerminalKeyboard {
        final List<String> done = new ArrayList<>();
        volatile long lastUser = Long.MIN_VALUE;
        Runnable afterClear = () -> { };

        @Override
        public boolean awaitUnlocked(Duration timeout) {
            return true;
        }

        @Override
        public long lastUserInput() {
            return lastUser;
        }

        @Override
        public void press(AidKey key) {
            done.add(key.name());
            if (key == AidKey.CLEAR) {
                afterClear.run();
            }
        }

        @Override
        public boolean firstInputField() {
            done.add("first");
            return true;
        }

        @Override
        public boolean nextInputField() {
            done.add("next");
            return true;
        }

        @Override
        public void type(char[] text) {
            done.add("type:" + text.length);
        }
    }

    private static TerminalLogin login() {
        return new TerminalLogin("TESTUSR", "geheim1".toCharArray(), "a");
    }

    @Test
    void theSequenceIsThatOfMainframeMate() throws Exception {
        Keys keys = new Keys();
        assertThat(new LoginScript(Duration.ZERO).run(keys, login(), System.nanoTime())).isEmpty();
        assertThat(keys.done).containsExactly("CLEAR", "first", "type:7", "next", "type:7", "ENTER", "type:1",
                "ENTER");
    }

    @Test
    void typingByHandStopsTheLoginBeforeTheNextStep() throws Exception {
        Keys keys = new Keys();
        long since = System.nanoTime();
        keys.afterClear = () -> keys.lastUser = System.nanoTime() + 1;
        assertThat(new LoginScript(Duration.ZERO).run(keys, login(), since)).contains(LoginScript.BY_HAND);
        assertThat(keys.done).as("nach der eigenen Taste wird nichts mehr getippt").containsExactly("CLEAR");
    }
}
