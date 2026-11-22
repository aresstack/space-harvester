package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Die Fälle aus MainframeMates {@code RecordStructureCodecTest} mit den dortigen Vorgaben. */
class RecordStructureTest {

    private static byte[] bytes(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : parts) {
            switch (part) {
                case "<R>" -> out.writeBytes(RecordStructure.RECORD_END);
                case "<E>" -> out.writeBytes(RecordStructure.FILE_END);
                default -> out.writeBytes(part.getBytes(ISO_8859_1));
            }
        }
        return out.toByteArray();
    }

    @Test
    void recordEndsBecomeLinesAndFileEndAndLastNewlineDisappear() {
        assertThat(RecordStructure.decode(bytes("LINE1", "<R>", "LINE2", "<R>", "LINE3", "<R>", "<E>"), ISO_8859_1))
                .isEqualTo("LINE1\nLINE2\nLINE3");
    }

    @Test
    void paddingAndCarriageReturnsAreStripped() {
        assertThat(RecordStructure.decode(bytes("HELLO     ", "<R>", "WORLD\r", "<R>", "<E>"), ISO_8859_1))
                .isEqualTo("HELLO\nWORLD");
        assertThat(RecordStructure.decode(bytes("HELLO WORLD  ", "<R>"), ISO_8859_1)).isEqualTo("HELLO WORLD");
        assertThat(RecordStructure.withoutPadding(new byte[] {'A', 0, 'B', 0})).containsExactly('A', 'B');
    }

    @Test
    void encodeAddsRecordEndAfterEachLineAndFileEnd() throws Exception {
        assertThat(RecordStructure.encode("LINE1  \nLINE2\r", ISO_8859_1))
                .containsExactly(bytes("LINE1", "<R>", "LINE2", "<R>", "<E>"));
    }

    @Test
    void roundTrip() throws Exception {
        String text = "//JOB1 JOB\n//S1 EXEC PGM=IEFBR14\nGrüße";
        assertThat(RecordStructure.decode(RecordStructure.encode(text, ISO_8859_1), ISO_8859_1)).isEqualTo(text);
        assertThat(RecordStructure.decode(new byte[0], ISO_8859_1)).isEmpty();
    }

    @Test
    void charactersOutsideTheCharsetAreReportedNotReplaced() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RecordStructure.encode("//JOB1 JOB\nPreis 5 €", ISO_8859_1))
                .isInstanceOf(com.aresstack.spaceharvester.deck.ftp.TextEncodingException.class)
                .hasMessageContaining("„€“").hasMessageContaining("Zeile 2").hasMessageContaining("Spalte 9")
                .hasMessageContaining("ISO-8859-1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RecordStructure.text("a\n€", ISO_8859_1))
                .isInstanceOf(com.aresstack.spaceharvester.deck.ftp.TextEncodingException.class)
                .hasMessageContaining("Zeile 2");
    }
}
