package com.aresstack.enterpriseai.astu.chat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatOptionsTest {

    @Test
    public void defaultsLeaveEverythingUnset() {
        ChatOptions options = ChatOptions.defaults();
        assertNull(options.model());
        assertNull(options.temperature());
        assertNull(options.topP());
        assertNull(options.topK());
        assertNull(options.maxTokens());
        assertNull(options.presencePenalty());
        assertNull(options.frequencyPenalty());
        assertTrue(options.stop().isEmpty());
        assertNull(options.endUserId());
    }

    @Test
    public void setValuesWinOverFallback() {
        ChatOptions defaults = ChatOptions.builder().model("m1").temperature(0.2).maxTokens(100)
                .stop(Collections.singletonList("END")).endUserId("u1").build();
        ChatOptions override = ChatOptions.builder().temperature(0.9).topK(40).build();

        ChatOptions merged = override.withFallback(defaults);

        assertEquals("m1", merged.model());
        assertEquals(Double.valueOf(0.9), merged.temperature());
        assertEquals(Integer.valueOf(40), merged.topK());
        assertEquals(Integer.valueOf(100), merged.maxTokens());
        assertEquals(Collections.singletonList("END"), merged.stop());
        assertEquals("u1", merged.endUserId());
    }

    @Test
    public void nonEmptyStopListReplacesFallbackStopList() {
        ChatOptions merged = ChatOptions.builder().stop(Arrays.asList("A", "B")).build()
                .withFallback(ChatOptions.builder().stop(Collections.singletonList("C")).build());
        assertEquals(Arrays.asList("A", "B"), merged.stop());
    }

    @Test
    public void rejectsImplausibleValues() {
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().temperature(Double.NaN);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().temperature(-0.1);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().topP(1.5);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().topK(0);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().maxTokens(-1);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().presencePenalty(Double.POSITIVE_INFINITY);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ChatOptions.builder().stop(Collections.singletonList(""));
            }
        });
    }

    @Test
    public void stopListIsCopiedAndUnmodifiable() {
        java.util.List<String> source = new java.util.ArrayList<String>(Collections.singletonList("X"));
        ChatOptions options = ChatOptions.builder().stop(source).build();
        source.add("Y");
        assertEquals(Collections.singletonList("X"), options.stop());
        try {
            options.stop().add("Z");
            fail("stop list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // erwartet
        }
    }

    @Test
    public void toStringDoesNotRevealEndUserId() {
        String text = ChatOptions.builder().endUserId("max.mustermann").build().toString();
        assertTrue(text, !text.contains("mustermann"));
    }

    private static void expectRejected(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // erwartet
        }
    }
}
