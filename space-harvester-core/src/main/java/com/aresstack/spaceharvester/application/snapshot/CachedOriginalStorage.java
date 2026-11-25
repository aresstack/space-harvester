package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Preserve the existing native snapshot and size-limit behavior for ordinary sources. */
final class CachedOriginalStorage implements SnapshotStorageStrategy {

    private final BiFunction<HarvestSource, String, PageSnapshot> originals;
    private final Predicate<PageMetadata> loadsWhole;
    private final LongSupplier textLimit;
    private final SourceSnapshots.LargeFileText texts;

    CachedOriginalStorage(BiFunction<HarvestSource, String, PageSnapshot> originals,
                          Predicate<PageMetadata> loadsWhole, LongSupplier textLimit,
                          SourceSnapshots.LargeFileText texts) {
        this.originals = originals;
        this.loadsWhole = loadsWhole;
        this.textLimit = textLimit;
        this.texts = texts;
    }

    @Override
    public boolean supportsSource(HarvestSource source) {
        return true;
    }

    @Override
    public boolean supports(HarvestSource source, PageMetadata metadata) {
        return true;
    }

    @Override
    public PageSnapshot capture(HarvestSource source, PageMetadata metadata) {
        if (loadsWhole.test(metadata)) {
            return originals.apply(source, metadata.pageId());
        }
        long limit = textLimit.getAsLong();
        if (limit > 0 && LargeFiles.size(metadata) <= limit) {
            Optional<String> text = texts.text(source, metadata, limit);
            if (text.isPresent()) {
                return LargeFiles.textOnly(source.id(), metadata,
                        text.get().getBytes(StandardCharsets.UTF_8), Instant.now());
            }
        }
        return LargeFiles.placeholder(source.id(), metadata, Instant.now());
    }
}
