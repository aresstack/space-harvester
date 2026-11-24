package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.deck.eakte.http.FakeOutlookMessage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** E-Mails im Anhang-Viewer: Outlook (.msg) und .eml mit Kopf, Text und Anlagen; alle Inhalte erfunden. */
class MailMessageTest {

    @Test
    void outlookMessageHasHeaderTextAndThePdfAttachment() throws Exception {
        MailMessage mail = MailMessage.read("Erlass.msg", FakeOutlookMessage.build(0));
        assertThat(mail.subject()).isEqualTo(FakeOutlookMessage.SUBJECT);
        assertThat(mail.from()).isEqualTo(FakeOutlookMessage.FROM);
        assertThat(mail.to()).isEqualTo(FakeOutlookMessage.TO);
        assertThat(mail.text()).contains("Frachtraum 7");
        assertThat(mail.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo(FakeOutlookMessage.ATTACHMENT);
            assertThat(attachment.mediaType()).isEqualTo("application/pdf");
            assertThat(attachment.data()).isEqualTo(FakeOutlookMessage.PDF);
            assertThat(attachment.asReaderAttachment().kind()).isEqualTo(ReaderAttachmentKind.PDF);
        });
        WordDocumentPreview.Rendered page = mail.render();
        assertThat(page.html()).contains("Erlass Frachtraum")
                .contains(MailMessage.ATTACHMENT_LINK + "0").contains(FakeOutlookMessage.ATTACHMENT);
        assertThat(page.text()).contains("Von: " + FakeOutlookMessage.FROM).contains("Anlagen: "
                + FakeOutlookMessage.ATTACHMENT);
    }

    @Test
    void emlWithHtmlInlineImageAndAttachment() throws Exception {
        MailMessage mail = MailMessage.read("Weiterleitung.eml", eml());
        assertThat(mail.subject()).isEqualTo("Erlass für Frachtraum 7");
        assertThat(mail.from()).isEqualTo("Kapitänin Avenger <kapitaenin@example.invalid>");
        assertThat(mail.to()).isEqualTo("ladung@example.invalid");
        assertThat(mail.date()).isNotEmpty();
        // Das eingebettete Bild steht im Text, nicht in der Anlagenliste.
        assertThat(mail.attachments()).extracting(MailMessage.Attachment::name).containsExactly("Erlass für 7.pdf");
        WordDocumentPreview.Rendered page = mail.render();
        assertThat(page.images()).hasSize(1);
        assertThat(page.html()).contains(MailMessage.IMAGE_BASE + "0").doesNotContain("<script")
                .doesNotContain("example.invalid/tracker");
        assertThat(mail.text()).contains("Bitte prüfen");
    }

    @Test
    void writtenAsEmlItReadsBackTheSame() throws Exception {
        MailMessage mail = MailMessage.read("Erlass.msg", FakeOutlookMessage.build(0));
        MailMessage again = MailMessage.read("Erlass.eml", mail.toEml());
        assertThat(again.subject()).isEqualTo(mail.subject());
        assertThat(again.from()).isEqualTo(mail.from());
        assertThat(again.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo(FakeOutlookMessage.ATTACHMENT);
            assertThat(attachment.data()).isEqualTo(FakeOutlookMessage.PDF);
        });
    }

    @Test
    void recognisesMailsByExtensionOrMediaType() {
        assertThat(ReaderAttachmentKind.of("", "a.msg")).isEqualTo(ReaderAttachmentKind.MAIL);
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "a.EML")).isEqualTo(ReaderAttachmentKind.MAIL);
        assertThat(ReaderAttachmentKind.of("message/rfc822", "ohne Endung")).isEqualTo(ReaderAttachmentKind.MAIL);
        assertThat(ReaderAttachmentKind.label("", "a.msg")).isEqualTo("Outlook-Nachricht");
        assertThat(AttachmentViewerPanel.showsFormatted(new ReaderAttachment("k", "a.eml", null, 1))).isTrue();
    }

    static byte[] eml() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        String pdf = Base64.getMimeEncoder().encodeToString(FakeOutlookMessage.PDF);
        String text = "From: =?UTF-8?Q?Kapit=C3=A4nin_Avenger?= <kapitaenin@example.invalid>\r\n"
                + "To: ladung@example.invalid\r\n"
                + "Subject: =?UTF-8?Q?Erlass_f=C3=BCr_Frachtraum_7?=\r\n"
                + "Date: Tue, 24 Nov 2026 08:15:00 +0100\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"aussen\"\r\n\r\n"
                + "--aussen\r\n"
                + "Content-Type: multipart/related; boundary=\"innen\"\r\n\r\n"
                + "--innen\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\n"
                + "<html><head><style>p{color:red}</style><script>alert(1)</script></head><body>"
                + "<p>Bitte prüfen.</p><img src=\"cid:logo@example.invalid\">"
                + "<img src=\"https://example.invalid/tracker.png\"></body></html>\r\n"
                + "--innen\r\n"
                + "Content-Type: image/png\r\n"
                + "Content-ID: <logo@example.invalid>\r\n"
                + "Content-Disposition: inline; filename=\"logo.png\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(png.toByteArray()) + "\r\n"
                + "--innen--\r\n"
                + "--aussen\r\n"
                + "Content-Type: application/pdf; name=\"=?UTF-8?Q?Erlass_f=C3=BCr_7.pdf?=\"\r\n"
                + "Content-Disposition: attachment; filename=\"=?UTF-8?Q?Erlass_f=C3=BCr_7.pdf?=\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + pdf + "\r\n"
                + "--aussen--\r\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
