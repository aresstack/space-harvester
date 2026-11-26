package com.aresstack.spaceharvester.mainframe.parser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.aresstack.spaceharvester.mainframe.parser.JclAnalysis.Kind.*;
import static com.aresstack.spaceharvester.mainframe.parser.JclAnalysis.Resolution.*;

class JclAnalyzerTest {
    @Test
    void preservesLogicalCardsAndDatasetParameters() {
        var analysis = new JclAnalyzer().analyze("JOB1", """
                //JOB1 JOB (ACCT),'A USER'
                //S1 EXEC PGM=DEMO,
                //         PARM='A,B (C)'
                //IN DD DSN=APP.DATA.INPUT,
                //      DISP=(OLD,KEEP)
                //   DD DSNAME=APP.DATA.SECOND,DISP=SHR
                //OUT DD DSN=APP.DATA.OUT(+1),DISP=(NEW,CATLG,DELETE)
                //SYSOUT DD SYSOUT=*
                //
                """);
        assertThat(analysis.diagnostics()).isEmpty();
        assertThat(analysis.statements().get(1).endLine()).isEqualTo(3);
        assertThat(analysis.statements().get(1).parameters()).containsEntry("PARM", "'A,B (C)'");
        assertThat(analysis.dependencies()).anySatisfy(dep -> {
            assertThat(dep.kind()).isEqualTo(DATASET);
            assertThat(dep.target()).isEqualTo("APP.DATA.SECOND");
            assertThat(dep.ddName()).isEqualTo("IN");
            assertThat(dep.stepLine()).isEqualTo(2);
        }).anySatisfy(dep -> {
            assertThat(dep.target()).isEqualTo("APP.DATA.OUT(+1)");
            assertThat(dep.disposition()).isEqualTo("(NEW,CATLG,DELETE)");
        });
    }

    @Test
    void doesNotInterpretInstreamDataAsJcl() {
        var analysis = new JclAnalyzer().analyze("DECK", """
                //J JOB
                //S EXEC PGM=IDCAMS
                //SYSIN DD DATA,DLM='@@'
                //FAKE EXEC PGM=WRONG
                /*
                @@
                //NEXT EXEC PGM=GOOD
                //SYSIN DD *
                DATA
                //AFTER EXEC PGM=ENDPGM
                //
                """);
        assertThat(analysis.dependencies().stream().filter(d -> d.kind() == PROGRAM).map(JclAnalysis.Dependency::target))
                .containsExactly("IDCAMS", "GOOD", "ENDPGM");
    }

    @Test
    void sharedOutlineUsesLogicalCardsAndRejectsIncompleteTargets() {
        String code = """
                //J JOB
                //S EXEC PGM=DEMO,
                //         PARM='A,B'
                //IN DD DSN=APP.LONG.DATASET,DISP=SHR
                //SYSIN DD *
                //FAKE EXEC PGM=WRONG
                /*
                //BAD EXEC PGM=BADPGM,
                //NEXT EXEC PGM=GOOD
                """.replace("//FAKE EXEC PGM=WRONG", "FAKE EXEC PGM=WRONG");
        var analysis = new JclAnalyzer().analyze("J", code);
        assertThat(analysis.dependencies().stream().filter(dep -> dep.kind() == PROGRAM)
                .map(JclAnalysis.Dependency::target)).containsExactly("DEMO", "GOOD");
        assertThat(analysis.partial()).isTrue();
        var outline = new AntlrJclParser().parse(code, "J");
        assertThat(outline.getElements()).anySatisfy(element -> {
            assertThat(element.getName()).isEqualTo("S");
            assertThat(element.getEndLineNumber()).isEqualTo(3);
            assertThat(element.getParameter("PARM")).isEqualTo("A,B");
        }).anySatisfy(element -> {
            assertThat(element.getName()).isEqualTo("IN");
            assertThat(element.getParameter("DSN")).isEqualTo("APP.LONG.DATASET");
            assertThat(element.getParent().getName()).isEqualTo("S");
        });
        assertThat(outline.getElements()).noneMatch(element -> "BADPGM".equals(element.getParameter("PGM")));
    }

    @Test
    void keepsProcedureScopesAndConditionalStepsSeparate() {
        var analysis = new JclAnalyzer().analyze("DECK", """
                //J JOB
                //P PROC ENV=TEST
                //PSTEP EXEC PGM=INNER
                // PEND
                // JCLLIB ORDER=(APP.PROC.ONE,APP.PROC.TWO)
                // SET TARGET=DEMO
                // IF (RC = 0) THEN
                //S1 EXEC P
                // ELSE
                //S2 EXEC PGM=&TARGET
                // ENDIF
                // INCLUDE MEMBER=EXTRA
                //S3 EXEC PROC=EXTERNAL
                //DD1 DD DSN=&&TEMP,DISP=(NEW,PASS)
                //DD2 DD DSN=*.S1.IN
                """);
        assertThat(analysis.dependencies()).anySatisfy(dep -> {
            assertThat(dep.kind()).isEqualTo(PROCEDURE);
            assertThat(dep.target()).isEqualTo("P");
            assertThat(dep.resolution()).isEqualTo(LOCAL_DECLARATION);
            assertThat(dep.declarationLine()).isEqualTo(2);
        }).anySatisfy(dep -> {
            assertThat(dep.target()).isEqualTo("&TARGET");
            assertThat(dep.resolution()).isEqualTo(SYMBOLIC);
        });
        assertThat(analysis.dependencies().stream().filter(d -> d.kind() == PROCEDURE_LIBRARY)
                .map(JclAnalysis.Dependency::target)).containsExactly("APP.PROC.ONE", "APP.PROC.TWO");
        assertThat(analysis.statements().stream().filter(s -> s.name().equals("S1")).findFirst().orElseThrow().guards())
                .singleElement().satisfies(g -> assertThat(g.otherwise()).isFalse());
        assertThat(analysis.statements().stream().filter(s -> s.name().equals("S2")).findFirst().orElseThrow().guards())
                .singleElement().satisfies(g -> assertThat(g.otherwise()).isTrue());
        assertThat(analysis.partial()).isTrue();
        assertThat(analysis.dependencies()).anyMatch(d -> d.kind() == TEMPORARY_DATASET)
                .anyMatch(d -> d.kind() == DATASET_REFERENCE);
    }
}
