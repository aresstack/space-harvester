package com.aresstack.spaceharvester.app.ui.content;

import java.util.Objects;

/** Decide whether a verified source revision actually supersedes the displayed page. */
final class SourceRevision {

    private SourceRevision() {
    }

    static boolean hasUpdate(String displayedRevision, String checkedRevision) {
        return checkedRevision != null && !checkedRevision.isBlank()
                && !Objects.equals(displayedRevision, checkedRevision);
    }
}
