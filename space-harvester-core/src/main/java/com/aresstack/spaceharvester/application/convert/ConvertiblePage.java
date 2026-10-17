package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;

/**
 * Eine Seite für einen Converter.
 *
 * @param header   Kopfdaten der Seite im Cache
 * @param snapshot nativer Snapshot mit der unveränderten Payload
 * @param depth    Tiefe im Baum, Wurzeln haben 0
 */
public record ConvertiblePage(PageHeader header, PageSnapshot snapshot, int depth) {

    public ConvertiblePage {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(snapshot, "snapshot");
        if (depth < 0) {
            throw new IllegalArgumentException("depth < 0");
        }
    }

    public String pageId() {
        return header.key().pageId();
    }
}
