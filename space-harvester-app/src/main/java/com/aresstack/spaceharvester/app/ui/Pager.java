package com.aresstack.spaceharvester.app.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Blättert durch mehrere Abfragen des Core mit je eigenem Cursor und führt sie zu einer Liste zusammen: Innerhalb einer
 * Gruppe liefert {@link #next} die Einträge aller Ströme in der Ordnung von {@code order} (jeder Strom ist für sich
 * schon so geordnet); die nächste Gruppe kommt erst, wenn die vorige leer ist. So blättern Bezeichner (eine Gruppe:
 * Nomen und Kennungen) und Beziehungen (vorgeschlagene und bestätigte, danach abgelehnte) mit demselben Stück Code.
 * Jeder Strom lädt erst, wenn er gebraucht wird, und je Aufruf höchstens so viele Seiten, bis er etwas beisteuern kann.
 * Nicht auf dem EDT nutzen; die Aufrufe sind untereinander synchronisiert.
 *
 * @param <T> der Eintrag
 */
public final class Pager<T> {

    /** Eine Seite einer Abfrage, wie sie der Core liefert. */
    public record Page<T>(List<T> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /** Holt die Seite nach {@code cursor} ({@code null}: die erste). */
    public interface Fetch<T> {
        Page<T> fetch(String cursor) throws Exception;
    }

    private final Comparator<? super T> order;
    private final List<List<Stream>> groups = new ArrayList<>();
    private int group;

    /**
     * @param order  Ordnung innerhalb einer Gruppe; sie muss der Ordnung jedes Stroms entsprechen
     * @param groups Gruppen von Strömen in der Reihenfolge, in der sie erscheinen
     */
    public Pager(Comparator<? super T> order, List<List<Fetch<T>>> groups) {
        this.order = Objects.requireNonNull(order, "order");
        for (List<Fetch<T>> fetches : groups) {
            List<Stream> streams = new ArrayList<>(fetches.size());
            for (Fetch<T> fetch : fetches) {
                streams.add(new Stream(fetch));
            }
            this.groups.add(List.copyOf(streams));
        }
    }

    /** Die nächsten höchstens {@code count} Einträge; leer, wenn alle Gruppen erschöpft sind. */
    public synchronized List<T> next(int count) throws Exception {
        List<T> page = new ArrayList<>(count);
        while (page.size() < count && group < groups.size()) {
            Stream smallest = null;
            for (Stream stream : groups.get(group)) {
                stream.fill();
                if (!stream.buffer.isEmpty() && (smallest == null
                        || order.compare(stream.buffer.peekFirst(), smallest.buffer.peekFirst()) < 0)) {
                    smallest = stream;
                }
            }
            if (smallest == null) {
                group++;
                continue;
            }
            page.add(smallest.buffer.pollFirst());
        }
        return page;
    }

    /** Ob noch Einträge kommen können; vor dem ersten Laden immer, solange es Ströme gibt. */
    public synchronized boolean hasMore() {
        for (int g = group; g < groups.size(); g++) {
            for (Stream stream : groups.get(g)) {
                if (!stream.buffer.isEmpty() || !stream.done) {
                    return true;
                }
            }
        }
        return false;
    }

    private final class Stream {
        private final Fetch<T> fetch;
        private final ArrayDeque<T> buffer = new ArrayDeque<>();
        private String cursor;
        private boolean done;

        Stream(Fetch<T> fetch) {
            this.fetch = Objects.requireNonNull(fetch, "fetch");
        }

        /** Lädt Seiten, bis der Puffer etwas enthält oder der Strom zu Ende ist. */
        void fill() throws Exception {
            while (buffer.isEmpty() && !done) {
                Page<T> page = fetch.fetch(cursor);
                buffer.addAll(page.items());
                cursor = page.nextCursor();
                done = cursor == null;
            }
        }
    }
}
