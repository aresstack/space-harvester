package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CobolSearchTest {
    private static final String PROGRAM = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. DEMO.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                       COPY CUSTOMER.
                   77 WS-PGM PIC X(8).
                   PROCEDURE DIVISION.
                   MAIN-PARA.
                       CALL 'BOOKING'.
                       CALL WS-PGM.
                       GOBACK.
            """;
    private static final String CUSTOMER = """
                   01 CUSTOMER-DATA.
                      05 CUST-ID PIC 9(8).
                      COPY ADDRESS.
            """;
    private static final String ADDRESS = "           05 CITY PIC X(20).\n";

    @Test
    void findsNestedCopyUsesDataAndStaticCallersWithPhysicalProvenance() {
        String member = "'USER.COBOL(DEMO)'";
        String customer = "'USER.COPY(CUSTOMER)'";
        String address = "'USER.COPY(ADDRESS)'";
        var analysis = CobolAnalyzer.proLeap().analyze(CobolSource.fixed(member, PROGRAM),
                (including, name, library) -> switch (name) {
                    case "CUSTOMER" -> Optional.of(CobolSource.fixed(customer, CUSTOMER));
                    case "ADDRESS" -> Optional.of(CobolSource.fixed(address, ADDRESS));
                    default -> Optional.empty();
                });
        assertThat(analysis.diagnostics()).isEmpty();
        var search = new CobolSearch(CobolSearch.programFacts(member, "rev", analysis));
        assertThat(search.search(CobolSearch.Mode.PROGRAM, "demo", 10, 0).hits()).singleElement()
                .satisfies(f -> assertThat(f.member()).isEqualTo(member));
        assertThat(search.search(CobolSearch.Mode.COPYBOOK, "address", 10, 0).hits()).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(CobolSearch.Kind.COPYBOOK);
                    assertThat(f.source()).isEqualTo(address);
                });
        assertThat(search.search(CobolSearch.Mode.COPY_USAGE, "address", 10, 0).hits()).singleElement()
                .satisfies(f -> {
                    assertThat(f.member()).isEqualTo(member);
                    assertThat(f.source()).isEqualTo(customer);
                    assertThat(f.line()).isEqualTo(3);
                    assertThat(f.attributes()).containsEntry("RESOLVED_SOURCE", address)
                            .containsEntry("REFERENCE_SCOPE", "NESTED");
                });
        assertThat(search.search(CobolSearch.Mode.DATA, "CITY", 10, 0).hits()).singleElement()
                .satisfies(f -> {
                    assertThat(f.source()).isEqualTo(address);
                    assertThat(f.attributes()).containsEntry("PIC", "X(20)")
                            .containsEntry("QUALIFIED_NAME", "CITY OF CUSTOMER-DATA");
                });
        assertThat(search.search(CobolSearch.Mode.CALLERS, "booking", 10, 0).hits()).singleElement()
                .satisfies(f -> assertThat(f.member()).isEqualTo(member));
        assertThat(search.search(CobolSearch.Mode.CALLERS, "WS-PGM", 10, 0).total()).isZero();
        assertThat(search.search(CobolSearch.Mode.CALLEES, "DEMO", 10, 0).hits())
                .extracting(CobolSearch.Fact::kind).containsExactly(CobolSearch.Kind.CALL, CobolSearch.Kind.DYNAMIC_CALL);
    }

    @Test
    void retainsPartialCopyReferencesAndPaginatesAcrossDistinctMembers() {
        List<CobolSearch.Fact> facts = new ArrayList<>();
        for (String member : List.of("A.COBOL(DEMO)", "B.COBOL(DEMO)")) {
            var analysis = CobolAnalyzer.proLeap().analyze(CobolSource.fixed(member, PROGRAM),
                    (including, name, library) -> Optional.empty());
            facts.addAll(CobolSearch.programFacts(member, "rev", analysis));
        }
        var search = new CobolSearch(facts);
        var result = search.search(CobolSearch.Mode.COPY_USAGE, "customer", 1, 0);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.hits()).singleElement().satisfies(f -> {
            assertThat(f.partial()).isTrue();
            assertThat(f.attributes()).doesNotContainKey("RESOLVED_SOURCE");
        });
        assertThat(search.search(CobolSearch.Mode.COPY_USAGE, "customer", 1, 1).hits())
                .extracting(CobolSearch.Fact::member).containsExactly("B.COBOL(DEMO)");
        assertThatThrownBy(() -> search.search(CobolSearch.Mode.ALL, "", 501, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
