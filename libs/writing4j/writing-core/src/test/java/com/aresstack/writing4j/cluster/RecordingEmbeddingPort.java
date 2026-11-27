package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test fixture: an {@link EmbeddingPort} that logs role and text of every call, so a test can check which role a
 * productive path uses (PR 04; PR 14, 22 and 27 check theirs with it). Vectors are a fixed unit vector.
 */
public final class RecordingEmbeddingPort implements EmbeddingPort {

    /** One embedded text with the role it was embedded in. */
    public static final class Call {
        public final Role role;
        public final String text;

        Call(Role role, String text) {
            this.role = role;
            this.text = text;
        }

        @Override
        public String toString() {
            return role + ":" + text;
        }
    }

    private final List<Call> calls = new ArrayList<Call>();

    @Override
    public synchronized List<EmbeddingVector> embed(Role role, List<String> texts) {
        List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
        for (String text : texts) {
            calls.add(new Call(role, text));
            out.add(new EmbeddingVector("rec", "fp-rec", new float[]{1f, 0f}));
        }
        return out;
    }

    public synchronized List<Call> calls() {
        return Collections.unmodifiableList(new ArrayList<Call>(calls));
    }

    /** The texts embedded in one role, in call order. */
    public synchronized List<String> texts(Role role) {
        List<String> out = new ArrayList<String>();
        for (Call call : calls) {
            if (call.role == role) {
                out.add(call.text);
            }
        }
        return out;
    }
}
