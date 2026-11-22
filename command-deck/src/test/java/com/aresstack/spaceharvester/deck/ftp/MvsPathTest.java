package com.aresstack.spaceharvester.deck.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Dieselben Regeln wie MainframeMates {@code MvsPathDialectTest}, dazu der Weg nach oben. */
class MvsPathTest {

    @Test
    void rootAndQuoting() {
        assertThat(MvsPath.absolute("/")).isEqualTo("''");
        assertThat(MvsPath.absolute("")).isEqualTo("''");
        assertThat(MvsPath.absolute("ABC.DEF")).isEqualTo("'ABC.DEF'");
        assertThat(MvsPath.absolute("ABC/DEF")).isEqualTo("'ABC.DEF'");
        assertThat(MvsPath.absolute("'ABC.DEF'")).isEqualTo("'ABC.DEF'");
    }

    @Test
    void members() {
        assertThat(MvsPath.absolute("ABC.DEF(GHIJ)")).isEqualTo("'ABC.DEF(GHIJ)'");
        assertThat(MvsPath.isMember("'ABC.DEF(GHIJ)'")).isTrue();
        assertThat(MvsPath.splitMember("'ABC.DEF(GHIJ)'")).containsExactly("ABC.DEF", "GHIJ");
        assertThat(MvsPath.isMember("ABC.DEF")).isFalse();
        assertThatThrownBy(() -> MvsPath.splitMember("ABC.DEF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dotMemberNotationTriesParenthesesFirst() {
        assertThat(MvsPath.candidates("ABC.DEF.GHIJ")).containsExactly("'ABC.DEF(GHIJ)'", "'ABC.DEF.GHIJ'");
        assertThat(MvsPath.candidates("'ABC.DEF'")).containsExactly("'ABC.DEF'");
    }

    @Test
    void children() {
        assertThat(MvsPath.child("'ABC.DEF'", "MEM")).isEqualTo("'ABC.DEF(MEM)'");
        assertThat(MvsPath.child("'ABC'", "DEF")).isEqualTo("'ABC.DEF'");
        assertThat(MvsPath.child("'ABC'", "ABC.DEF.GHI")).isEqualTo("'ABC.DEF.GHI'");
        assertThat(MvsPath.child("'ABC.DEF'", "VERY.LONG")).isEqualTo("'VERY.LONG'");
    }

    @Test
    void parents() {
        assertThat(MvsPath.parent("'ABC.DEF(MEM)'")).isEqualTo("'ABC.DEF'");
        assertThat(MvsPath.parent("'ABC.DEF'")).isEqualTo("'ABC'");
        assertThat(MvsPath.parent("'ABC'")).isEqualTo("''");
    }
}
