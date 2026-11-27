package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.spaceharvester.app.chat.ChatTools;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

/**
 * Eine Seite von Space Harvester als neutraler Verweis: ein internes Dokument mit der Kennung {@code Quelle/Seite}, wie
 * der Chat sie verwendet ({@link ChatTools#ref}).
 */
public final class PageRefs {

    private PageRefs() {
    }

    public static SourceRef of(PageKey key, String title) {
        return SourceRef.of(SourceKind.INTERNAL_DOCUMENT, ChatTools.ref(key)).withTitle(title == null ? "" : title);
    }

    /** Die Seite hinter dem Verweis; {@code null}, wenn er keine Seite von Space Harvester ist. */
    public static PageKey key(SourceRef ref) {
        return ref == null || !ref.kind().equals(SourceKind.INTERNAL_DOCUMENT) ? null : ChatTools.parse(ref.id());
    }
}
