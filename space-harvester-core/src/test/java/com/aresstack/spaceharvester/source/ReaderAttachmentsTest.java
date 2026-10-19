package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import org.junit.jupiter.api.Test;

/**
 * Mehr Anhänge, als die vollständige Liste fasst: wie der Lesebereich des Enterprise AI Client
 * ({@code ConfluenceKnowledgeSource.readerAttachments}) die ersten 500 statt eines Fehlers.
 */
class ReaderAttachmentsTest {

    private final FakeConfluence confluence = new FakeConfluence();

    private KnowledgeSourceHarvestSource source() {
        return new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", new ConfluenceKnowledgeSource(
                SourceAddress.of("wiki"), ConfluenceConfig.builder(FakeConfluence.BASE).build(), confluence, null));
    }

    @Test
    void aPageWithMoreAttachmentsThanTheListHoldsShowsTheFirstOnesWithAWarning() {
        confluence.space("DEV", "Entwicklung");
        confluence.page("100", "Start", "DEV", "<p>S</p>");
        for (int i = 0; i <= AttachmentListSource.MAX_ATTACHMENTS; i++) {
            confluence.attachment("att" + i, "100", "Datei " + i + ".txt", "text/plain", "x");
        }

        PageAttachments attachments = source().attachments("100");

        assertThat(attachments.attachments()).hasSize(500);
        assertThat(attachments.attachments().get(0).title()).isEqualTo("Datei 0.txt");
        assertThat(attachments.responses()).isEmpty();
        assertThat(attachments.nativeFormat()).isEqualTo(KnowledgeSourceHarvestSource.READER_ATTACHMENTS);
        assertThat(attachments.warnings()).singleElement().asString().contains("Lesebereich");
    }

    @Test
    void aCompleteListStaysTheStoredList() {
        confluence.space("DEV", "Entwicklung");
        confluence.page("100", "Start", "DEV", "<p>S</p>");
        confluence.attachment("att1", "100", "Plan.txt", "text/plain", "x");

        PageAttachments attachments = source().attachments("100");

        assertThat(attachments.attachments()).hasSize(1);
        assertThat(attachments.responses()).isNotEmpty();
        assertThat(attachments.warnings()).isEmpty();
    }
}
