package com.aresstack.spaceharvester.deck.ftp.jes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JesListingTest {

    @Test
    void finishedJobWithReturnCode() {
        JesJob job = JesListing.parseJob("BUILD    JOB04700 KKR      OUTPUT A        RC=0000 3 spool files");
        assertThat(job.jobId()).isEqualTo("JOB04700");
        assertThat(job.jobName()).isEqualTo("BUILD");
        assertThat(job.owner()).isEqualTo("KKR");
        assertThat(job.status()).isEqualTo("OUTPUT");
        assertThat(job.jobClass()).isEqualTo("A");
        assertThat(job.returnCode()).contains("0000");
        assertThat(job.spoolFiles()).isEqualTo(3);
        assertThat(job.failed()).isFalse();
    }

    @Test
    void abendAndJclErrorAndRunningJobs() {
        assertThat(JesListing.parseJob("STERNE   JOB04701 KKR      OUTPUT A        ABEND=S0C4 2 spool files")
                .returnCode()).contains("ABEND S0C4");
        assertThat(JesListing.parseJob("STERNE   JOB04703 KKR      OUTPUT A        (JCL error) 1 spool files")
                .returnCode()).contains("JCL ERROR");
        JesJob running = JesListing.parseJob("KOMET    JOB04702 KKR      ACTIVE B");
        assertThat(running.status()).isEqualTo("ACTIVE");
        assertThat(running.jobClass()).isEqualTo("B");
        assertThat(running.returnCode()).isEmpty();
        assertThat(JesListing.parseJob("KOMET    J0012345 KKR      INPUT").jobId()).isEqualTo("J0012345");
    }

    @Test
    void headersAreSkipped() {
        assertThat(JesListing.parseJob("JOBNAME  JOBID    OWNER    STATUS CLASS")).isNull();
        // Jobs dürfen trotzdem so heißen wie eine Kopfzeile beginnt
        assertThat(JesListing.parseJob("OWNER01  JOB04700 KKR      OUTPUT A        RC=0000 3 spool files").jobName())
                .isEqualTo("OWNER01");
        assertThat(JesListing.parseJob("JOBNAME1 JOB04701 KKR      ACTIVE B").jobName()).isEqualTo("JOBNAME1");
        assertThat(JesListing.parseSpool("         ID  STEPNAME PROCSTEP C DDNAME   BYTE-COUNT")).isNull();
        assertThat(JesListing.parseSpool("--------")).isNull();
        assertThat(JesListing.parseSpool("3 spool files")).isNull();
    }

    @Test
    void spoolLinesInTheFormatsMainframeMateKnows() {
        SpoolFile full = JesListing.parseSpool("002 JES2     N/A  H JESJCL      1,234    20");
        assertThat(full.ddName()).isEqualTo("JESJCL");
        assertThat(full.stepName()).isEqualTo("JES2");
        assertThat(full.procStep()).isEqualTo("N/A");
        assertThat(full.outputClass()).isEqualTo("H");
        assertThat(full.bytes()).isEqualTo(1234);
        assertThat(full.records()).isEqualTo(20);
        assertThat(JesListing.parseSpool("002 JES2     N/A  H JESJCL      1234").records()).isZero();
    }

    @Test
    void spoolLineWithoutProcStepKeepsTheDdName() {
        // MainframeMate nahm hier die Byte-Zahl als DD-Namen.
        SpoolFile file = JesListing.parseSpool("         004 STEP1             A SYSPRINT       1102");
        assertThat(file.id()).isEqualTo(4);
        assertThat(file.stepName()).isEqualTo("STEP1");
        assertThat(file.procStep()).isEmpty();
        assertThat(file.ddName()).isEqualTo("SYSPRINT");
        assertThat(file.bytes()).isEqualTo(1102);
        assertThat(file.label()).isEqualTo("STEP1 · SYSPRINT");
    }

    @Test
    void sectionsOfTheWholeOutput() {
        String whole = "J E S 2  J O B  L O G\nline\n !! END OF JES SPOOL FILE !!\n//BUILD JOB\n"
                + " !! END OF JES SPOOL FILE !!\nIDCAMS  SYSTEM SERVICES\nIDC0001I\n";
        List<SpoolFile> sections = JesListing.sections(whole);
        assertThat(sections).extracting(SpoolFile::ddName).containsExactly("JESMSGLG", "JESJCL", "SYSPRINT");
        assertThat(JesListing.sectionText(whole, 2)).isEqualTo("//BUILD JOB");
        assertThat(JesListing.sectionText(whole, 9)).isEmpty();
    }

    @Test
    void ddNamesFromReplyAndContent() {
        assertThat(JesListing.ddNameFromReply("125 Sending data set KKR.BUILD.JOB04700.D0000002.?.JESJCL", 2))
                .isEqualTo("JESJCL");
        assertThat(JesListing.ddNameFromReply("250 Transfer completed", 7)).isEqualTo("SPOOL#7");
        assertThat(JesListing.ddNameFromContent("STMT NO. MESSAGE\n1 IEF236I ALLOC", 3)).isEqualTo("JESYSMSG");
    }

    @Test
    void submitReplyAndJobCard() {
        assertThat(JesListing.submittedJobId("250-It is known to JES as JOB04711\n250 Transfer completed"))
                .contains("JOB04711");
        assertThat(JesListing.submittedJobId("250 Transfer completed")).isEmpty();
        assertThat(JesListing.jobName("//* Kommentar\n//orbit1   JOB (ACCT)\n")).contains("ORBIT1");
    }
}
