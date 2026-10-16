package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource;
import com.aresstack.enterpriseai.source.api.testing.KnowledgeSourceContractTest;

/** Der Fake erfüllt den Vertrag selbst – sonst wären Tests gegen ihn wertlos. */
public class InMemoryKnowledgeSourceContractTest extends KnowledgeSourceContractTest {

    private static InMemoryKnowledgeSource fixture() {
        return new InMemoryKnowledgeSource("memory-test")
                .add("start", "Startseite", "# Start\n\nWillkommen im Wissen.", "a", "b", "fehlt", "a")
                .add("a", "Seite A", "Inhalt A mit Suchwort.", "start", "c")
                .add("b", "Seite B", "Inhalt B.")
                .add("c", "Seite C", "Inhalt C.");
    }

    @Override
    protected KnowledgeSourcePort createSource() {
        return fixture();
    }

    @Override
    protected String linkedStartPoint() {
        return "start";
    }

    @Override
    protected String missingStartPoint() {
        return "gibt-es-nicht";
    }

    @Override
    protected KnowledgeResourceId unknownResourceId() {
        return fixture().idOf("gibt-es-nicht");
    }

    @Override
    protected String searchTextWithHits() {
        return "suchwort";
    }
}
