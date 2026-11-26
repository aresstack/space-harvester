package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.editor.*;
import com.aresstack.spaceharvester.deck.ftp.CobolAnalysisArea;
import com.aresstack.spaceharvester.deck.ftp.CobolInventory;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CobolSearchAcquisitionTest {
    private static final String PROGRAM = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. BOOKING.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                       COPY CUST.
                   PROCEDURE DIVISION.
                       GOBACK.
            """;

    @Test
    void usesInventoryScopeAndReadsSharedCopybookOnlyOnce() {
        String a = "'USER.COBOL(A)'", b = "'USER.COBOL(B)'";
        String copy = "'USER.COPY(CUST)'", later = "'USER.LATER(CUST)'";
        var area = new CobolAnalysisArea("USER.COBOL", List.of("USER.COPY", "USER.LATER"));
        var inventory = new CobolInventory.Result(area, List.of(
                new CobolInventory.Member(a, true, false), new CobolInventory.Member(b, true, false),
                new CobolInventory.Member(copy, false, true), new CobolInventory.Member(later, false, true)),
                Map.of(), List.of(), false);
        List<String> reads = new ArrayList<>();
        DocumentStore store = store(Map.of(a, PROGRAM, b, PROGRAM,
                copy, "       01 CUST-DATA PIC X(10).\n", later, "       01 OTHER-DATA PIC X.\n"), reads);
        var result = new CobolSearchAcquisition(store).build(inventory, () -> false, msg -> {});
        assertThat(reads).containsExactly(a, copy, b);
        assertThat(result.programs()).isEqualTo(2);
        assertThat(result.partial()).isZero();
        assertThat(result.search().search(CobolSearch.Mode.COPY_USAGE, "cust", 10, 0).hits())
                .hasSize(2).allSatisfy(f -> assertThat(f.attributes()).containsEntry("RESOLVED_SOURCE", copy));
        assertThat(result.search().search(CobolSearch.Mode.DATA, "cust-data", 10, 0).hits())
                .hasSize(2).allSatisfy(f -> {
                    assertThat(f.source()).isEqualTo(copy);
                    assertThat(f.attributes()).containsEntry("SOURCE_REVISION", "hash:" + copy);
                });
        var books = result.search().search(CobolSearch.Mode.COPYBOOK, "cust", 10, 0);
        assertThat(books.total()).isEqualTo(2);
        assertThat(books.hits()).anySatisfy(f -> {
            assertThat(f.source()).isEqualTo(copy);
            assertThat(f.kind()).isEqualTo(CobolSearch.Kind.COPYBOOK);
        }).anySatisfy(f -> {
            assertThat(f.source()).isEqualTo(later);
            assertThat(f.kind()).isEqualTo(CobolSearch.Kind.COPYBOOK_CANDIDATE);
        });
    }

    @Test
    void refusesIncompleteInventoryAndCancellationBeforeAnyReads() {
        var area = new CobolAnalysisArea("USER.COBOL", List.of());
        var member = new CobolInventory.Member("'USER.COBOL(A)'", true, false);
        List<String> reads = new ArrayList<>();
        var acquisition = new CobolSearchAcquisition(store(Map.of(), reads));
        var incomplete = new CobolInventory.Result(area, List.of(member), Map.of("USER.COPY", "error"),
                List.of(), false);
        assertThatThrownBy(() -> acquisition.build(incomplete, () -> false, msg -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        var complete = new CobolInventory.Result(area, List.of(member), Map.of(), List.of(), false);
        assertThatThrownBy(() -> acquisition.build(complete, () -> true, msg -> {}))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(reads).isEmpty();
    }

    private static DocumentStore store(Map<String, String> content, List<String> reads) {
        return new DocumentStore() {
            @Override public EditorDocument load(String location) throws DocumentException {
                reads.add(location);
                if (!content.containsKey(location)) throw new DocumentException("missing");
                return new EditorDocument(location, location, content.get(location), "UTF-8", "\n", "hash:" + location);
            }
            @Override public SaveResult save(EditorDocument loaded, String text, boolean overwrite) {
                throw new AssertionError("read-only");
            }
        };
    }
}
