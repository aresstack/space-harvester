package com.aresstack.spaceharvester.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aresstack.spaceharvester.application.query.KeywordSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Chat-Trefferliste beginnt mit der Liste der Suchleiste; die progressive Suche ergänzt nur. */
class ChatListedHitsTest {

    private static KeywordSearch.Hit hit(String id) {
        return new KeywordSearch.Hit("wiki", id, "ZOO", "Seite " + id, null, null, null, null, null);
    }

    @Test
    void barListComesFirstInItsOrderAndProgressiveOnlyAddsWhatIsMissing() {
        List<KeywordSearch.Hit> bar = List.of(hit("giraffe"), hit("zebra"), hit("okapi"));
        List<KeywordSearch.Hit> progressive = List.of(hit("lama"), hit("zebra"), hit("alpaka"));

        List<String> ids = ChatTools.listed(bar, progressive).stream().map(KeywordSearch.Hit::pageId).toList();

        assertEquals(List.of("giraffe", "zebra", "okapi", "lama", "alpaka"), ids);
    }

    @Test
    void withoutBarResultTheProgressiveListStays() {
        List<String> ids = ChatTools.listed(List.of(), List.of(hit("lama"), hit("alpaka"))).stream()
                .map(KeywordSearch.Hit::pageId).toList();

        assertEquals(List.of("lama", "alpaka"), ids);
    }

    @Test
    void sourcesWithoutOnlineSearchAreNamedSoTheModelDoesNotClaimNothingExists() {
        String one = ChatTools.localOnlyNote(List.of("Ablage"));
        String two = ChatTools.localOnlyNote(List.of("Ablage", "Dateien"));

        assertEquals(true, one.startsWith("Hinweis: Ablage kann nicht online durchsucht werden"));
        assertEquals(true, two.startsWith("Hinweis: Ablage, Dateien können nicht online durchsucht werden"));
        assertEquals(true, two.contains("zuerst indexieren"));
    }
}
