package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.content.PageDetailPanel;

/** Distinguish a document destination from a submitted search and its scope. */
sealed interface WorkspaceLocation {
    String label();

    record Document(PageDetailPanel.Place place) implements WorkspaceLocation {
        @Override public String label() {
            return place.title() != null && !place.title().isBlank() ? place.title()
                    : place.page() ? "Seite " + place.pageId()
                    : place.spaceKey() == null ? place.sourceId() : place.spaceKey();
        }

        @Override public boolean equals(Object value) {
            return value instanceof Document other
                    && java.util.Objects.equals(place.sourceId(), other.place.sourceId())
                    && java.util.Objects.equals(place.spaceKey(), other.place.spaceKey())
                    && java.util.Objects.equals(place.pageId(), other.place.pageId());
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(place.sourceId(), place.spaceKey(), place.pageId());
        }
    }

    record Search(SearchView.SearchLocation location) implements WorkspaceLocation {
        @Override public String label() {
            return location.label();
        }
    }
}
