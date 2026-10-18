package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

final class RecordingStreamListener implements ChatStreamListener {

    private final List<String> events = new ArrayList<String>();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final CountDownLatch firstDelta = new CountDownLatch(1);
    volatile ChatResponse response;
    volatile ChatCompletionException error;

    @Override
    public synchronized void onStart() {
        events.add("start");
    }

    @Override
    public synchronized void onDelta(String text) {
        events.add("delta:" + text);
        firstDelta.countDown();
    }

    @Override
    public synchronized void onComplete(ChatResponse completed) {
        response = completed;
        events.add("complete");
        terminal.countDown();
    }

    @Override
    public synchronized void onError(ChatCompletionException failure) {
        error = failure;
        events.add("error");
        terminal.countDown();
    }

    @Override
    public synchronized void onCancelled() {
        events.add("cancelled");
        terminal.countDown();
    }

    synchronized List<String> events() {
        return new ArrayList<String>(events);
    }

    RecordingStreamListener awaitTerminal() throws InterruptedException {
        assertTrue("stream did not finish in time", terminal.await(10, TimeUnit.SECONDS));
        return this;
    }

    RecordingStreamListener awaitFirstDelta() throws InterruptedException {
        assertTrue("no delta in time", firstDelta.await(10, TimeUnit.SECONDS));
        return this;
    }
}
