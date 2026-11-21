package com.aresstack.spaceharvester.application.links;

import java.util.Locale;
import java.util.Objects;

/**
 * Ein Verweisziel, wie es in der Seite steht: die Seiten-ID oder Space und Titel. Ein Ziel über Space und Titel wird
 * erst bei der Abfrage einer Seite zugeordnet, und nur, wenn genau eine lokal bekannte Seite so heißt.
 *
 * @param pageId   Seiten-ID oder {@code null}, wenn Space und Titel das Ziel bestimmen
 * @param spaceKey Space des Ziels; bei einem Ziel über die ID {@code null}
 * @param title    Titel des Ziels; bei einem Ziel über die ID {@code null}
 */
public record LinkTarget(String pageId, String spaceKey, String title) {

    public LinkTarget {
        if (pageId != null) {
            if (pageId.isBlank() || spaceKey != null || title != null) {
                throw new IllegalArgumentException("Ziel über die ID hat weder Space noch Titel");
            }
        } else {
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(title, "title");
            if (spaceKey.isBlank() || title.isBlank()) {
                throw new IllegalArgumentException("Ziel über den Titel braucht Space und Titel");
            }
        }
    }

    /** Ziel über die Seiten-ID. */
    public static LinkTarget page(String pageId) {
        return new LinkTarget(Objects.requireNonNull(pageId, "pageId"), null, null);
    }

    /** Ziel über Space und Titel. */
    public static LinkTarget titled(String spaceKey, String title) {
        return new LinkTarget(null, spaceKey, title);
    }

    /** Ob das Ziel über die ID bestimmt ist. */
    public boolean byId() {
        return pageId != null;
    }

    /** Schlüssel für Space und Titel: Space wie geschrieben, Titel ohne Rand und ohne Groß-/Kleinschreibung. */
    public static String titleKey(String spaceKey, String title) {
        return spaceKey + '\u0000' + title.strip().toLowerCase(Locale.ROOT);
    }

    /** {@link #titleKey(String, String)} dieses Ziels; nur für Ziele über den Titel. */
    public String titleKey() {
        if (byId()) {
            throw new IllegalStateException("Ziel über die ID hat keinen Titelschlüssel");
        }
        return titleKey(spaceKey, title);
    }
}
