package com.aresstack.spaceharvester.deck.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Reine Logik, übernommen aus MainframeMate: Wiederholung, LIST-Zeilen, Sortieren/Filtern, Namensfilter. */
class MainframeMateParityTest {

    @Test
    void retryPolicyFollowsMainframeMate() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        assertThat(policy.shouldRetry(new IOException("Connection reset"), 1)).isTrue();
        assertThat(policy.shouldRetry(new IOException("Connection reset"), 2)).isFalse();
        assertThat(policy.shouldRetry(new SocketTimeoutException("Read timed out"), 1)).isTrue();
        assertThat(policy.shouldRetry(new FtpReplyException("550 not found", 550), 1)).isFalse();

        RetryPolicy codes = new RetryPolicy(4, 100, true, 250, false, false, RetryPolicy.parseCodes("421, 425,x"));
        assertThat(codes.replyCodes()).containsExactlyInAnyOrder(421, 425);
        assertThat(codes.shouldRetry(new FtpReplyException("421 busy", 421), 1)).isTrue();
        assertThat(codes.shouldRetry(new SocketTimeoutException("timed out"), 1)).isFalse();
        assertThat(codes.shouldRetry(new IOException("reset"), 1)).isFalse();
        assertThat(codes.delayMillis(1)).isEqualTo(100);
        assertThat(codes.delayMillis(2)).isEqualTo(200);
        assertThat(codes.delayMillis(3)).isEqualTo(250);
        assertThat(codes.codesText()).isEqualTo("421, 425");
        assertThatThrownBy(() -> new RetryPolicy(0, 0, false, 0, true, true, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsDatasetAndMemberListLines() {
        MvsListing.Line dataset = MvsListing.dataset(
                "WRK001 3390   2026/11/20  1   15  FB      80 27920  PO   KKR.JCL").orElseThrow();
        assertThat(dataset.name()).isEqualTo("KKR.JCL");
        assertThat(dataset.details().format()).isEqualTo("FB/80");
        assertThat(dataset.details().blockSize()).isEqualTo(27920);
        assertThat(dataset.details().organization()).isEqualTo("PO");
        assertThat(dataset.details().volume()).isEqualTo("WRK001");
        assertThat(dataset.modified()).isPresent();
        assertThat(MvsListing.dataset("Volume Unit    Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname")).isEmpty();
        assertThat(MvsListing.dataset("Migrated                                                KKR.ALT").orElseThrow()
                .details().volume()).isEqualTo("MIGRAT");

        MvsListing.Line member = MvsListing.member(
                "BUILD     01.03 2026/11/01 2026/11/20 10:15    12    10     0 KKR").orElseThrow();
        assertThat(member.name()).isEqualTo("BUILD");
        assertThat(member.details().lines()).isEqualTo(12);
        assertThat(member.details().changedBy()).isEqualTo("KKR");
        assertThat(member.modified()).isPresent();
        assertThat(MvsListing.member(" Name     VV.MM   Created       Changed      Size  Init   Mod   Id")).isEmpty();
        assertThat(MvsListing.member("NOSTATS").orElseThrow().details()).isEqualTo(RemoteEntry.Details.NONE);
    }

    @Test
    void sortsWithFoldersFirstAndFiltersLikeTheNavigator() {
        RemoteEntry folder = RemoteEntry.directory("zeta", "/zeta");
        RemoteEntry small = new RemoteEntry("b.txt", "/b.txt", false, 10, Optional.of(Instant.ofEpochSecond(200)));
        RemoteEntry big = new RemoteEntry("a.log", "/a.log", false, 99, Optional.of(Instant.ofEpochSecond(100)));
        List<RemoteEntry> all = List.of(small, folder, big);

        assertThat(EntryList.sorted(all, EntryList.Sort.NAME_ASC)).containsExactly(folder, big, small);
        assertThat(EntryList.sorted(all, EntryList.Sort.NAME_DESC)).containsExactly(folder, small, big);
        assertThat(EntryList.sorted(all, EntryList.Sort.SIZE_DESC)).containsExactly(folder, big, small);
        assertThat(EntryList.sorted(all, EntryList.Sort.DATE_DESC)).containsExactly(folder, small, big);
        assertThat(EntryList.sorted(all, EntryList.Sort.EXTENSION)).containsExactly(folder, big, small);

        // Ohne MVS ein Regex, ungültig wie „enthält“ und rot
        assertThat(EntryList.filtered(all, "^a", false).entries()).containsExactly(big);
        EntryList.Filtered broken = EntryList.filtered(all, "a(", false);
        assertThat(broken.valid()).isFalse();
        assertThat(broken.alarming(3)).isTrue();
        // Auf MVS „enthält“, mit / ein Regex
        assertThat(EntryList.filtered(all, "TXT", true).entries()).containsExactly(small);
        assertThat(EntryList.filtered(all, "/^z", true).entries()).containsExactly(folder);
        assertThat(EntryList.filtered(all, "nix", true).alarming(3)).isTrue();

        assertThat(EntryList.grouped(all)).containsOnlyKeys("Ordner", ".log", ".txt");
        assertThat(EntryList.onlyDatasets(List.of(folder))).isTrue();
        assertThat(EntryList.onlyDatasets(all)).isFalse();
    }

    @Test
    void nameFilterBuildsAnAnchoredRegexFromTicksAndCanBeSwitchedOff() {
        NameFilter filter = new NameFilter();
        AtomicInteger changes = new AtomicInteger();
        filter.observe(changes::incrementAndGet);
        assertThat(filter.matches("KKR")).isTrue();
        filter.toggle("kkr", true);
        filter.toggle("ops", true);
        assertThat(filter.regex()).isEqualTo("^(\\QKKR\\E|\\QOPS\\E)");
        assertThat(filter.matches("KKR")).isTrue();
        assertThat(filter.matches("ABC")).isFalse();
        filter.enabled(false);
        assertThat(filter.matches("ABC")).isTrue();
        filter.enabled(true);
        filter.manual("^A");
        assertThat(filter.selected()).isEmpty();
        assertThat(filter.matches("abc")).isTrue();
        filter.clear();
        assertThat(filter.active()).isFalse();
        assertThat(changes.get()).isEqualTo(6);
    }

    @Test
    void datasetAttributesValidateAndBecomeSiteParameters() {
        assertThat(DatasetAttributes.DEFAULT.siteCommand())
                .isEqualTo("RECFM=FB LRECL=80 BLKSIZE=27920 TRACKS PRIMARY=10 SECONDARY=5 DIRECTORY=10");
        DatasetAttributes sequential = new DatasetAttributes(DatasetAttributes.Organization.PS, "vb", 255, 0, 5, 1, 0);
        assertThat(sequential.siteCommand()).endsWith("DIRECTORY=0").contains("RECFM=VB");
        assertThat(sequential.summary()).doesNotContain("Directory");
        assertThatThrownBy(() -> new DatasetAttributes(DatasetAttributes.Organization.PO, "XX", 80, 0, 1, 0, 1))
                .hasMessageContaining("Satzformat");
        assertThatThrownBy(() -> new DatasetAttributes(DatasetAttributes.Organization.PO, "FB", 40000, 0, 1, 0, 1))
                .hasMessageContaining("LRECL");
    }

    @Test
    void profileKeepsAllSettings() {
        FtpProfile profile = FtpProfile.of("host", 21).withStartHlq("ops")
                .withSpoolNaming(new SpoolNaming(SpoolNaming.Mode.OFF, 3, false))
                .withTransfer(new TransferOptions(TransferOptions.FileType.EBCDIC, null, null, null));
        assertThat(profile.startHlq()).isEqualTo("OPS");
        assertThat(profile.spoolNaming().probeAfterwards()).isTrue();
        assertThat(profile.transfer().recordStructure(true)).isTrue();
        assertThat(profile.transfer().recordStructure(false)).isFalse();
        assertThat(profile.connectTimeoutSeconds()).isEqualTo(60);
        assertThatThrownBy(() -> new SpoolNaming(SpoolNaming.Mode.FAST, 11, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
