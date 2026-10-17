package com.aresstack.spaceharvester.application.jobs;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Test-Fake für einen Seitenleser: liefert die IDs eines Space seitenweise, ohne Quelle oder Cache. Der echte
 * Leseport wird mit den Strängen C/D/G abgestimmt (Vertragsfenster 2); bis dahin genügt den Jobs diese Form.
 */
final class FakePageReader {

    private final List<String> pageIds;

    FakePageReader(int pages) {
        pageIds = IntStream.rangeClosed(1, pages).mapToObj(i -> "p" + i).toList();
    }

    int size() {
        return pageIds.size();
    }

    /** Ein typischer Auftrag: zählt, prüft vor jeder Seite auf Abbruch, verarbeitet und meldet Fortschritt. */
    JobTask exportTask(Consumer<String> onPage) {
        return JobTask.blocking(context -> {
            context.discovered(pageIds.size());
            for (String pageId : pageIds) {
                context.checkpoint();
                onPage.accept(pageId);
                context.processed(1);
                context.written(1);
            }
        });
    }
}
