package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.WebSearchResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DataForSeoSearchResponseMapperTest {

    @Test
    public void mapOrganicItems() {
        String response = "{"
                + "\"status_code\":20000,"
                + "\"tasks\":[{"
                + "\"status_code\":20000,"
                + "\"result\":[{\"items\":[{"
                + "\"type\":\"organic\","
                + "\"rank_group\":1,"
                + "\"title\":\"Example\","
                + "\"url\":\"https://example.org\","
                + "\"description\":\"Snippet\""
                + "}]}]}]}";

        WebSearchResult result =
                new DataForSeoSearchResponseMapper()
                        .map(response, SearchEngine.GOOGLE);

        assertEquals(1, result.getHits().size());
        assertEquals(
                "https://example.org",
                result.getHits().get(0).getUrl());
    }
}
