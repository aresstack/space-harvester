package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Page the authoritative inventory, independently of the optional link crawl. */
final class MediaWikiInventory {
    static final int BATCH_SIZE = 50;
    private final MediaWikiClient client;
    private final String siteKey;

    MediaWikiInventory(MediaWikiClient client, String siteKey) {
        this.client = client;
        this.siteKey = siteKey;
    }

    Batch pages(List<Integer> namespaces, String cursor, int limit) throws KnowledgeSourceException {
        String key = "pages:" + namespaces;
        Cursor position = decode(key, cursor);
        if (position.index >= namespaces.size()) {
            throw invalidCursor();
        }
        MediaWikiTitleBatch batch = client.allPages(namespaces.get(position.index),
                Math.max(1, Math.min(BATCH_SIZE, limit)), position.token);
        String next = !batch.continuation.isEmpty() ? encode(key, position.index, batch.continuation)
                : position.index + 1 < namespaces.size() ? encode(key, position.index + 1, "") : "";
        if (!next.isEmpty() && next.equals(cursor)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "MediaWiki inventory did not advance");
        }
        return new Batch(batch.titles, next);
    }

    Batch categories(String cursor) throws KnowledgeSourceException {
        Cursor position = decode("categories", cursor);
        MediaWikiTitleBatch batch = client.categories(BATCH_SIZE, position.token);
        return new Batch(batch.titles, next("categories", batch, cursor));
    }

    Batch members(String category, List<Integer> namespaces, String cursor) throws KnowledgeSourceException {
        String key = "category:" + category;
        Cursor position = decode(key, cursor);
        MediaWikiTitleBatch batch = client.categoryMembers(category, namespaces, BATCH_SIZE, position.token);
        return new Batch(batch.titles, next(key, batch, cursor));
    }

    private String next(String key, MediaWikiTitleBatch batch, String cursor) throws KnowledgeSourceException {
        String next = batch.continuation.isEmpty() ? "" : encode(key, 0, batch.continuation);
        if (!next.isEmpty() && next.equals(cursor)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "MediaWiki listing did not advance");
        }
        return next;
    }

    private String encode(String key, int index, String token) {
        return "mw1." + base64(siteKey + "|" + key) + "." + index + "." + base64(token);
    }

    private Cursor decode(String key, String cursor) throws KnowledgeSourceException {
        if (cursor == null || cursor.isEmpty()) {
            return new Cursor(0, "");
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 4 || !"mw1".equals(parts[0])
                    || !base64(siteKey + "|" + key).equals(parts[1])) {
                throw invalidCursor();
            }
            int index = Integer.parseInt(parts[2]);
            if (index < 0 || (!key.startsWith("pages:") && index != 0)) {
                throw invalidCursor();
            }
            return new Cursor(index, new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static KnowledgeSourceException invalidCursor() {
        return new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid MediaWiki inventory cursor");
    }

    static final class Batch {
        final List<String> titles;
        final String next;
        Batch(List<String> titles, String next) {
            this.titles = titles;
            this.next = next;
        }
    }

    private static final class Cursor {
        final int index;
        final String token;
        Cursor(int index, String token) {
            this.index = index;
            this.token = token;
        }
    }
}
