package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.testing.KnowledgeSourceContractTest;

/** Gemeinsame Port-Vertragstests (source-api) gegen den Jira-Adapter über {@link FakeJira}. */
public class JiraKnowledgeSourceContractTest extends KnowledgeSourceContractTest {

    static FakeJira sample() {
        FakeJira jira = new FakeJira();
        jira.project("DEMO", "Demo-Projekt");
        jira.project("OPS", "Betrieb");
        jira.issue("DEMO-1", "Anmeldung schlägt fehl", "Nach dem Update klappt die Anmeldung nicht mehr.")
                .comment("Carol", "Ich schaue es mir an.", "<p>Ich schaue es mir an.</p>");
        jira.issue("DEMO-2", "Zertifikat erneuern", "Das Zertifikat läuft im März ab.");
        jira.issue("DEMO-3", "Teilaufgabe Protokoll", "Protokoll der Anmeldung prüfen.");
        jira.issue("OPS-7", "Server neu starten", "Wartungsfenster am Wochenende, zwei Stunden.");
        jira.link("DEMO-1", "blocks", "DEMO-2");
        jira.issues.get("DEMO-1").subtasks.add("DEMO-3");
        jira.issues.get("DEMO-3").parent = "DEMO-1";
        return jira;
    }

    @Override
    protected KnowledgeSourcePort createSource() {
        JiraConfig config = JiraConfig.builder(FakeJira.BASE)
                .credentialRef(RecordingSecretProvider.REF)
                .pageSize(2)
                .build();
        return new JiraKnowledgeSource(SourceAddress.of("jira-dc"), config, sample(), new RecordingSecretProvider());
    }

    @Override
    protected String linkedStartPoint() {
        return "DEMO-1";
    }

    @Override
    protected String missingStartPoint() {
        return "DEMO-999";
    }

    @Override
    protected KnowledgeResourceId unknownResourceId() {
        return KnowledgeResourceId.of("jira", "jira.example.org/jira/issue/DEMO-424242");
    }

    @Override
    protected String searchTextWithHits() {
        return "Zertifikat";
    }
}
