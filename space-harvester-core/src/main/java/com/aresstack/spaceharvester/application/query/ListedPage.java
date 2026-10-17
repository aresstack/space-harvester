package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;

/**
 * Eine Seite der Liste und des Baums: die aktuellen Kopfdaten ({@link PageHeader}, gegebenenfalls neuer als die
 * Payload) und der Hash der gespeicherten Payload, ohne die Bytes zu laden.
 *
 * @param header    aktuelle Kopfdaten
 * @param rawSha256 {@code sha256:<hex>} der gespeicherten Payload oder {@code null}, wenn noch keine gespeichert ist
 */
public record ListedPage(PageHeader header, String rawSha256) {

    public ListedPage {
        Objects.requireNonNull(header, "header");
    }

    /**
     * @param snapshot gespeicherter Snapshot der Seite oder {@code null}, wenn noch keine Payload vorliegt
     */
    public static ListedPage of(PageHeader header, PageSnapshot snapshot) {
        return new ListedPage(header, snapshot == null ? null : snapshot.payload().rawSha256());
    }

    public String pageId() {
        return header.key().pageId();
    }

    public String parentPageId() {
        return header.metadata().parentPageId();
    }
}
