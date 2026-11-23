package com.aresstack.spaceharvester.deck.ftp.jes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Spool-Aufbereitung wie in MainframeMate (erfundene Jobs). */
class SpoolTextTest {

    private static final String JESJCL = String.join("\n",
            "        1 //ORBIT01  JOB (ACCT),'ORBIT',CLASS=A,MSGCLASS=X                       J0123456",
            "          //* KOMMENTAR",
            "        2 //STEP1    EXEC PROC=STARTPRC",
            "        3 XXSTARTPRC PROC",
            "        4 XXSTEP     EXEC PGM=IEFBR14",
            "          IEFC653I SUBSTITUTION JCL - DSN=KKR.DATA",
            "          FORTSETZUNG DER MELDUNG",
            "        5 //SYSIN    DD *",
            "        6 //SYSOUT   DD SYSOUT=*");

    @Test
    void normalisesJesJclToResubmittableJcl() {
        String clean = SpoolText.normalizeJcl(JESJCL);
        assertThat(clean).isEqualTo(String.join("\n",
                "//ORBIT01  JOB (ACCT),'ORBIT',CLASS=A,MSGCLASS=X",
                "//* KOMMENTAR",
                "//STEP1    EXEC PROC=STARTPRC",
                "//SYSIN    DD *",
                "//* << instream data omitted by JES2 >>",
                "//SYSOUT   DD SYSOUT=*"));
        assertThat(SpoolText.normalizeJcl("kein Spool\nnur Text")).isEqualTo("kein Spool\nnur Text");
    }

    @Test
    void removesControlCharactersButKeepsLines() {
        assertThat(SpoolText.sanitize("A\u0000B\fC\u0001\tD\r\n")).isEqualTo("AB\nC\tD\r\n");
        assertThat(SpoolText.sanitize(null)).isEmpty();
    }

    @Test
    void detectsTheLanguage() {
        assertThat(SpoolText.detect("irgendwas", "JESJCL", SpoolText.Language.AUTO)).isEqualTo(SpoolText.Language.JCL);
        assertThat(SpoolText.detect("//A JOB X\n//S EXEC PGM=B", null, null)).isEqualTo(SpoolText.Language.JCL);
        assertThat(SpoolText.detect("       IDENTIFICATION DIVISION.", null, null))
                .isEqualTo(SpoolText.Language.COBOL);
        assertThat(SpoolText.detect("DEFINE DATA LOCAL\nEND-DEFINE", null, null))
                .isEqualTo(SpoolText.Language.NATURAL);
        assertThat(SpoolText.detect("Hallo", null, null)).isEqualTo(SpoolText.Language.PLAIN);
        assertThat(SpoolText.detect("Hallo", null, SpoolText.Language.COBOL)).isEqualTo(SpoolText.Language.COBOL);
        assertThat(SpoolText.count("IEF a ief b", "IEF")).isEqualTo(2);
    }

    @Test
    void stripsTheJobNumberOnlyOnTheJobCard() {
        assertThat(SpoolText.stripJobNumber("//ORBIT01  JOB (ACCT),'X'    J0123456")).isEqualTo("//ORBIT01  JOB (ACCT),'X'");
        assertThat(SpoolText.stripJobNumber("//S1 EXEC PGM=X              J0123456")).endsWith("J0123456");
        assertThat(SpoolText.isInstreamDataDd("//SYSIN DD DATA,DLM=@@")).isTrue();
    }
}
