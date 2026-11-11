package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StoredAttachmentListTest {

    @Test
    public void listsShareSearchResponsesWithoutExposingThem() {
        byte[] first = "{\"results\":[1]}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"results\":[2]}".getBytes(StandardCharsets.UTF_8);
        StoredAttachmentList.SharedPayloads shared =
                new StoredAttachmentList.SharedPayloads(Arrays.asList(first, second));
        first[0] = 'x'; // Kopie beim Anlegen: spätere Änderungen des Aufrufers wirken nicht.
        Instant now = Instant.now();

        StoredAttachmentList onlySecond = new StoredAttachmentList(Collections.<KnowledgeResource>emptyList(), shared,
                Collections.singletonList(1), "application/json", "f", now, null);
        StoredAttachmentList both = new StoredAttachmentList(Collections.<KnowledgeResource>emptyList(), shared,
                Arrays.asList(0, 1), "application/json", "f", now, null);

        assertEquals(2, shared.size());
        assertEquals(1, onlySecond.payloads().size());
        assertArrayEquals(second, onlySecond.payloads().get(0));
        List<byte[]> handedOut = both.payloads();
        assertEquals('{', handedOut.get(0)[0]);
        handedOut.get(1)[0] = 'x';
        assertEquals('{', onlySecond.payloads().get(0)[0]);
        assertTrue(onlySecond.complete());
    }

    @Test
    public void positionOutsideTheSharedResponsesIsRejected() {
        StoredAttachmentList.SharedPayloads shared = new StoredAttachmentList.SharedPayloads(
                Collections.singletonList("{}".getBytes(StandardCharsets.UTF_8)));
        try {
            new StoredAttachmentList(Collections.<KnowledgeResource>emptyList(), shared,
                    Collections.singletonList(1), "application/json", "f", Instant.now(), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // erwartet
        }
    }
}
