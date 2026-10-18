package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.UnreadableChangeEventException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Webhook-Aufrufe von Confluence Data Center (ADR 0014). */
class ConfluenceWebhooksTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aPageUpdateBecomesAChangeWithSpaceAndVersion() {
        assertThat(ConfluenceWebhooks.parse(json("""
                {"timestamp":1760788800000,"userKey":"u1","updateTrigger":"edit_page",
                 "page":{"id":123456,"spaceKey":"DOC","title":"Start","contentType":"page","version":4,
                         "self":"https://wiki.example.org/pages/viewpage.action?pageId=123456"}}
                """), "page_updated"))
                .containsExactly(new ChangeEvent(ChangeEvent.Kind.CHANGED, "123456", "DOC", "4"));
    }

    @Test
    void theEventNameMayComeFromTheBodyAndTheVersionAsObject() {
        assertThat(ConfluenceWebhooks.parse(json("""
                {"event":"page_trashed","page":{"id":"77","space":{"key":"OPS"},"version":{"number":2}}}
                """), null))
                .containsExactly(new ChangeEvent(ChangeEvent.Kind.REMOVED, "77", "OPS", "2"));
        assertThat(ConfluenceWebhooks.parse(json("{\"page\":{\"id\":78}}"), "page_removed"))
                .containsExactly(new ChangeEvent(ChangeEvent.Kind.REMOVED, "78", null, null));
    }

    @Test
    void restrictionAndPermissionEventsBecomePermissionChanges() {
        assertThat(ConfluenceWebhooks.parse(json("{\"content\":{\"id\":5,\"type\":\"page\",\"spaceKey\":\"DOC\"}}"),
                "content_permissions_updated"))
                .containsExactly(new ChangeEvent(ChangeEvent.Kind.PERMISSIONS, "5", "DOC", null));
    }

    @Test
    void blogPostsCommentsAndUnknownEventsAreIgnored() {
        assertThat(ConfluenceWebhooks.parse(json("{\"blog\":{\"id\":1,\"spaceKey\":\"DOC\"}}"), "blog_created"))
                .isEmpty();
        assertThat(ConfluenceWebhooks.parse(json("{\"comment\":{\"id\":2}}"), "comment_created")).isEmpty();
        assertThat(ConfluenceWebhooks.parse(json("{\"content\":{\"id\":3,\"type\":\"blogpost\"}}"),
                "content_permissions_updated")).isEmpty();
        assertThat(ConfluenceWebhooks.parse(json("{\"page\":{\"id\":4}}"), "space_created")).isEmpty();
    }

    @Test
    void aBodyThatIsNoJsonObjectIsUnreadable() {
        assertThatThrownBy(() -> ConfluenceWebhooks.parse(json("nicht json"), "page_updated"))
                .isInstanceOf(UnreadableChangeEventException.class);
        assertThatThrownBy(() -> ConfluenceWebhooks.parse(json("[1]"), "page_updated"))
                .isInstanceOf(UnreadableChangeEventException.class);
        assertThatThrownBy(() -> ConfluenceWebhooks.parse(new byte[0], "page_updated"))
                .isInstanceOf(UnreadableChangeEventException.class);
    }
}
