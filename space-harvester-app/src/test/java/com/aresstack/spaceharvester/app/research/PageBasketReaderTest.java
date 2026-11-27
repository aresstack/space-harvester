package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Seiten des Korbs als Passagen mit Überschriften; freigegeben, bevor ein Modell sie sieht. Erfundene Seiten. */
class PageBasketReaderTest {

    @Test
    void aPageBecomesPassagesWithTheirHeadingsAndIsReleasedBeforeTheNextModelCall() {
        HostResearchTest.Stock stock = new HostResearchTest.Stock();
        String text = "Imkerei\nDie Varroamilbe befällt die Brut im Spätsommer und schwächt das Volk deutlich.\n"
                + "Behandlung\nAmeisensäure wirkt gegen die Milbe, wenn die Temperatur stimmt und das Volk brutfrei ist.\n";
        int second = text.indexOf("Behandlung");
        PageText reading = new PageText("test/v1", text, List.of(new PageText.Section("Imkerei", 1, 0, text.length()),
                new PageText.Section("Behandlung", 2, second, text.length())));
        PageKey key = new PageKey("wiki", "9");
        stock.pages.put(key, new PageStock.Page(key, "Varroa", text, "GARTEN", null, reading));
        PageKey noSpace = new PageKey("wiki", "10");
        stock.pages.put(noSpace, new PageStock.Page(noSpace, "Ohne Space", "Ein Text ohne Space.", null, null));

        PendingReleases releases = new PendingReleases(stock);
        PageBasketReader reader = new PageBasketReader(stock, releases, PageBasketReader.defaultSegmentation());
        List<PassageView> passages = reader.passages(PageRefs.of(key, "Varroa"));

        assertThat(passages).isNotEmpty();
        assertThat(passages).anySatisfy(passage -> {
            assertThat(passage.text()).contains("Ameisensäure");
            assertThat(passage.headingPath()).contains("Behandlung");
        });
        assertThat(passages).extracting(PassageView::id).doesNotHaveDuplicates();
        assertThat(reader.passages(PageRefs.of(noSpace, "Ohne Space"))).isEmpty();
        assertThat(reader.unreadable()).isEqualTo(1);
        assertThat(stock.released).isEmpty();

        String[] seen = new String[1];
        MainModelChat model = releases.releasingBefore(new MainModelChat() {
            @Override
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
                seen[0] = stock.released.contains(key) ? "freigegeben" : "nicht freigegeben";
                return MainModelChatResult.ok("ok");
            }

            @Override
            public String modelName() {
                return "probe";
            }
        });
        model.complete(List.of(ChatMessage.user("x")), 0, 10);
        assertThat(seen[0]).isEqualTo("freigegeben");
        assertThat(stock.released).containsExactly(key);
    }
}
