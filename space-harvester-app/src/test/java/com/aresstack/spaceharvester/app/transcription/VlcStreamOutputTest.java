package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The VLC option chain must keep Windows temporary paths literal (spaces, backslashes, commas, braces). */
final class VlcStreamOutputTest {

    @Test
    void quotesWindowsPathsLiterally() {
        assertThat(VlcPcmAudioDecoder.quote("C:\\Users\\Max Muster\\AppData\\Local\\Temp\\a,b{c}.s16"))
                .isEqualTo("'C:\\Users\\Max Muster\\AppData\\Local\\Temp\\a,b{c}.s16'");
    }

    @Test
    void escapesAPathThatContainsASingleQuote() {
        assertThat(VlcPcmAudioDecoder.quote("C:\\Users\\O'Neil\\x.s16"))
                .isEqualTo("\"C:\\\\Users\\\\O'Neil\\\\x.s16\"");
    }
}
