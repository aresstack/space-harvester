package com.aresstack.spaceharvester.mainframe.parser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis.Kind.*;
import static com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis.Resolution.*;

class NaturalAnalyzerTest {
    @Test
    void preservesNestedDeclarationsAndDistinguishesLiteralDynamicAndLocalTargets() {
        var analysis = new NaturalAnalyzer().analyze("LIB", "DEMO", "NSP", """
                DEFINE DATA
                LOCAL USING LDA1
                LOCAL
                1 GROUP-A
                  2 GROUP-B
                    3 #FIELD (A10)
                1 V VIEW OF CUSTOMER
                  2 ID
                END-DEFINE
                CALLNAT 'SUB1' #FIELD /* comment
                CALLNAT #TARGET
                PERFORM ROUTINE
                READ V
                END-READ
                READ WORK FILE 1 #FIELD
                DEFINE SUBROUTINE ROUTINE
                END-SUBROUTINE
                END
                """);
        assertThat(analysis.elements()).anySatisfy(element -> {
            assertThat(element.name()).isEqualTo("#FIELD");
            assertThat(element.line()).isEqualTo(6);
            assertThat(element.parentLine()).isEqualTo(5);
        });
        assertThat(analysis.dependencies()).anyMatch(d -> d.kind() == CALLNAT && d.target().equals("SUB1") && d.resolution() == LITERAL)
                .anyMatch(d -> d.kind() == CALLNAT && d.target().equals("#TARGET") && d.resolution() == DYNAMIC)
                .anyMatch(d -> d.kind() == PERFORM && d.declarationLine() == 16 && d.resolution() == LOCAL_DECLARATION)
                .anyMatch(d -> d.kind() == DATABASE && d.target().equals("V") && d.declarationLine() == 7)
                .anyMatch(d -> d.kind() == WORK_FILE && d.target().equals("1"))
                .anyMatch(d -> d.kind() == DATA_AREA && d.target().equals("LDA1"));
        assertThat(analysis.diagnostics()).isNotEmpty();
    }

    @Test
    void ddmFieldsKeepTheirActualListingLinesAndDescriptorInformation() {
        var analysis = new NaturalAnalyzer().analyze("FDDM", "EMPLOYEES", "NSD", """
                DB: 010 FILE: 001  - EMPLOYEES                   DEFAULT SEQUENCE: AA

                  T L  DB ---- NAME -------------------  F  LENG  S D REMARK
                  - --  -- -------------------------------- ----- - -  ------
                  * 1  AA PERSONNEL-ID                   A     8
                  * 1  AB FIRST-NAME                     A    20
                """);
        assertThat(analysis.elements()).anySatisfy(element -> {
            assertThat(element.kind()).isEqualTo("DDM_HEADER");
            assertThat(element.attributes()).containsEntry("DB", "10").containsEntry("FNR", "1");
        }).anySatisfy(element -> {
            assertThat(element.name()).isEqualTo("PERSONNEL-ID");
            assertThat(element.line()).isEqualTo(5);
            assertThat(element.attributes()).containsEntry("SHORT_NAME", "AA");
        });
    }

    @Test
    void commentsInsideQuotedOperandsAreNotStrippedAndCommentCardsAreIgnored() {
        var analysis = new NaturalAnalyzer().analyze("LIB", "DEMO", "NSP", """
                ** CALLNAT 'FAKE'
                /* CALLNAT 'FAKE'
                CALLNAT 'A/*B'
                CALLNAT 'REAL' /* CALLNAT 'FAKE'
                END
                """);
        assertThat(analysis.dependencies()).extracting(NaturalAnalysis.Dependency::target)
                .containsExactly("A/*B", "REAL");
    }
}
