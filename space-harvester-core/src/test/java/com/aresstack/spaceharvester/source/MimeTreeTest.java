package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Der Baum der Grenzen einer Mail (AP7a): verschachtelt, gefaltete Köpfe, lange Zeilen. */
class MimeTreeTest {

    @Test
    void nestedBoundariesKnowTheirAlternative() throws IOException {
        String mail = "Subject: x\r\nContent-Type: multipart/mixed;\r\n\tboundary=\"M\"\r\n\r\n"
                + "--M\r\nContent-Type: multipart/alternative; boundary=A\r\n\r\n"
                + "--A\r\nContent-Type: text/plain\r\n\r\n" + "x".repeat(5_000) + "\r\n"
                + "--A\r\nContent-Type: multipart/related; boundary=\"R\"\r\n\r\n"
                + "--R\r\nContent-Type: text/html\r\n\r\n<p>h</p>\r\n--R--\r\n--A--\r\n"
                + "--M\r\nContent-Type: multipart/related; boundary=\"S\"\r\n\r\n--S\r\n\r\nt\r\n--S--\r\n--M--\r\n";

        MimeTree tree = MimeTree.scan(new ByteArrayInputStream(mail.getBytes(StandardCharsets.ISO_8859_1)));

        assertThat(tree.childBelow("A", "R")).isEqualTo("R");
        assertThat(tree.childBelow("M", "R")).isEqualTo("A");
        assertThat(tree.childBelow("A", "S")).isNull();
        assertThat(tree.alternativeAbove("R")).isEqualTo("A");
        assertThat(tree.alternativeAbove("S")).isNull();
        assertThat(tree.childBelow("A", "unbekannt")).isNull();
    }
}
