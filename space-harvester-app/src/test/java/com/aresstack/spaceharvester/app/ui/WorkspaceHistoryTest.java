package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceHistoryTest {
    @Test
    void travelsInBothDirectionsAcrossDocumentsSearchesAndFilters() {
        List<String> restored = new ArrayList<>();
        WorkspaceHistory<String> history = new WorkspaceHistory<>(value -> value, restored::add);
        history.visit("Document A");
        history.preview();
        assertThat(history.history().back()).containsExactly("Document A");
        history.visit("Search X");
        history.visit("Search X / Source Y");
        history.visit("Search Z");
        history.visit("Document B");
        assertThat(history.history().back()).containsExactly("Search Z", "Search X / Source Y", "Search X", "Document A");
        history.go(-1);
        history.go(-2);
        history.go(-1);
        history.go(2);
        assertThat(restored).containsExactly("Search Z", "Search X", "Document A", "Search X / Source Y");
        assertThat(history.history().forward()).containsExactly("Search Z", "Document B");
        history.visit("Search W");
        assertThat(history.history().forward()).isEmpty();
        assertThat(history.history().back()).containsExactly("Search X / Source Y", "Search X", "Document A");
    }

    @Test
    void returningFromAnUnsubmittedSearchKeepsTheCurrentDocument() {
        List<String> restored = new ArrayList<>();
        WorkspaceHistory<String> history = new WorkspaceHistory<>(value -> value, restored::add);
        history.visit("Document");
        history.preview();
        history.go(-1);
        assertThat(restored).containsExactly("Document");
        assertThat(history.history().back()).isEmpty();
        history.visit("Document");
        assertThat(history.history().back()).isEmpty();
    }
}
