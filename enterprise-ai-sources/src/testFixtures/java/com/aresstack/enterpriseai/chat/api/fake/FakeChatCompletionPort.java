package com.aresstack.enterpriseai.chat.api.fake;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatRole;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Skriptbarer {@link ChatCompletionPort} ohne Netzwerk, für Use-Case- und UI-Tests sowie Demos.
 *
 * <p>Antworten werden per {@code enqueue*} in Reihenfolge vorgegeben. Ist die Warteschlange leer, antwortet
 * der Fake mit einem Echo der letzten Nutzernachricht. Streaming läuft auf einem eigenen Daemon-Thread je
 * Anfrage und hält den Lebenszyklus des Ports ein (onStart, Deltas, genau ein Abschluss).
 */
public final class FakeChatCompletionPort implements ChatCompletionPort {

    private final Object lock = new Object();
    private final LinkedList<Script> scripts = new LinkedList<Script>();
    private final List<ChatRequest> requests = new ArrayList<ChatRequest>();
    private final long deltaDelayMillis;

    public FakeChatCompletionPort() {
        this(0L);
    }

    /** @param deltaDelayMillis Pause vor jedem Delta, um sichtbares Streaming zu simulieren */
    public FakeChatCompletionPort(long deltaDelayMillis) {
        this.deltaDelayMillis = deltaDelayMillis;
    }

    /** Die nächste Anfrage wird mit diesen Deltas beantwortet. */
    public FakeChatCompletionPort enqueueAnswer(String... deltas) {
        return enqueue(new Script(Arrays.asList(deltas), null, false));
    }

    /** Die nächste Anfrage scheitert mit diesem Fehler (beim Streaming nach onStart). */
    public FakeChatCompletionPort enqueueError(ChatCompletionException error) {
        return enqueue(new Script(Collections.<String>emptyList(), error, false));
    }

    /** Die nächste Anfrage liefert diese Deltas und hängt dann, bis sie abgebrochen wird. */
    public FakeChatCompletionPort enqueueHanging(String... deltas) {
        return enqueue(new Script(Arrays.asList(deltas), null, true));
    }

    /** @return alle bisher empfangenen Anfragen in Reihenfolge */
    public List<ChatRequest> requests() {
        synchronized (lock) {
            return new ArrayList<ChatRequest>(requests);
        }
    }

    public ChatRequest lastRequest() {
        synchronized (lock) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Script script = next(request);
        if (script.error != null) {
            throw script.error;
        }
        if (script.hanging) {
            throw new IllegalStateException("hanging scripts are only supported for streaming");
        }
        return response(join(script.deltas), request);
    }

    @Override
    public ChatTask stream(final ChatRequest request, final ChatStreamListener listener) {
        final Script script = next(request);
        final FakeTask task = new FakeTask();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                play(script, request, listener, task);
            }
        }, "fake-chat-stream");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private void play(Script script, ChatRequest request, ChatStreamListener listener, FakeTask task) {
        try {
            if (task.cancelRequested()) {
                task.finishCancelled(listener);
                return;
            }
            listener.onStart();
            StringBuilder text = new StringBuilder();
            for (String delta : script.deltas) {
                if (deltaDelayMillis > 0L && task.awaitCancel(deltaDelayMillis)) {
                    break;
                }
                if (task.cancelRequested()) {
                    break;
                }
                text.append(delta);
                listener.onDelta(delta);
            }
            if (script.hanging && !task.cancelRequested()) {
                task.awaitCancel(Long.MAX_VALUE);
            }
            if (script.error != null) {
                task.finish(listener, null, script.error);
            } else {
                task.finish(listener, response(text.toString(), request), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private FakeChatCompletionPort enqueue(Script script) {
        synchronized (lock) {
            scripts.add(script);
        }
        return this;
    }

    private Script next(ChatRequest request) {
        synchronized (lock) {
            requests.add(request);
            if (!scripts.isEmpty()) {
                return scripts.removeFirst();
            }
        }
        return new Script(Collections.singletonList("Echo: " + lastUserText(request)), null, false);
    }

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.USER) {
                return messages.get(i).content();
            }
        }
        return "";
    }

    private static ChatResponse response(String text, ChatRequest request) {
        return new ChatResponse(ChatMessage.assistant(text), ChatFinishReason.STOP, ChatUsage.notReported(),
                request.options().model() == null ? "fake" : request.options().model());
    }

    private static String join(List<String> deltas) {
        StringBuilder text = new StringBuilder();
        for (String delta : deltas) {
            text.append(delta);
        }
        return text.toString();
    }

    private static final class Script {
        final List<String> deltas;
        final ChatCompletionException error;
        final boolean hanging;

        Script(List<String> deltas, ChatCompletionException error, boolean hanging) {
            this.deltas = new ArrayList<String>(deltas);
            this.error = error;
            this.hanging = hanging;
        }
    }

    /**
     * Abbruch und Abschluss entscheiden sich unter einem Lock: Wer zuerst kommt, gewinnt. Ein Abbruch vor
     * dem Abschluss führt immer zu onCancelled, ein Abbruch danach ist wirkungslos.
     */
    private static final class FakeTask implements ChatTask {
        private final CountDownLatch cancelSignal = new CountDownLatch(1);
        private boolean done;
        private boolean cancelled;

        @Override
        public synchronized void cancel() {
            if (!done) {
                cancelSignal.countDown();
            }
        }

        @Override
        public synchronized boolean isDone() {
            return done;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        boolean cancelRequested() {
            return cancelSignal.getCount() == 0L;
        }

        /** @return {@code true}, wenn innerhalb der Zeit abgebrochen wurde */
        boolean awaitCancel(long millis) throws InterruptedException {
            return cancelSignal.await(millis, TimeUnit.MILLISECONDS);
        }

        void finishCancelled(ChatStreamListener listener) {
            finish(listener, null, null);
        }

        /** Schließt ab: bei vorherigem Abbruch mit onCancelled, sonst mit Antwort bzw. Fehler. */
        void finish(ChatStreamListener listener, ChatResponse response, ChatCompletionException error) {
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                cancelled = cancelRequested() || (response == null && error == null);
            }
            if (cancelled) {
                listener.onCancelled();
            } else if (error != null) {
                listener.onError(error);
            } else {
                listener.onComplete(response);
            }
        }
    }
}
