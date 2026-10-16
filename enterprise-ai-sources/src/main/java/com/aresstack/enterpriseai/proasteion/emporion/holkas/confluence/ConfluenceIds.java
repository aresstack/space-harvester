package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

import java.util.regex.Pattern;

/**
 * Ressourcen-IDs und Startpunkte des Confluence-Adapters (paketintern).
 *
 * <ul>
 *   <li>Seite: {@code confluence:<locator>/page/<contentId>}</li>
 *   <li>Anhang: {@code confluence:<locator>/attachment/<attachmentId>} (Confluence-IDs der Form {@code att123})</li>
 * </ul>
 * Der Locator ist die kanonische Basis-URL ohne Schema ({@code cf.intern/confluence}, siehe {@code SourceLocators}),
 * nie ein Benutzername der Quelle; als corenth-Bookmark also {@code confluence://cf.intern/confluence/page/123456}.
 * Die Content-ID ist in Confluence über Umbenennen und Verschieben hinweg stabil und damit der Upsert-Schlüssel.
 */
final class ConfluenceIds {

    static final String SCHEME = "confluence";

    private static final Pattern CONTENT_ID = Pattern.compile("[0-9]{1,19}");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("att[0-9]{1,19}");
    /** Space-Keys: Buchstaben/Ziffern/Unterstrich, persönliche Spaces mit {@code ~}. */
    private static final Pattern SPACE_KEY = Pattern.compile("~?[A-Za-z0-9_][A-Za-z0-9_.@-]{0,254}");

    private ConfluenceIds() {
    }

    static KnowledgeResourceId page(String locator, String contentId) {
        return KnowledgeResourceId.of(SCHEME, locator + "/page/" + contentId);
    }

    static KnowledgeResourceId attachment(String locator, String attachmentId) {
        return KnowledgeResourceId.of(SCHEME, locator + "/attachment/" + attachmentId);
    }

    static boolean isContentId(String value) {
        return value != null && CONTENT_ID.matcher(value).matches();
    }

    static boolean isAttachmentId(String value) {
        return value != null && ATTACHMENT_ID.matcher(value).matches();
    }

    static boolean isSpaceKey(String value) {
        return value != null && SPACE_KEY.matcher(value).matches();
    }

    /** Zerlegte ID dieser Quelle oder {@code null}, wenn die ID nicht zu ihr gehört. */
    static Parsed parse(String locator, KnowledgeResourceId id) {
        if (!SCHEME.equals(id.scheme())) {
            return null;
        }
        String prefix = locator + "/";
        String rest = id.schemeSpecificPart();
        if (!rest.startsWith(prefix)) {
            return null;
        }
        rest = rest.substring(prefix.length());
        if (rest.startsWith("page/") && isContentId(rest.substring(5))) {
            return new Parsed(false, rest.substring(5));
        }
        if (rest.startsWith("attachment/") && isAttachmentId(rest.substring(11))) {
            return new Parsed(true, rest.substring(11));
        }
        return null;
    }

    /**
     * Startpunkt aus {@code SourceScope}: Seiten-ID ({@code 123456} oder {@code page:123456}), Space
     * ({@code space:DEV} oder {@code DEV}, beginnt bei der Startseite des Space) oder eine Ressourcen-ID dieser Quelle.
     *
     * @return {@code null}, wenn der Startpunkt keine dieser Formen hat
     */
    static StartPoint startPoint(String locator, String raw) {
        String value = raw.trim();
        if (value.startsWith(SCHEME + ":")) {
            Parsed parsed;
            try {
                parsed = parse(locator, KnowledgeResourceId.of(value));
            } catch (IllegalArgumentException e) {
                return null;
            }
            return parsed == null || parsed.attachment ? null : new StartPoint(parsed.contentId, null);
        }
        if (value.regionMatches(true, 0, "page:", 0, 5)) {
            String id = value.substring(5).trim();
            return isContentId(id) ? new StartPoint(id, null) : null;
        }
        if (value.regionMatches(true, 0, "space:", 0, 6)) {
            String key = value.substring(6).trim();
            return isSpaceKey(key) ? new StartPoint(null, key) : null;
        }
        if (isContentId(value)) {
            return new StartPoint(value, null);
        }
        return isSpaceKey(value) ? new StartPoint(null, value) : null;
    }

    static final class Parsed {
        final boolean attachment;
        final String contentId;

        Parsed(boolean attachment, String contentId) {
            this.attachment = attachment;
            this.contentId = contentId;
        }
    }

    static final class StartPoint {
        final String pageId;
        final String spaceKey;

        StartPoint(String pageId, String spaceKey) {
            this.pageId = pageId;
            this.spaceKey = spaceKey;
        }
    }
}
