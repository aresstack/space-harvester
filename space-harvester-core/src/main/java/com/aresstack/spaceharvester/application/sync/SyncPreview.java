package com.aresstack.spaceharvester.application.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Vorschau eines Abgleichs („Bestand prüfen“, ADR 0014): was ein Lauf in derselben Betriebsart ändern würde, ohne etwas
 * zu laden oder zu schreiben. Die Inventur und die Einzelprüfungen fehlender Seiten laufen wie im echten Lauf; ein
 * späterer Lauf plant neu.
 *
 * @param summary          Zähler wie nach einem Lauf, ohne Generation
 * @param newPageIds       neue Seiten (mit oder ohne Inhalt)
 * @param changedPageIds   bekannte Seiten mit neuer Version
 * @param movedPageIds     verschobene Seiten
 * @param removedPageIds   bestätigt gelöschte Seiten
 * @param withheldPageIds  Seiten, die gesperrt würden (für das technische Konto nicht sichtbar)
 * @param unverifiedPageIds fehlende Seiten, deren Fehlen nicht bestätigt werden konnte; sie blieben
 */
public record SyncPreview(SyncSummary summary, List<String> newPageIds, List<String> changedPageIds,
                          List<String> movedPageIds, List<String> removedPageIds, List<String> withheldPageIds,
                          List<String> unverifiedPageIds) {

    public SyncPreview {
        Objects.requireNonNull(summary, "summary");
        newPageIds = List.copyOf(newPageIds);
        changedPageIds = List.copyOf(changedPageIds);
        movedPageIds = List.copyOf(movedPageIds);
        removedPageIds = List.copyOf(removedPageIds);
        withheldPageIds = List.copyOf(withheldPageIds);
        unverifiedPageIds = List.copyOf(unverifiedPageIds);
    }

    static SyncPreview of(SyncPlan plan) {
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (PageChange change : plan.changes()) {
            switch (change.kind()) {
                case NEW, NOT_LOADED -> added.add(change.pageId());
                case CONTENT_CHANGED, OUTDATED -> changed.add(change.pageId());
                case MISSING -> {
                    RemovalCheck.Result removal = plan.removals().get(change.pageId());
                    RemovalCheck.Verdict verdict = removal == null ? null : removal.verdict();
                    if (verdict == RemovalCheck.Verdict.DELETED) {
                        removed.add(change.pageId());
                    } else if (verdict == RemovalCheck.Verdict.INACCESSIBLE) {
                        withheld.add(change.pageId());
                    } else if (verdict != RemovalCheck.Verdict.MOVED_OUT) {
                        unverified.add(change.pageId());
                    }
                }
                default -> {
                }
            }
            if (change.moved()) {
                moved.add(change.pageId());
            }
        }
        String notConfirmed = plan.unconfirmable().orElse(null);
        return new SyncPreview(new SpaceSync.SyncRun(plan, java.util.Set.of(), null, notConfirmed).summary(), added,
                changed, moved, removed, withheld, unverified);
    }
}
