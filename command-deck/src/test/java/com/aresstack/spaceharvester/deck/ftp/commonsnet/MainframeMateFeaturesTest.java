package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost;
import com.aresstack.spaceharvester.deck.ftp.FileConnection;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.TransferOptions;
import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import com.aresstack.spaceharvester.deck.ftp.jes.JobOutput;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Was aus MainframeMate dazukam, gegen den Schein-z/OS-Host. */
class MainframeMateFeaturesTest {

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

    private FileConnection files(FtpProfile profile) throws Exception {
        return connector.files(profile, "KKR", "geheim".toCharArray());
    }

    private JesConnection jes() throws Exception {
        return connector.jes(host.profile(), "KKR", "geheim".toCharArray());
    }

    @Test
    void detailsBringRecordFormatLinesAndUser() throws Exception {
        try (FileConnection files = files(host.profile())) {
            List<RemoteEntry> top = files.list("'KKR'", true);
            RemoteEntry jcl = top.stream().filter(entry -> entry.name().equals("JCL")).findFirst().orElseThrow();
            assertThat(jcl.details().format()).isEqualTo("FB/80");
            assertThat(jcl.details().organization()).isEqualTo("PO");
            // „SRC“ ist nur eine Qualifier-Stufe und bleibt ohne Angaben
            assertThat(top.stream().filter(entry -> entry.name().equals("SRC")).findFirst().orElseThrow().details())
                    .isEqualTo(RemoteEntry.Details.NONE);

            List<RemoteEntry> members = files.list("'KKR.JCL'", true);
            assertThat(members).extracting(entry -> entry.details().lines()).containsExactly(3, 3);
            assertThat(members).extracting(entry -> entry.details().changedBy()).containsOnly("KKR");
            assertThat(members).allMatch(entry -> entry.modified().isPresent());
            // Ohne Details kein zusätzliches LIST
            int before = host.commands().size();
            files.list("'KKR.JCL'", false);
            assertThat(host.commands().subList(before, host.commands().size())).noneMatch(c -> c.startsWith("LIST"));
        }
    }

    @Test
    void createsDatasetsWithTheirAttributes() throws Exception {
        try (FileConnection files = files(host.profile())) {
            files.createDataset("'KKR.NEU.PDS'", DatasetAttributes.DEFAULT);
            files.createDataset("'KKR.NEU.SEQ'",
                    new DatasetAttributes(DatasetAttributes.Organization.PS, "VB", 255, 0, 3, 1, 0));
        }
        assertThat(host.commands()).contains(
                "SITE RECFM=FB LRECL=80 BLKSIZE=27920 TRACKS PRIMARY=10 SECONDARY=5 DIRECTORY=10",
                "MKD 'KKR.NEU.PDS'",
                "SITE RECFM=VB LRECL=255 BLKSIZE=0 TRACKS PRIMARY=3 SECONDARY=1 DIRECTORY=0",
                "STOR 'KKR.NEU.SEQ'");
        assertThat(host.datasetExists("KKR.NEU.PDS")).isTrue();
        assertThat(host.text("KKR.NEU.SEQ")).isEmpty();
    }

    @Test
    void startHlqAndTransferOptionsComeFromTheProfile() throws Exception {
        FtpProfile profile = host.profile().withStartHlq("ops")
                .withTransfer(new TransferOptions(TransferOptions.FileType.EBCDIC,
                        TransferOptions.TextFormat.CARRIAGE_CONTROL, TransferOptions.Structure.FILE,
                        TransferOptions.TransferMode.STREAM));
        try (FileConnection files = files(profile)) {
            assertThat(files.home()).isEqualTo("'OPS'");
        }
        assertThat(host.commands()).contains("TYPE E C", "STRU F");
    }

    @Test
    void spoolNamesOffShowsNumbersFirstAndProbeFetchesEachFile() throws Exception {
        host.jesInterfaceLevel1();
        try (JesConnection jes = jes()) {
            JobOutput off = JobOutput.load(jes, "JOB04700", new SpoolNaming(SpoolNaming.Mode.OFF, 1, false));
            assertThat(off.files()).extracting(SpoolFile::ddName).containsExactly("SPOOL#1", "SPOOL#2", "SPOOL#3");
            assertThat(off.whole()).isPresent();

            JobOutput probed = JobOutput.load(jes, "JOB04700", new SpoolNaming(SpoolNaming.Mode.PROBE, 1, false));
            assertThat(probed.whole()).isEmpty();
            assertThat(probed.files()).extracting(SpoolFile::ddName)
                    .containsExactly("JESMSGLG", "JESJCL", "SYSPRINT");
            assertThat(probed.files().get(0).records()).isEqualTo(2);

            assertThat(off.withNames(java.util.Map.of(1, "JESMSGLG", 2, "SPOOL#2")).files())
                    .extracting(SpoolFile::ddName).containsExactly("JESMSGLG", "SPOOL#2", "SPOOL#3");
        }
    }

    @Test
    void connectionsAlwaysGoDirectWithoutAnyProxy() throws Exception {
        try (com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost host
                = com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost.sample()) {
            org.apache.commons.net.ftp.FTPClient ftp = Clients.connect(host.profile(), "KKR", "geheim".toCharArray());
            try {
                org.assertj.core.api.Assertions.assertThat(ftp.getProxy()).isSameAs(java.net.Proxy.NO_PROXY);
            } finally {
                Clients.close(ftp);
            }
        }
    }
}
