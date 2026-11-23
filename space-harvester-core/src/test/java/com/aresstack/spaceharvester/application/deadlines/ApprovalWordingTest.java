package com.aresstack.spaceharvester.application.deadlines;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelos Entscheidungstabelle vom 23.11.2026 auf Wortlautebene; die Sätze sind erfunden. */
class ApprovalWordingTest {

    @Test
    void aBindingExtensionNeedsNoGrantWord() {
        assertThat(ApprovalWording.namesGrant("Die Frist wird bis zum 30.06.2028 verlängert.")).isTrue();
        assertThat(ApprovalWording.namesGrant("Die Ausgleichsfrist für das Konto 4711 wird hiermit verlängert."))
                .isTrue();
        assertThat(ApprovalWording.namesGrant("Die Frist wird bis zum 30. Juni 2028 verlängert.")).isTrue();
        assertThat(ApprovalWording.namesGrant("Hiermit verlängere ich die Frist bis Ende 2028.")).isTrue();
        assertThat(ApprovalWording.namesGrant("Der Verlängerung wird zugestimmt.")).isTrue();
        assertThat(ApprovalWording.namesGrant("Die Verlängerung wird genehmigt.")).isTrue();
    }

    @Test
    void aRequestOrAPossibilityIsNoGrant() {
        assertThat(ApprovalWording.namesGrant("Die Verlängerung wurde beim BMF beantragt.")).isFalse();
        assertThat(ApprovalWording.namesRequest("Die Verlängerung wurde beim BMF beantragt.")).isTrue();
        assertThat(ApprovalWording.namesGrant("Eine Verlängerung ist möglich.")).isFalse();
        assertThat(ApprovalWording.namesGrant("Die Frist kann auf Antrag verlängert werden.")).isFalse();
        assertThat(ApprovalWording.namesGrant("Die Frist soll nicht weiter verlängert werden.")).isFalse();
        assertThat(ApprovalWording.namesGrant("Auf Antrag wird die Frist verlängert.")).isFalse();
        assertThat(ApprovalWording.namesGrant(null)).isFalse();
    }

    @Test
    void theBindingWordsMustStayInOneSentence() {
        assertThat(ApprovalWording.namesGrant("Die Frist wird geprüft. Sie wurde bisher nie verlängert.")).isFalse();
    }
}
