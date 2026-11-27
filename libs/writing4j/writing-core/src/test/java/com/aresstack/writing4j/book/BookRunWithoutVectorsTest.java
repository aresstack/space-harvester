package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Phase 4 without an embedding model: where a demanded question goes, and what the counted chat passes on. */
public class BookRunWithoutVectorsTest {

    private static BookRun.BasketPassage passage(String id, String text) {
        return new BookRun.BasketPassage(id, SourceRef.of(SourceKind.INTERNAL_DOCUMENT, "quelle").withTitle("Quelle"),
                "intern", "", text);
    }

    @Test
    public void aDemandedQuestionGoesToTheChapterThatSharesMostWordsOnAverageNotToTheLargest() {
        List<BookRun.BasketPassage> passages = new ArrayList<BookRun.BasketPassage>();
        Map<String, List<String>> members = new LinkedHashMap<String, List<String>>();
        List<String> large = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            passages.add(passage("g" + i, "Die Gemeinde pflegt ihre Wiesen und Hecken entlang der Wege."));
            large.add("g" + i);
        }
        for (int i = 20; i < 23; i++) {
            // more passages of the large chapter share a word than the small chapter has passages
            passages.add(passage("g" + i, "Die Gemeinde plant eine Pflanzung am Rathaus."));
            large.add("g" + i);
        }
        passages.add(passage("k1", "Die Pflanzung junger Bäume braucht einen Pfahl und Bindematerial."));
        passages.add(passage("k2", "Nach der Pflanzung werden junge Bäume reichlich gewässert."));
        members.put("kapitel-1", large);
        members.put("kapitel-2", Arrays.asList("k1", "k2"));

        assertEquals("kapitel-2", BookRun.nearestByWords("Wie gelingt die Pflanzung junger Bäume?", passages,
                members));
        assertEquals("", BookRun.nearestByWords("Was kostet Strom?", passages, members));
    }

    @Test
    public void theCountedChatPassesTheContextLengthOfTheModelOn() {
        MainModelChat model = new MainModelChat() {
            @Override
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
                return MainModelChatResult.ok("");
            }

            @Override
            public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                    int maxOutputTokens, String schemaJson) {
                return MainModelChatResult.ok("{}");
            }

            @Override
            public int contextTokens() {
                return 131072;
            }

            @Override
            public String modelName() {
                return "gross";
            }
        };
        BookRun.Step step = new BookRun.Step("fragen");
        assertEquals(131072, new BookRun.Metered(model, step, new BookRun.Switches()).contextTokens());
    }
}
