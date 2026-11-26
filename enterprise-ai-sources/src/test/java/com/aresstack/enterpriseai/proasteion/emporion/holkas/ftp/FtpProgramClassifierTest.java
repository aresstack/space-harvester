package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FtpProgramClassifierTest {

    @Test
    public void recognizesFixedAndFreeFormatCobol() {
        assertEquals(FtpProgramClassifier.Kind.COBOL,
                FtpProgramClassifier.classify("000100 IDENTIFICATION DIVISION.\n000200 PROGRAM-ID. TEST."));
        assertEquals(FtpProgramClassifier.Kind.COBOL,
                FtpProgramClassifier.classify("identification division.\nprogram-id. test."));
    }

    @Test
    public void recognizesCopybooksWithoutProgramHeaders() {
        assertEquals(FtpProgramClassifier.Kind.COPYBOOK,
                FtpProgramClassifier.classify("       01 RECORD-A.\n          05 FIELD-A PIC X(20)."));
    }

    @Test
    public void recognizesJobsAndProceduresIncludingInStreamCobol() {
        assertEquals(FtpProgramClassifier.Kind.JCL,
                FtpProgramClassifier.classify("//RUN JOB CLASS=A\n//STEP EXEC PGM=COBOL\n"
                        + "//SYSIN DD *\nIDENTIFICATION DIVISION."));
        assertEquals(FtpProgramClassifier.Kind.JCL, FtpProgramClassifier.classify("//BUILD PROC"));
    }

    @Test
    public void rejectsCommentOnlyAndOrdinaryText() {
        assertEquals(FtpProgramClassifier.Kind.UNKNOWN,
                FtpProgramClassifier.classify("000100*IDENTIFICATION DIVISION.\n//*RUN JOB CLASS=A"));
        assertEquals(FtpProgramClassifier.Kind.UNKNOWN,
                FtpProgramClassifier.classify("The PROGRAM-ID is a COBOL keyword.\n// some comment"));
    }
}