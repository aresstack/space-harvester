package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.application.podcast.ReadAloud;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Ein Vorlese-Ablauf ohne Dienst: merkt sich die Texte und spricht, bis er gestoppt wird. */
public final class FakeReadAloud implements ReadAloud {

    public final List<String> spoken = new CopyOnWriteArrayList<>();
    public final List<String> calls = new CopyOnWriteArrayList<>();
    private boolean stopped;
    private boolean paused;

    @Override
    public boolean speak(String text, Listener listener) {
        synchronized (this) {
            stopped = false;
        }
        spoken.add(text);
        synchronized (this) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!stopped && System.nanoTime() < until) {
                try {
                    wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        return true;
    }

    /** Wartet, bis {@code count} Texte angekommen sind. */
    public boolean await(int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (spoken.size() < count && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        return spoken.size() >= count;
    }

    @Override
    public synchronized void stop() {
        calls.add("stop");
        stopped = true;
        notifyAll();
    }

    @Override
    public synchronized void pause() {
        calls.add("pause");
        paused = true;
    }

    @Override
    public synchronized void resume() {
        calls.add("resume");
        paused = false;
    }

    @Override
    public synchronized boolean isPaused() {
        return paused;
    }

    @Override
    public void setRate(double rate) {
    }

    @Override
    public double rate() {
        return 1;
    }

    @Override
    public String model() {
        return "orpheus-3b-german";
    }

    @Override
    public String voice() {
        return "Jana";
    }
}
