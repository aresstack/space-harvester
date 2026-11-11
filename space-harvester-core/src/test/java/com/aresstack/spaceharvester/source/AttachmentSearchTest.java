package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Anhangskatalog vieler Seiten über die Confluence-Suche statt eines Abrufs je Seite (Angelo 11.11.2026). */
class AttachmentSearchTest {

    private final FakeConfluence confluence = new FakeConfluence();

    AttachmentSearchTest() {
        confluence.space("DEV", "Entwicklung");
        confluence.space("OPS", "Betrieb");
        confluence.page("100", "Start", "DEV", "<p>S</p>");
        confluence.homepage("DEV", "100");
        confluence.page("101", "Planung", "DEV", "<p>P</p>");
        confluence.page("102", "Notizen", "DEV", "<p>N</p>");
        confluence.child("100", "101");
        confluence.child("100", "102");
        confluence.attachment("att1", "101", "bild.png", "image/png", "PNG");
        confluence.attachment("att2", "101", "handbuch.pdf", "application/pdf", "PDF");
        confluence.attachment("att3", "102", "notiz.txt", "text/plain", "Notiz");
    }

    @Test
    void oneSearchListsTheAttachmentsOfManyPagesLikeThePerPageList() {
        HarvestSource source = source();

        Map<String, PageAttachments> lists = source.attachmentsOf(List.of("100", "101", "102"), "DEV");

        assertThat(lists.keySet()).containsExactly("100", "101", "102");
        assertThat(lists.get("100").attachments()).isEmpty();
        assertThat(ids(lists.get("101"))).isEqualTo(ids(source.attachments("101")));
        assertThat(ids(lists.get("102"))).containsExactly("att3");
        // Die Rohantworten der Suche gehören zu vielen Seiten und werden nicht je Seite gehalten.
        assertThat(lists.get("101").responses()).isEmpty();
        assertThat(confluence.requestCount("/child/attachment")).isEqualTo(1);
    }

    @Test
    void aPageMovedToAnotherSpaceHasAnEmptyList() {
        confluence.move("102", "OPS");

        Map<String, PageAttachments> lists = source().attachmentsOf(List.of("101", "102"), "DEV");

        assertThat(ids(lists.get("101"))).containsExactly("att1", "att2");
        assertThat(lists.get("102").attachments()).isEmpty();
    }

    @Test
    void aSourceWithoutSearchIsUnsupported() {
        HarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource("x"));

        assertThatThrownBy(() -> source.attachmentsOf(List.of("101"), "DEV"))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    private HarvestSource source() {
        ConfluenceKnowledgeSource port = new ConfluenceKnowledgeSource(SourceAddress.of("wiki"),
                ConfluenceConfig.builder(FakeConfluence.BASE).build(), confluence, null);
        return new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port);
    }

    private static List<String> ids(PageAttachments list) {
        return list.attachments().stream().map(PageAttachment::attachmentId).toList();
    }
}
