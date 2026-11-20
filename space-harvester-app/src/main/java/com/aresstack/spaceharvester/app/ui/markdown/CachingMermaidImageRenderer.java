package com.aresstack.spaceharvester.app.ui.markdown;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hält eine begrenzte Zahl gerenderter Diagramme im Speicher und delegiert bei Fehltreffern
 * (aus askai-java8 {@code CachingMermaidImageRenderer}). Keine geteilte Instanz: jeder Verlauf bekommt über
 * {@link #forChat()} seinen eigenen Cache, wie die Architekturregeln es verlangen (keine Singletons).
 */
public final class CachingMermaidImageRenderer implements MermaidImageRenderer {

    private static final int DEFAULT_CACHE_SIZE = 32;

    private final MermaidImageRenderer delegate;
    private final Map<CacheKey, BufferedImage> cache;

    public CachingMermaidImageRenderer(MermaidImageRenderer delegate, final int maximumEntries) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.delegate = delegate;
        this.cache = new LinkedHashMap<CacheKey, BufferedImage>(maximumEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    /**
     * Die produktive Kette für einen Chat-Verlauf: Cache über dem fehlertoleranten Normalisierer über mermaid-java.
     * Der Cache liegt über dem Normalisierer: er schlüsselt nach dem ursprünglichen Quelltext und speichert nur
     * erfolgreiche Renderings; die Reparatur passiert bei einem Fehltreffer im Delegaten.
     */
    public static CachingMermaidImageRenderer forChat() {
        return new CachingMermaidImageRenderer(
                new NormalizingMermaidImageRenderer(
                        new AresStackMermaidImageRenderer(), new MermaidRenderingSourceNormalizer()),
                DEFAULT_CACHE_SIZE);
    }

    @Override
    public BufferedImage render(String diagramCode, int width) {
        CacheKey key = new CacheKey(diagramCode, width);
        synchronized (cache) {
            BufferedImage cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        BufferedImage rendered = delegate.render(diagramCode, width);
        if (rendered != null) {
            synchronized (cache) {
                cache.put(key, rendered);
            }
        }
        return rendered;
    }

    private static final class CacheKey {

        private final String diagramCode;
        private final int width;

        private CacheKey(String diagramCode, int width) {
            this.diagramCode = diagramCode == null ? "" : diagramCode;
            this.width = width;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return width == that.width && diagramCode.equals(that.diagramCode);
        }

        @Override
        public int hashCode() {
            return 31 * diagramCode.hashCode() + width;
        }
    }
}
