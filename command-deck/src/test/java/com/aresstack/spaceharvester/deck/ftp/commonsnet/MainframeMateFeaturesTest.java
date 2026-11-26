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
    void binarySampleAndDownloadKeepEmbeddedControlBytes() throws Exception {
        byte[] raw = new byte[27998];
        java.util.Arrays.fill(raw, (byte) 0x40);
        raw[0] = 0;
        raw[1] = (byte) 0xff;
        raw[2] = 1;
        raw[3] = (byte) 0xff;
        raw[4] = 2;
        host.dataset("KKR.DATA.MIXED", new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1));
        try (FileConnection files = files(host.profile())) {
            var sample = files.readBinarySample("'KKR.DATA.MIXED'", 16);
            assertThat(sample.totalBytes()).isEqualTo(raw.length);
            assertThat(sample.capturedBytes()).isEqualTo(16);
            assertThat(sample.slice(0, 16)).isEqualTo(java.util.Arrays.copyOf(raw, 16));
            assertThat(sample.sha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
            var out = new java.io.ByteArrayOutputStream();
            files.downloadBinary("'KKR.DATA.MIXED'", out);
            assertThat(out.toByteArray()).isEqualTo(raw);
            assertThat(files.readBinary("'KKR.DATA.MIXED'")).isEqualTo(raw);
            assertThat(files.datasetDetails("'KKR.DATA.MIXED'").organization()).isEqualTo("PS");
            var textProjection = files.read("'KKR.DATA.MIXED'");
            assertThat(textProjection.writeRestriction()).contains("Binärdaten");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    files.writeIfUnchanged("'KKR.DATA.MIXED'", "changed", textProjection.hash()))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("Binärdaten");
            assertThat(files.readBinary("'KKR.DATA.MIXED'")).isEqualTo(raw);
            assertThat(files.read("'KKR.JCL(BUILD)'").text()).contains("//BUILD");
            assertThat(host.commands()).contains("TYPE I", "STRU F", "TYPE A", "STRU R");
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
    void withoutProxyConnectionsGoDirectAndByDefaultFollowTheSystem() throws Exception {
        try (com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost host
                = com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost.sample()) {
            org.apache.commons.net.ftp.FTPClient standard = Clients.connect(host.profile(), "KKR",
                    "geheim".toCharArray());
            try {
                org.assertj.core.api.Assertions.assertThat(host.profile().useProxy()).isTrue();
                org.assertj.core.api.Assertions.assertThat(standard.getProxy()).as("Vorgabe wie bisher").isNull();
            } finally {
                Clients.close(standard);
            }
            org.apache.commons.net.ftp.FTPClient direct = Clients.connect(host.profile().withUseProxy(false), "KKR",
                    "geheim".toCharArray());
            try {
                org.assertj.core.api.Assertions.assertThat(direct.getProxy()).isSameAs(java.net.Proxy.NO_PROXY);
            } finally {
                Clients.close(direct);
            }
        }
    }
}
