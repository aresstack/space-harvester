package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.testing.KnowledgeSourceContractTest;

/** Gemeinsame Port-Vertragstests (source-api) gegen den Confluence-Adapter über {@link FakeConfluence}. */
public class ConfluenceKnowledgeSourceContractTest extends KnowledgeSourceContractTest {

    @Override
    protected KnowledgeSourcePort createSource() {
        FakeConfluence confluence = new FakeConfluence();
        confluence.page("100", "Start", "DEV", "<p>Willkommen im <b>DEV</b>-Space.</p>");
        confluence.page("101", "Kapitel 1", "DEV", "<h2>Einleitung</h2><p>Text eins.</p>");
        confluence.page("102", "Kapitel 2", "DEV", "<p>Text zwei.</p>");
        confluence.page("103", "Unterkapitel", "DEV", "<p>Text drei.</p>");
        confluence.child("100", "101");
        confluence.child("100", "102");
        confluence.child("101", "103");
        confluence.spaceHomepages.put("DEV", "100");
        confluence.attachment("att900", "100", "notes.txt", "text/plain", "Notizen");
        confluence.searchResults.add("102");
        ConfluenceConfig config = ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF)
                .includeAttachments(true)
                .pageSize(1)
                .build();
        return new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"), config, confluence,
                new RecordingSecretProvider());
    }

    @Override
    protected String linkedStartPoint() {
        return "space:DEV";
    }

    @Override
    protected String missingStartPoint() {
        return "999";
    }

    @Override
    protected KnowledgeResourceId unknownResourceId() {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/424242");
    }

    @Override
    protected String searchTextWithHits() {
        return "zwei";
    }
}
