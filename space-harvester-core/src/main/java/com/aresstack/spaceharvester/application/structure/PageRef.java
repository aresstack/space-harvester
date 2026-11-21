package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;

/**
 * Eine lokal gespeicherte Seite, wie der Aufrufer sie sehen darf.
 *
 * @param key      Quelle und Seite
 * @param title    Titel aus den Kopfdaten
 * @param spaceKey Space oder {@code null}
 */
public record PageRef(PageKey key, String title, String spaceKey) {

    public PageRef {
        Objects.requireNonNull(key, "key");
    }
}
