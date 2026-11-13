package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Schlüsselform von {@link DocumentKey} (ADR 0026, Nachtrag Stufe 4): Schlüssel, Dateiname, Ablageort, Hash. */
class DocumentKeyTest {

    private static final PageKey PAGE = new PageKey("wiki", "100");
    private static final String OUTLOOK = "AAMkAGI2T/G+x=Ünïcode .. \\ C:";
    private static final PartId.Content CONTENT = new PartId.Content("ab".repeat(32), 2);
    private static final PartId.Position POSITION = new PartId.Position("cd".repeat(32), "/1/3");

    @Test
    void thePageKeepsItsPlaceAndItsName() {
        DocumentKey page = DocumentKey.page(PAGE);

        assertThat(page.value()).isEqualTo("wiki\u0000100");
        assertThat(page.fileName()).isEmpty();
        assertThat(page.storagePath()).isEmpty();
        assertThat(page.hash()).isEqualTo(sha256("wiki\n100"));
    }

    @Test
    void anAttachmentIsNamedByTheHashOfItsIdNeverByTheIdItself() {
        DocumentKey attachment = DocumentKey.attachment(PAGE, OUTLOOK);

        assertThat(attachment.value()).isEqualTo("wiki\u0000100\u0000" + OUTLOOK);
        assertThat(attachment.fileName()).isEqualTo("a-" + sha256(OUTLOOK)).matches("[0-9a-z-]+");
        assertThat(attachment.storagePath()).isEqualTo("documents/a-" + sha256(OUTLOOK));
        assertThat(attachment.hash()).isEqualTo(sha256("wiki\n100\n" + OUTLOOK));
    }

    @Test
    void anEnclosedFileAddsItsPartId() {
        DocumentKey content = DocumentKey.attachment(PAGE, OUTLOOK).child(CONTENT);
        DocumentKey position = DocumentKey.attachment(PAGE, OUTLOOK).child(POSITION);

        assertThat(content.value()).isEqualTo("wiki\u0000100\u0000" + OUTLOOK + "\u0000" + CONTENT.value());
        assertThat(content.fileName()).isEqualTo("a-" + sha256(OUTLOOK) + "-" + CONTENT.fileName())
                .matches("[0-9a-z-]+");
        assertThat(position.fileName()).matches("[0-9a-z-]+");
        assertThat(content.storagePath())
                .isEqualTo("documents/a-" + sha256(OUTLOOK) + "/parts/" + CONTENT.fileName())
                .matches("[0-9a-z/-]+");
        assertThat(content.hash()).isEqualTo(sha256("wiki\n100\n" + OUTLOOK + "\n" + CONTENT.value()));
        // Ohne Anhang (Quelle files): die Teilkennung im Behälter Seite.
        DocumentKey inPage = DocumentKey.page(PAGE).child(CONTENT);
        assertThat(inPage.storagePath()).isEqualTo("documents/parts/" + CONTENT.fileName());
        assertThat(inPage.hash()).isEqualTo(sha256("wiki\n100\n" + CONTENT.value()));
    }

    @Test
    void differentDocumentsNeverShareAKeyANameOrAHash() {
        DocumentKey a = DocumentKey.attachment(PAGE, "a");
        DocumentKey b = DocumentKey.attachment(PAGE, "b");
        DocumentKey part = a.child(CONTENT);

        assertThat(java.util.List.of(a.value(), b.value(), part.value())).doesNotHaveDuplicates();
        assertThat(java.util.List.of(a.fileName(), b.fileName(), part.fileName())).doesNotHaveDuplicates();
        assertThat(java.util.List.of(a.hash(), b.hash(), part.hash(), DocumentKey.page(PAGE).hash()))
                .doesNotHaveDuplicates();
    }

    @Test
    void controlCharactersInTheAttachmentIdAreRefused() {
        assertThatThrownBy(() -> DocumentKey.attachment(PAGE, "a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentKey.attachment(PAGE, "a\u0000b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
