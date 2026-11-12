package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** „Größter Anhang“ zeigt bei 0 nicht „0 B“, sondern „ohne Grenze“ (#571). */
class SizeLimitTextTest {

    @Test
    void zeroMeansNoLimit() {
        assertThat(Texts.sizeLimit(0)).isEqualTo("ohne Grenze");
        assertThat(Texts.sizeLimit(-1)).isEqualTo("ohne Grenze");
        assertThat(Texts.sizeLimit(100L * 1024 * 1024)).isEqualTo(Texts.bytes(100L * 1024 * 1024));
    }
}
