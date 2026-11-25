package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.io.IOException;

/** Signal an expired or denied image session without exposing the requested URL. */
final class MediaWikiImageAccessException extends IOException {
    MediaWikiImageAccessException(int status) {
        super("MediaWiki image access denied (HTTP " + status + ")");
    }
}
