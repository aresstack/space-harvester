package com.aresstack.spaceharvester;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * #499 / ADR 0024: Der Microservice bleibt unverändert, MaltParser läuft nur in der Desktop-App. Weder das Modul
 * {@code :nlp-parser} noch MaltParser selbst dürfen auf dem Klassenpfad des Dienstes liegen, auch nicht über eine
 * transitive Abhängigkeit.
 */
class NoParserInServerTest {

    @Test
    void neitherTheParserModuleNorMaltParserIsOnTheServerClasspath() {
        assertThatThrownBy(() -> Class.forName("com.aresstack.spaceharvester.nlp.malt.MaltModelLoader"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.aresstack.spaceharvester.nlp.malt.MaltDependencyParser"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.maltparser.Malt")).isInstanceOf(ClassNotFoundException.class);
    }
}
