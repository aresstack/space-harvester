package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost;
import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.RemoteFile;
import com.aresstack.spaceharvester.deck.ftp.WriteConflictException;
import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.JobOutput;
import com.aresstack.spaceharvester.deck.ftp.jes.JobStatus;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import com.aresstack.spaceharvester.deck.ftp.jes.SubmittedJob;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Echte FTP-Gespräche gegen den Schein-z/OS-Host. */
class CommonsNetFtpConnectorTest {

    private final CommonsNetFtpConnector connector = new CommonsNetFtpConnector();
    private FakeMvsFtpHost host;

    @BeforeEach
    void start() throws Exception {
        host = FakeMvsFtpHost.sample();
    }

    @AfterEach
    void stop() throws Exception {
        host.close();
    }

    private FileConnection files() throws Exception {
        return connector.files(host.profile(), "KKR", "geheim".toCharArray());
    }

    private JesConnection jes() throws Exception {
        return connector.jes(host.profile(), "KKR", "geheim".toCharArray());
    }

    @Test
    void recognisesMvsAndStartsAtTheUsersHlq() throws Exception {
        try (FileConnection files = files()) {
            assertThat(files.mvs()).isTrue();
            assertThat(files.home()).isEqualTo("'KKR'");
        }
        assertThat(host.commands()).contains("STRU R");
    }

    @Test
    void listsDatasetsBelowAnHlqAndMembersOfAPds() throws Exception {
        try (FileConnection files = files()) {
            List<RemoteEntry> top = files.list("'KKR'");
            assertThat(top).extracting(RemoteEntry::name).containsExactly("DATA", "JCL", "SRC");
            assertThat(top).allMatch(RemoteEntry::directory);
            assertThat(top).extracting(RemoteEntry::path).contains("'KKR.JCL'", "'KKR.SRC'");

            List<RemoteEntry> members = files.list("'KKR.JCL'");
            assertThat(members).extracting(RemoteEntry::name).containsExactly("BUILD", "STERNE");
            assertThat(members).noneMatch(RemoteEntry::directory);
            assertThat(members.get(0).path()).isEqualTo("'KKR.JCL(BUILD)'");
        }
    }

    @Test
    void sequentialDatasetsAreFilesThatCanBeRead() throws Exception {
        try (FileConnection files = files()) {
            List<RemoteEntry> data = files.list("'KKR.DATA'");
            assertThat(data).extracting(RemoteEntry::name).containsExactly("LOG");
            // Wie MainframeMate: ein Dataset steht als Dataset da; erst beim Öffnen entscheidet NLST 'DS(*)'.
            assertThat(data.get(0).directory()).isTrue();
            assertThat(files.opensAsFolder(data.get(0))).isFalse();
            assertThat(files.read(data.get(0).path()).text()).isEqualTo("Grüße an Bord\nZeile zwei");
            // Partitionierte Datasets öffnen als Liste ihrer Members
            List<RemoteEntry> src = files.list("'KKR.SRC'");
            assertThat(src).extracting(RemoteEntry::name, RemoteEntry::directory)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("COBOL", true));
            assertThat(files.opensAsFolder(src.get(0))).isTrue();
            assertThat(files.list(src.get(0).path())).extracting(RemoteEntry::directory).containsOnly(false);
        }
    }

    @Test
    void aPartitionedDatasetOpensLikeAFolderOfItsMembersAsInMainframeMate() throws Exception {
        // Der Scheinhost antwortet wie der echte: NLST 'KKR.JCL' ohne (*) nennt nur das Dataset selbst. Früher stand
        // dann „KKR.JCL“ als einziger Eintrag da; wie MvsListingService fragt die Liste zuerst NLST 'KKR.JCL(*)'.
        try (FileConnection files = files()) {
            RemoteEntry jcl = files.list("'KKR'").stream().filter(entry -> entry.name().equals("JCL")).findFirst()
                    .orElseThrow();
            assertThat(jcl.path()).isEqualTo("'KKR.JCL'");
            assertThat(files.opensAsFolder(jcl)).isTrue();
            List<RemoteEntry> members = files.list(jcl.path());
            assertThat(members).extracting(RemoteEntry::name).containsExactly("BUILD", "STERNE");
            assertThat(members).extracting(RemoteEntry::path)
                    .containsExactly("'KKR.JCL(BUILD)'", "'KKR.JCL(STERNE)'");
            assertThat(members).extracting(RemoteEntry::directory).containsOnly(false);
            assertThat(files.opensAsFolder(members.get(0))).isFalse();
        }
    }

    @Test
    void theRootCannotBeListed() throws Exception {
        try (FileConnection files = files()) {
            assertThatThrownBy(() -> files.list("''")).isInstanceOf(FtpReplyException.class).hasMessageContaining("HLQ");
        }
    }

    @Test
    void readsRecordsWithoutPaddingAndWithUmlauts() throws Exception {
        try (FileConnection files = files()) {
            RemoteFile member = files.read("'KKR.JCL(BUILD)'");
            assertThat(member.text()).isEqualTo("//BUILD    JOB (ACCT),'ORBIT',CLASS=A\n//STEP1    EXEC PGM=IEFBR14");
            assertThat(files.read("KKR.DATA.LOG").text()).isEqualTo("Grüße an Bord\nZeile zwei");
            // DATASET.MEMBER wird zuerst als DATASET(MEMBER) versucht
            assertThat(files.read("KKR.JCL.STERNE").path()).isEqualTo("'KKR.JCL(STERNE)'");
        }
    }

    @Test
    void refusesTextTheCharsetCannotHoldAndWritesNothing() throws Exception {
        try (FileConnection files = files()) {
            assertThatThrownBy(() -> files.write("'KKR.JCL(BUILD)'", "//BUILD JOB\n* 5 €"))
                    .isInstanceOf(com.aresstack.spaceharvester.deck.ftp.TextEncodingException.class)
                    .hasMessageContaining("€");
            assertThat(host.text("KKR.JCL(BUILD)")).startsWith("//BUILD    JOB (ACCT)");
            assertThat(files.alive()).isTrue();
        }
    }

    @Test
    void writesMembersRecordByRecordAndDetectsConflicts() throws Exception {
        try (FileConnection files = files()) {
            files.write("'KKR.JCL(NEU)'", "//NEU      JOB\n//S1  EXEC PGM=IEFBR14   ");
            assertThat(host.text("KKR.JCL(NEU)")).isEqualTo("//NEU      JOB\n//S1  EXEC PGM=IEFBR14");
            RemoteFile read = files.read("'KKR.JCL(NEU)'");
            files.writeIfUnchanged("'KKR.JCL(NEU)'", "//NEU JOB", read.hash());
            assertThat(host.text("KKR.JCL(NEU)")).isEqualTo("//NEU JOB");
            assertThatThrownBy(() -> files.writeIfUnchanged("'KKR.JCL(NEU)'", "x", read.hash()))
                    .isInstanceOf(WriteConflictException.class);
            assertThatThrownBy(() -> files.write("'KKR.FEHLT(X)'", "x")).isInstanceOf(FtpReplyException.class);
        }
    }

    @Test
    void createsAndDeletes() throws Exception {
        try (FileConnection files = files()) {
            files.createDirectory("'KKR.NEU.PDS'");
            assertThat(host.datasetExists("KKR.NEU.PDS")).isTrue();
            files.delete("'KKR.JCL(STERNE)'");
            assertThat(host.text("KKR.JCL(STERNE)")).isNull();
            assertThatThrownBy(() -> files.delete("'KKR.JCL(STERNE)'")).isInstanceOf(FtpReplyException.class);
            assertThat(files.alive()).isTrue();
        }
    }

    @Test
    void wrongPasswordIsARefusalNotAConnectionError() {
        assertThatThrownBy(() -> connector.files(host.profile(), "KKR", "falsch".toCharArray()))
                .isInstanceOf(FtpReplyException.class).hasMessageContaining("Anmeldung");
    }

    @Test
    void listsAllJobsIncludingRunningOnes() throws Exception {
        try (JesConnection jes = jes()) {
            List<JesJob> jobs = jes.jobs(JobFilter.mine());
            assertThat(jobs).extracting(JesJob::jobId).containsExactly("JOB04700", "JOB04701", "JOB04702");
            assertThat(jobs.get(2).status()).isEqualTo("ACTIVE");
            assertThat(jes.jobs(new JobFilter("", "*", JobStatus.ACTIVE))).extracting(JesJob::jobName)
                    .containsExactly("KOMET");
            assertThat(jes.jobs(new JobFilter("", "ST*", JobStatus.ALL))).extracting(JesJob::jobName)
                    .containsExactly("STERNE");
            assertThat(jes.jobs(new JobFilter("NIEMAND", "*", JobStatus.ALL))).isEmpty();
        }
        assertThat(host.commands()).contains("SITE FILETYPE=JES", "SITE JESOWNER=KKR", "SITE JESSTATUS=ACTIVE");
    }

    @Test
    void spoolListAndSpoolText() throws Exception {
        try (JesConnection jes = jes()) {
            JobOutput output = JobOutput.load(jes, "JOB04700");
            assertThat(output.whole()).isEmpty();
            assertThat(output.files()).extracting(SpoolFile::ddName).containsExactly("JESMSGLG", "JESJCL", "SYSPRINT");
            assertThat(output.files().get(2).stepName()).isEqualTo("STEP1");
            assertThat(output.text(jes, "JOB04700", output.files().get(2)))
                    .contains("IDC0001I FUNCTION COMPLETED");
        }
    }

    @Test
    void withoutSpoolListTheSectionsComeFromTheWholeOutput() throws Exception {
        host.jesInterfaceLevel1();
        try (JesConnection jes = jes()) {
            JobOutput output = JobOutput.load(jes, "JOB04700");
            assertThat(output.whole()).isPresent();
            assertThat(output.files()).extracting(SpoolFile::ddName).containsExactly("JESMSGLG", "JESJCL", "SYSPRINT");
            assertThat(output.text(jes, "JOB04700", output.files().get(1))).contains("EXEC PGM=IEFBR14");
        }
    }

    @Test
    void submitsAndDeletesJobs() throws Exception {
        try (JesConnection jes = jes()) {
            SubmittedJob job = jes.submit("//ORBIT1   JOB (ACCT)\n//S1 EXEC PGM=IEFBR14\n");
            assertThat(job.jobId()).contains("JOB04711");
            assertThat(job.jobName()).contains("ORBIT1");
            assertThat(host.submitted()).hasSize(1);
            jes.delete("JOB04711");
            assertThat(host.jobs()).extracting(FakeMvsFtpHost.Job::id).doesNotContain("JOB04711");
            assertThatThrownBy(() -> jes.delete("JOB09999")).isInstanceOf(FtpReplyException.class);
            assertThatThrownBy(() -> jes.spool("JOB04700", 99)).isInstanceOf(FtpReplyException.class);
        }
    }
}
