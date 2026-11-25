package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MediaWikiTitleBatch {
    final List<String> titles;
    final String continuation;

    MediaWikiTitleBatch(List<String> titles, String continuation) {
        this.titles = Collections.unmodifiableList(new ArrayList<String>(titles));
        this.continuation = continuation;
    }
}
