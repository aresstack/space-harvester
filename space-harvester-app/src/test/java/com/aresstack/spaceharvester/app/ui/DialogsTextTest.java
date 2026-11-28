package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Textabfragen: Eine Pflichtangabe gibt „Übernehmen“ erst mit Text frei; eine Abfrage, deren Hinweis „leer lassen“
 * anbietet ({@link Dialogs#askOptionalText}), nimmt das leere Feld an (Angelo 28.11.: Startadresse leer ging nicht).
 */
class DialogsTextTest {

    @Test
    void anOptionalTextCanBeLeftEmptyARequiredOneNot() {
        assertThat(Dialogs.accepts("", true)).isTrue();
        assertThat(Dialogs.accepts("   ", true)).isTrue();
        assertThat(Dialogs.accepts("", false)).isFalse();
        assertThat(Dialogs.accepts("  ", false)).isFalse();
        assertThat(Dialogs.accepts("Anmelden", false)).isTrue();
    }
}
