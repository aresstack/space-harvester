package com.aresstack.spaceharvester.application.quality;

import java.util.Optional;

/**
 * Löst eine Seitenadresse (Browser oder App) in {@code quelle:pageId} auf; in der App über die eingetragenen Quellen
 * und {@link com.aresstack.spaceharvester.application.query.PageUrls}.
 */
@FunctionalInterface
public interface PageResolver {

    /** Kein Auflösen: Adressen sind dann Zeilenfehler. */
    PageResolver NONE = url -> Optional.empty();

    Optional<PageRef> resolve(String url);
}
